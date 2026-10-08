// SPDX-License-Identifier: AGPL-3.0-or-later
package cc.wujm.ashlar.tool.mc;

import cc.wujm.ashlar.rpc.MainThread;
import cc.wujm.ashlar.tool.ToolArgError;
import org.bukkit.Bukkit;
import org.bukkit.block.data.BlockData;
import org.bukkit.block.structure.Mirror;
import org.bukkit.block.structure.StructureRotation;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

/** Uses Paper's native transformations, including defaults, rail shapes and handedness. No world writes. */
final class BuildStateTransform {
    // Bound each main-thread callback; repeated states are transformed only once per request.
    private static final int BATCH_SIZE = 64;

    private BuildStateTransform() {}

    static CompletableFuture<McBuild.Args> apply(McBuild.Args args, BuildTransform transform) {
        if (!transform.changesOrientation()) {
            return CompletableFuture.completedFuture(args);
        }
        Map<String, String> states = new LinkedHashMap<>();
        args.fills().forEach(f -> { collect(states, f.block()); collect(states, f.filter()); });
        args.blocks().forEach(b -> collect(states, b.block()));
        args.text().forEach(t -> { collect(states, t.block()); collect(states, t.background()); });
        List<String> keys = new ArrayList<>(states.keySet());
        return batch(keys, states, transform, 0).thenApply(ignored -> new McBuild.Args(args.world(),
                args.fills().stream().map(f -> new McBuild.FillOpArg(f.from(), f.to(), states.get(f.block()),
                        f.mode(), f.filter() == null ? null : partialFilter(f.filter(), states.get(f.filter()), transform))).toList(),
                args.blocks().stream().map(b -> new McBuild.SparseOpArg(b.pos(), states.get(b.block()), b.sign())).toList(),
                args.text().stream().map(t -> new McBuild.TextArg(t.text(), t.pos(), states.get(t.block()),
                        states.get(t.background()), t.facing(), t.scale(), t.spacing(), t.align(), t.expanded())).toList(),
                args.snapshot(), args.connect(), args.liquids()));
    }

    private static void collect(Map<String, String> states, String block) {
        if (block != null) states.putIfAbsent(block, null);
    }

    private static CompletableFuture<Void> batch(List<String> keys, Map<String, String> states,
            BuildTransform transform, int start) {
        if (start >= keys.size()) return CompletableFuture.completedFuture(null);
        int end = Math.min(start + BATCH_SIZE, keys.size());
        return MainThread.call(() -> {
            for (int i = start; i < end; i++) {
                String key = keys.get(i);
                try {
                    BlockData data = Bukkit.createBlockData(key);
                    // FRONT_BACK reflects X, LEFT_RIGHT reflects Z in Minecraft's structure API.
                    data.mirror(switch (transform.mirror()) {
                        case "x" -> Mirror.FRONT_BACK;
                        case "z" -> Mirror.LEFT_RIGHT;
                        default -> Mirror.NONE;
                    });
                    data.rotate(switch (transform.rotation()) {
                        case 90 -> StructureRotation.CLOCKWISE_90;
                        case 180 -> StructureRotation.CLOCKWISE_180;
                        case 270 -> StructureRotation.COUNTERCLOCKWISE_90;
                        default -> StructureRotation.NONE;
                    });
                    states.put(key, data.getAsString());
                } catch (IllegalArgumentException ex) {
                    throw new ToolArgError("transform: invalid block state '" + key + "': " + ex.getMessage());
                }
            }
            return (Void) null;
        }).thenCompose(ignored -> batch(keys, states, transform, end));
    }

    /** Keep only explicitly supplied filter properties: omitted ones must remain wildcards. */
    static String partialFilter(String original, String transformed, BuildTransform transform) {
        int bracket = original.indexOf('[');
        int transformedBracket = transformed.indexOf('[');
        String material = transformedBracket < 0 ? transformed : transformed.substring(0, transformedBracket);
        if (bracket < 0 || original.substring(bracket + 1, original.length() - 1).isEmpty()) return material;
        Map<String, String> properties = new LinkedHashMap<>();
        if (transformedBracket >= 0) {
            for (String property : transformed.substring(transformedBracket + 1, transformed.length() - 1).split(",")) {
                String[] pair = property.split("=", 2);
                properties.put(pair[0], pair[1]);
            }
        }
        List<String> selected = new ArrayList<>();
        for (String property : original.substring(bracket + 1, original.length() - 1).split(",")) {
            String name = transform.direction(property.split("=", 2)[0]);
            if (!properties.containsKey(name)) {
                throw new ToolArgError("transform: cannot preserve filter property '" + name + "'");
            }
            selected.add(name + "=" + properties.get(name));
        }
        return material + "[" + String.join(",", selected) + "]";
    }
}
