// SPDX-License-Identifier: AGPL-3.0-or-later
package cc.wujm.ashlar.tool.mc;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import cc.wujm.ashlar.rpc.InvocationContext;
import cc.wujm.ashlar.rpc.MainThread;
import cc.wujm.ashlar.rpc.RpcHandler;
import cc.wujm.ashlar.tool.ArgParse;
import cc.wujm.ashlar.tool.Tool;
import cc.wujm.ashlar.tool.ToolArgError;
import cc.wujm.ashlar.tool.ToolResult;
import cc.wujm.ashlar.tool.ToolRunner;
import cc.wujm.ashlar.tool.ToolSpec;

import org.bukkit.Bukkit;
import org.bukkit.command.Command;
import org.bukkit.command.PluginIdentifiableCommand;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;

/**
 * Pure-Java port of {@code mcp-server/src/tools/mc-command.ts}. Delegates straight to the plugin's
 * {@code run_command} handler, which already enforces {@code run-command.enabled} and normalizes a
 * leading {@code "/"} - so this tool respects the config gate exactly like the RPC does, with no
 * separate check here.
 */
public final class McCommand implements Tool {

    private final ToolSpec spec = ToolSpec.load("mc_command");
    private final RpcHandler runCommandHandler;

    public McCommand(RpcHandler runCommandHandler) {
        this.runCommandHandler = runCommandHandler;
    }

    @Override
    public ToolSpec spec() {
        return spec;
    }

    record Args(String command, boolean vanilla) {
        static Args parse(JsonObject o) {
            String command = ArgParse.requireString(o, "command");
            if (command.isEmpty()) {
                throw new ToolArgError("command: must contain at least 1 character(s)");
            }
            return new Args(command, ArgParse.optBoolean(o, "vanilla", false));
        }
    }

    /** The console's command map (main thread). */
    private static final CommandShadow.Lookup BUKKIT = new CommandShadow.Lookup() {
        @Override
        public String owner(String label) {
            Command c = Bukkit.getCommandMap().getCommand(label);
            if (c == null) return null;
            return c instanceof PluginIdentifiableCommand p ? p.getPlugin().getName() : "";
        }

        @Override
        public boolean exists(String label) {
            return Bukkit.getCommandMap().getCommand(label) != null;
        }
    };

    /** Step 19: the command to send and a note about a plugin override, from the command-map lookup. */
    record Routed(String command, String note) {}

    static Routed route(Args a, CommandShadow.Lookup lookup) {
        String command = a.command().startsWith("/") ? a.command().substring(1) : a.command();
        CommandShadow.Result r = CommandShadow.check(command, lookup);
        if (a.vanilla()) {
            if (r == null || !r.vanilla()) {
                String label = CommandShadow.label(command);
                throw new ToolArgError(label == null ? "vanilla: the command already names a namespace (or is empty); drop vanilla:true"
                        : "vanilla: there is no vanilla command \"" + label + "\"; nothing was run");
            }
            return new Routed(CommandShadow.vanilla(command, r.label()),
                    r.plugin() != null ? "Ran as minecraft:" + r.label() + " (vanilla:true); \"" + r.label() + "\" alone is " + r.plugin() + "'s command here." : null);
        }
        if (r != null && r.shadows()) {
            return new Routed(command, "Note: \"" + r.label() + "\" is " + r.plugin() + "'s command here, not vanilla; use minecraft:" + r.label()
                    + " or vanilla:true for vanilla behaviour and output.");
        }
        return new Routed(command, null);
    }

    @Override
    public CompletableFuture<ToolResult> call(InvocationContext ctx, JsonObject args) {
        return ToolRunner.runText("mc_command", () -> {
            Args a = Args.parse(args);
            return MainThread.call(() -> route(a, BUKKIT)).thenCompose(routed -> run(ctx, routed));
        });
    }

    private CompletableFuture<String> run(InvocationContext ctx, Routed routed) {
        JsonObject params = new JsonObject();
        params.addProperty("command", routed.command());
        return runCommandHandler.handle(ctx, params).thenApply(el -> {
            JsonObject r = el.getAsJsonObject();
            List<String> lines = new ArrayList<>();
            JsonArray output = r.getAsJsonArray("output");
            if (output.size() > 0) {
                List<String> outLines = new ArrayList<>();
                for (JsonElement o : output) {
                    outLines.add(o.getAsString());
                }
                lines.add(String.join("\n", outLines));
            } else {
                lines.add("(command produced no feedback)");
            }
            if (r.has("syntaxError")) {
                String err = "Syntax error: " + r.get("syntaxError").getAsString();
                if (output.isEmpty()) lines.set(0, err); else lines.add(err);
            } else if (!r.get("dispatched").getAsBoolean()) {
                lines.add("Warning: command \"" + r.get("command").getAsString()
                        + "\" was not dispatched (rejected by the server's command dispatcher).");
            }
            if (r.has("truncated") && r.get("truncated").getAsBoolean()) {
                lines.add("[output truncated]");
            }
            if (routed.note() != null) lines.add(routed.note());
            return String.join("\n", lines);
        });
    }
}
