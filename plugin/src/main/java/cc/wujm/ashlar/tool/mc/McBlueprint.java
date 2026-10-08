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

/** Blueprint storage only; compilation and world writes remain in mc_build. */
public final class McBlueprint implements Tool {
    private final ToolSpec spec = ToolSpec.load("mc_blueprint");
    private final BlueprintStore store;

    public McBlueprint(BlueprintStore store) { this.store = store; }
    @Override public ToolSpec spec() { return spec; }

    @Override public CompletableFuture<ToolResult> call(InvocationContext ctx, JsonObject args) {
        return ToolRunner.runText("mc_blueprint", () -> {
            String action = ArgParse.requireEnum(args, "action", List.of("save", "get", "list", "delete"));
            Set<String> allowed = switch (action) {
                case "save" -> Set.of("action", "id", "document", "overwrite");
                case "list" -> Set.of("action");
                default -> Set.of("action", "id");
            };
            BlueprintCompiler.fields(args, allowed, "mc_blueprint");
            String result = switch (action) {
                case "save" -> store.save(ArgParse.requireString(args, "id"),
                        ArgParse.requireObject(args.get("document"), "document"),
                        ArgParse.optBoolean(args, "overwrite", false)).toString();
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
}
