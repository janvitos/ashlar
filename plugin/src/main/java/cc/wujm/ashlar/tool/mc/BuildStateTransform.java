// SPDX-License-Identifier: AGPL-3.0-or-later
package cc.wujm.ashlar.tool.mc;

import cc.wujm.ashlar.rpc.MainThread;
import cc.wujm.ashlar.tool.ToolArgError;
import org.bukkit.Bukkit;
import org.bukkit.block.data.BlockData;
import org.bukkit.block.data.type.Stairs;
import org.bukkit.block.structure.Mirror;
import org.bukkit.block.structure.StructureRotation;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

/** Uses native transforms with geometrically correct stair reflections. No world writes. */
final class BuildStateTransform {
    private static final int BATCH_SIZE = 64;
    private record Key(String block, int rotation, String mirror) {}

    private BuildStateTransform() {}

    static CompletableFuture<McBuild.Args> apply(McBuild.Args args, BuildTransform transform) {
        return applyParts(List.of(new McBuild.Part(args, transform)), false);
    }

    /** All components share one request-local state cache and are prepared before any snapshot/write. */
    static CompletableFuture<McBuild.Args> applyParts(List<McBuild.Part> parts, boolean validateIdentity) {
        Map<Key, String> states = new LinkedHashMap<>();
        for (McBuild.Part part : parts) {
            if (!validateIdentity && !part.transform().changesOrientation()) continue;
            McBuild.Args args = part.args();
            args.fills().forEach(f -> { collect(states, f.block(), part.transform()); collect(states, f.filter(), part.transform()); });
            args.blocks().forEach(b -> collect(states, b.block(), part.transform()));
            args.text().forEach(t -> { collect(states, t.block(), part.transform()); collect(states, t.background(), part.transform()); });
        }
        return batch(new ArrayList<>(states.keySet()), states, 0).thenApply(ignored -> {
            List<McBuild.Args> prepared = new ArrayList<>();
            for (McBuild.Part part : parts) {
                McBuild.Args args = part.args();
                BuildTransform transform = part.transform();
                if (!validateIdentity && !transform.changesOrientation()) { prepared.add(args); continue; }
                prepared.add(new McBuild.Args(args.world(),
                        args.fills().stream().map(f -> new McBuild.FillOpArg(f.from(), f.to(), state(states, f.block(), transform),
                                f.mode(), f.filter() == null ? null : partialFilter(f.filter(), state(states, f.filter(), transform), transform))).toList(),
                        args.blocks().stream().map(b -> new McBuild.SparseOpArg(b.pos(), state(states, b.block(), transform), b.sign())).toList(),
                        args.text().stream().map(t -> new McBuild.TextArg(t.text(), t.pos(), state(states, t.block(), transform),
                                state(states, t.background(), transform), t.facing(), t.scale(), t.spacing(), t.align(), t.expanded())).toList(),
                        args.snapshot(), args.connect(), args.liquids()));
            }
            return McBuild.combine(prepared);
        });
    }

    private static void collect(Map<Key, String> states, String block, BuildTransform t) {
        if (block != null) states.putIfAbsent(new Key(block, t.rotation(), t.mirror()), null);
    }

    private static String state(Map<Key, String> states, String block, BuildTransform t) {
        return block == null ? null : states.get(new Key(block, t.rotation(), t.mirror()));
    }

    private static CompletableFuture<Void> batch(List<Key> keys, Map<Key, String> states, int start) {
        if (start >= keys.size()) return CompletableFuture.completedFuture(null);
        int end = Math.min(start + BATCH_SIZE, keys.size());
        return MainThread.call(() -> {
            for (int i = start; i < end; i++) {
                Key key = keys.get(i);
                try {
                    BlockData data = Bukkit.createBlockData(key.block());
                    Stairs.Shape originalStairShape = data instanceof Stairs stairs ? stairs.getShape() : null;
                    // FRONT_BACK reflects X, LEFT_RIGHT reflects Z in Minecraft's structure API.
                    data.mirror(switch (key.mirror()) {
                        case "x" -> Mirror.FRONT_BACK;
                        case "z" -> Mirror.LEFT_RIGHT;
                        default -> Mirror.NONE;
                    });
                    data.rotate(switch (key.rotation()) {
                        case 90 -> StructureRotation.CLOCKWISE_90;
                        case 180 -> StructureRotation.CLOCKWISE_180;
                        case 270 -> StructureRotation.COUNTERCLOCKWISE_90;
                        default -> StructureRotation.NONE;
                    });
                    if (originalStairShape != null && !key.mirror().equals("none")) {
                        // Verified on Paper 26.2: a true reflection always reverses corner chirality.
                        BuildTransform t = new BuildTransform(0, 0, 0, key.rotation(), key.mirror());
                        ((Stairs) data).setShape(Stairs.Shape.valueOf(t.stairShape(originalStairShape.name())));
                    }
                    states.put(key, data.getAsString());
                } catch (IllegalArgumentException ex) {
                    throw new ToolArgError("transform: invalid block state '" + key.block() + "': " + ex.getMessage());
                }
            }
            return (Void) null;
        }).thenCompose(ignored -> batch(keys, states, end));
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
            if (!properties.containsKey(name)) throw new ToolArgError("transform: cannot preserve filter property '" + name + "'");
            selected.add(name + "=" + properties.get(name));
        }
        return material + "[" + String.join(",", selected) + "]";
    }
}
