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

    static final int MAX_COMMANDS = 64;

    /** Lines of feedback kept per command in a {@code commands} batch. */
    static final int BATCH_LINES = 3;

    record Args(String command, boolean vanilla) {
        static Args parse(JsonObject o) {
            if (ArgParse.has(o, "commands")) throw new ToolArgError("pass either command or commands, not both");
            String command = ArgParse.requireString(o, "command");
            if (command.isEmpty()) {
                throw new ToolArgError("command: must contain at least 1 character(s)");
            }
            refuseLocate(command);
            return new Args(command, ArgParse.optBoolean(o, "vanilla", false));
        }
    }

    /**
     * Refuses {@code /locate}, also namespaced or after {@code execute ... run}. It searches on the
     * main thread and cannot be interrupted; one far biome search outran Paper's 60 s watchdog and
     * crashed the server.
     */
    static void refuseLocate(String command) {
        String[] tokens = command.strip().toLowerCase(java.util.Locale.ROOT).split("\\s+");
        tokens[0] = tokens[0].startsWith("/") ? tokens[0].substring(1) : tokens[0];
        boolean execute = tokens[0].equals("execute") || tokens[0].equals("minecraft:execute");
        for (int i = 0; i < tokens.length; i++) {
            if (i > 0 && !(execute && tokens[i - 1].equals("run"))) continue;
            String t = tokens[i];
            if (t.equals("locate") || t.equals("minecraft:locate")) {
                throw new ToolArgError("locate is blocked: it searches on the main thread and a long search can freeze the server until"
                        + " the watchdog kills it. Nothing was run. Find biomes and structures offline from the world's region files instead.");
            }
        }
    }

    /** {@code commands}: 1-64 non-empty strings, each run like {@code command} with the same {@code vanilla}. */
    static List<Args> parseBatch(JsonObject o) {
        if (ArgParse.has(o, "command")) throw new ToolArgError("pass either command or commands, not both");
        if (!o.get("commands").isJsonArray()) throw new ToolArgError("commands must be an array of strings");
        JsonArray arr = o.getAsJsonArray("commands");
        if (arr.isEmpty() || arr.size() > MAX_COMMANDS) throw new ToolArgError("commands: 1 to " + MAX_COMMANDS + " commands");
        boolean vanilla = ArgParse.optBoolean(o, "vanilla", false);
        List<Args> out = new ArrayList<>();
        for (int i = 0; i < arr.size(); i++) {
            JsonElement el = arr.get(i);
            if (!el.isJsonPrimitive() || !el.getAsJsonPrimitive().isString() || el.getAsString().isEmpty()) {
                throw new ToolArgError("commands[" + i + "] must be a non-empty string");
            }
            try {
                refuseLocate(el.getAsString());
            } catch (ToolArgError e) {
                throw new ToolArgError("commands[" + i + "]: " + e.getMessage());
            }
            out.add(new Args(el.getAsString(), vanilla));
        }
        return out;
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
        if (ArgParse.has(args, "commands")) {
            return ToolRunner.runText("mc_command", () -> {
                List<Args> batch = parseBatch(args);
                // Route every command first, so a bad vanilla:true name rejects the call before anything runs.
                return MainThread.call(() -> {
                    List<Routed> routed = new ArrayList<>();
                    for (int i = 0; i < batch.size(); i++) {
                        try {
                            routed.add(route(batch.get(i), BUKKIT));
                        } catch (ToolArgError e) {
                            throw new ToolArgError("commands[" + i + "]: " + e.getMessage());
                        }
                    }
                    return routed;
                }).thenCompose(routed -> runBatch(ctx, routed));
            });
        }
        return ToolRunner.runText("mc_command", () -> {
            Args a = Args.parse(args);
            return MainThread.call(() -> route(a, BUKKIT)).thenCompose(routed -> run(ctx, routed));
        });
    }

    /** Runs the commands in order; each gets a short result, and notes about plugin overrides are listed once. */
    private CompletableFuture<String> runBatch(InvocationContext ctx, List<Routed> routed) {
        List<String> lines = new ArrayList<>();
        java.util.Set<String> notes = new java.util.LinkedHashSet<>();
        int[] problems = {0};
        CompletableFuture<Void> chain = CompletableFuture.completedFuture(null);
        for (int i = 0; i < routed.size(); i++) {
            int index = i;
            Routed one = routed.get(i);
            chain = chain.thenCompose(v -> {
                JsonObject params = new JsonObject();
                params.addProperty("command", one.command());
                return runCommandHandler.handle(ctx, params).thenAccept(el -> {
                    JsonObject r = el.getAsJsonObject();
                    List<String> result = resultLines(r, new Routed(one.command(), null));
                    boolean problem = r.has("syntaxError") || !r.get("dispatched").getAsBoolean();
                    if (problem) problems[0]++;
                    List<String> body = String.join("\n", result).lines().toList();
                    String head = "[" + index + "] " + one.command() + ": ";
                    if (body.size() == 1) {
                        lines.add(head + body.getFirst());
                    } else {
                        lines.add(head.stripTrailing());
                        int keep = problem ? body.size() : Math.min(BATCH_LINES, body.size());
                        for (int k = 0; k < keep; k++) lines.add("  " + body.get(k));
                        if (body.size() > keep) lines.add("  (+" + (body.size() - keep) + " more lines)");
                    }
                    if (one.note() != null) notes.add(one.note());
                });
            });
        }
        return chain.thenApply(v -> {
            List<String> out = new ArrayList<>();
            out.add("Ran " + routed.size() + " command(s)" + (problems[0] > 0 ? "; " + problems[0] + " failed or were not dispatched" : "") + ":");
            out.addAll(lines);
            out.addAll(notes);
            return String.join("\n", out);
        });
    }

    private CompletableFuture<String> run(InvocationContext ctx, Routed routed) {
        JsonObject params = new JsonObject();
        params.addProperty("command", routed.command());
        return runCommandHandler.handle(ctx, params).thenApply(el -> String.join("\n", resultLines(el.getAsJsonObject(), routed)));
    }

    private static List<String> resultLines(JsonObject r, Routed routed) {
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
        return lines;
    }
}
