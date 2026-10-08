// SPDX-License-Identifier: AGPL-3.0-or-later
package cc.wujm.ashlar.tool.mc;

import cc.wujm.ashlar.rpc.InvocationContext;
import cc.wujm.ashlar.tool.ArgParse;
import cc.wujm.ashlar.tool.Tool;
import cc.wujm.ashlar.tool.ToolResult;
import cc.wujm.ashlar.tool.ToolRunner;
import cc.wujm.ashlar.tool.ToolSpec;
import com.google.gson.JsonObject;

import java.util.List;
import java.util.Set;
import java.util.concurrent.CompletableFuture;

/** Blueprint storage and optional pure architectural generation; world writes remain in mc_build. */
public final class McBlueprint implements Tool {
    private final ToolSpec spec = ToolSpec.load("mc_blueprint");
    private final BlueprintStore store;

    public McBlueprint(BlueprintStore store) { this.store = store; }
    @Override public ToolSpec spec() { return spec; }

    @Override public CompletableFuture<ToolResult> call(InvocationContext ctx, JsonObject args) {
        return ToolRunner.runText("mc_blueprint", () -> {
            String action = ArgParse.requireEnum(args, "action", List.of("save", "get", "list", "delete", "generate"));
            Set<String> allowed = switch (action) {
                case "save" -> Set.of("action", "id", "document", "overwrite");
                case "generate" -> Set.of("action", "id", "generator", "overwrite");
                case "list" -> Set.of("action");
                default -> Set.of("action", "id");
            };
            BlueprintCompiler.fields(args, allowed, "mc_blueprint");
            String result = switch (action) {
                case "save" -> store.save(ArgParse.requireString(args, "id"),
                        ArgParse.requireObject(args.get("document"), "document"),
                        ArgParse.optBoolean(args, "overwrite", false)).toString();
                case "generate" -> generate(args).toString();
                case "get" -> store.get(ArgParse.requireString(args, "id")).toString();
                case "list" -> store.list().toString();
                case "delete" -> {
                    String id = ArgParse.requireString(args, "id"); store.delete(id);
                    yield "Blueprint '" + id + "' deleted. World blocks are unchanged.";
                }
                default -> throw new IllegalStateException();
            };
            return CompletableFuture.completedFuture(result);
        });
    }

    private JsonObject generate(JsonObject args) {
        String id = ArgParse.requireString(args, "id");
        BlueprintCompiler.name(id);
        var generated = ArchitecturalGenerator.generate(ArgParse.requireObject(args.get("generator"), "generator"));
        JsonObject palette = generated.document().getAsJsonObject("palette");
        for (String role : palette.keySet()) {
            String state = palette.get(role).getAsString();
            try {
                var data = org.bukkit.Bukkit.createBlockData(state);
                if (!data.getMaterial().isSolid()) throw new IllegalArgumentException("must be a solid block");
                if (role.equals("stairs") && !(data instanceof org.bukkit.block.data.type.Stairs)) throw new IllegalArgumentException("must be a stair material");
                if (role.equals("slab") && !(data instanceof org.bukkit.block.data.type.Slab)) throw new IllegalArgumentException("must be a slab material");
            } catch (IllegalArgumentException e) { throw new cc.wujm.ashlar.tool.ToolArgError("generator.materials." + role + ": " + e.getMessage()); }
        }
        JsonObject request = new JsonObject(), selector = new JsonObject();
        selector.addProperty("id", id); request.add("blueprint", selector);
        var states = new java.util.HashSet<String>();
        for (var part : BlueprintCompiler.compile(generated.document(), request, BlueprintCompiler.Limits.DEFAULT))
            for (var fill : part.args().fills()) states.add(fill.block());
        for (String state : states) {
            try { org.bukkit.Bukkit.createBlockData(state); }
            catch (IllegalArgumentException e) { throw new cc.wujm.ashlar.tool.ToolArgError("invalid generated state " + state + ": " + e.getMessage()); }
        }
        JsonObject saved = store.save(id, generated.document(), ArgParse.optBoolean(args, "overwrite", false));
        saved.add("generator", generated.summary());
        return saved;
    }
}
