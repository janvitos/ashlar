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
    private final TerrainFitService terrain;

    public McBlueprint(BlueprintStore store) { this(store,null); }
    public McBlueprint(BlueprintStore store,TerrainFitService terrain) { this.store = store; this.terrain=terrain; }
    private CellFiles cellFiles;
    /** Enables {@code stage}/{@code finalize} uploads for {@code mc_build cellsFile} (Step 16). */
    public McBlueprint withCells(CellFiles files) { this.cellFiles = files; return this; }
    @Override public ToolSpec spec() { return spec; }

    @Override public CompletableFuture<ToolResult> call(InvocationContext ctx, JsonObject args) {
        return ToolRunner.runText("mc_blueprint", () -> {
            String action = ArgParse.requireEnum(args, "action", List.of("save", "get", "list", "delete", "generate", "fit", "stage", "finalize", "tag", "touches"));
            Set<String> allowed = switch (action) {
                case "save" -> Set.of("action", "id", "document", "overwrite");
                case "generate" -> Set.of("action", "id", "generator", "overwrite");
                case "fit" -> Set.of("action", "id", "site", "overwrite");
                case "list" -> Set.of("action", "tags");
                case "tag" -> Set.of("action", "id", "tags");
                case "touches" -> Set.of("action", "from", "to");
                case "stage" -> Set.of("action", "name", "cells", "reset");
                case "finalize" -> Set.of("action", "name");
                default -> Set.of("action", "id");
            };
            BlueprintCompiler.fields(args, allowed, "mc_blueprint");
            if (action.equals("stage") || action.equals("finalize")) {
                if (cellFiles == null) throw new cc.wujm.ashlar.tool.ToolArgError("cell staging is unavailable");
                String name = ArgParse.requireString(args, "name");
                if (action.equals("stage")) {
                    var st = cellFiles.stage(name, ArgParse.requireArray(args, "cells"), ArgParse.optBoolean(args, "reset", false), CellBuild.MAX_CELLS);
                    return CompletableFuture.completedFuture("Staged upload '" + st.name() + "': " + st.cells() + " cells in " + st.calls()
                            + " call(s). Add more with further stage calls, then action \"finalize\". Nothing is built yet.");
                }
                var st = cellFiles.finalizeStage(name, CellBuild.MAX_CELLS);
                return CompletableFuture.completedFuture("Staged file '" + st.name() + "' is ready: " + st.cells()
                        + " cells checked. Build it with mc_build {cellsFile:\"" + st.name() + "\"} or compare with mc_diff {against:{expectedFile:\"" + st.name() + "\"}}.");
            }
            if(action.equals("fit")) {
                if(terrain==null)throw new cc.wujm.ashlar.tool.ToolArgError("terrain fitting service is unavailable");
                return terrain.fit(ctx,args,store);
            }
            String result = switch (action) {
                case "save" -> store.save(ArgParse.requireString(args, "id"),
                        ArgParse.requireObject(args.get("document"), "document"),
                        ArgParse.optBoolean(args, "overwrite", false)).toString();
                case "generate" -> generate(args).toString();
                case "get" -> store.get(ArgParse.requireString(args, "id")).toString();
                case "list" -> {
                    if (!ArgParse.has(args, "tags")) yield store.list().toString();
                    JsonObject filter = ArgParse.requireObject(args.get("tags"), "tags");
                    BlueprintCompiler.tags(filter);
                    yield store.list(filter).toString();
                }
                case "tag" -> store.tag(ArgParse.requireString(args, "id"), ArgParse.requireObject(args.get("tags"), "tags")).toString();
                case "touches" -> {
                    var box = cc.wujm.ashlar.engine.Region.of(BuildTransform.strictCoords(args, "from"), BuildTransform.strictCoords(args, "to"));
                    yield store.touches(new int[] {box.minX(), box.minY(), box.minZ()}, new int[] {box.maxX(), box.maxY(), box.maxZ()}).toString();
                }
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
