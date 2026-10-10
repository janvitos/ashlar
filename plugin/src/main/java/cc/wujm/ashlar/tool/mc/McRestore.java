// SPDX-License-Identifier: AGPL-3.0-or-later
package cc.wujm.ashlar.tool.mc;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import cc.wujm.ashlar.rpc.InvocationContext;
import cc.wujm.ashlar.rpc.RpcHandler;
import cc.wujm.ashlar.tool.ArgParse;
import cc.wujm.ashlar.tool.Tool;
import cc.wujm.ashlar.tool.ToolArgError;
import cc.wujm.ashlar.tool.ToolResult;
import cc.wujm.ashlar.tool.ToolRunner;
import cc.wujm.ashlar.tool.ToolSpec;
import cc.wujm.ashlar.journal.UndoRules;
import cc.wujm.ashlar.tool.text.WarningText;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;

/** Pure-Java port of {@code mcp-server/src/tools/mc-restore.ts}. No text differences from the TS tool. */
public final class McRestore implements Tool {

    private final ToolSpec spec = ToolSpec.load("mc_restore");
    private final RpcHandler restoreHandler;
    private final JournalService journals;

    public McRestore(RpcHandler restoreHandler) {
        this(restoreHandler, null);
    }

    public McRestore(RpcHandler restoreHandler, JournalService journals) {
        this.restoreHandler = restoreHandler;
        this.journals = journals;
    }

    private ProtectionGuard protection;
    private java.util.function.Function<String, java.util.Optional<cc.wujm.ashlar.snapshot.Snapshot>> snapshots;

    /** Applies protected regions to snapshot restores (the snapshot's whole box) and journal undos (every journalled cell). */
    public McRestore withProtection(ProtectionGuard guard,
            java.util.function.Function<String, java.util.Optional<cc.wujm.ashlar.snapshot.Snapshot>> snapshotLookup) {
        this.protection = guard;
        this.snapshots = snapshotLookup;
        return this;
    }

    @Override
    public ToolSpec spec() {
        return spec;
    }

    record Args(String id) {
        static Args parse(JsonObject o) {
            for (String k : List.of("mode", "dryRun", "allowBlockEntityReplacement", "samples")) {
                if (ArgParse.has(o, k)) throw new ToolArgError(k + " only applies to a journal undo");
            }
            String id = ArgParse.requireString(o, "id");
            if (id.isEmpty()) {
                throw new ToolArgError("id: must contain at least 1 character(s)");
            }
            return new Args(id);
        }
    }

    /** {@code mc_restore {journal, mode, dryRun, allowBlockEntityReplacement, samples}}. */
    static JournalService.UndoArgs parseUndo(JsonObject o) {
        if (ArgParse.has(o, "id")) throw new ToolArgError("pass either id (a snapshot) or journal (a journal entry), not both");
        String journal = ArgParse.requireString(o, "journal");
        if (!journal.matches("jrn-\\d{8}-\\d{6}-[0-9a-f]{4}")) {
            throw new ToolArgError("journal must be an id like \"jrn-20261010-120239-aa42\"");
        }
        UndoRules.Mode mode = ArgParse.optEnum(o, "mode", List.of("safe", "force"), "safe").equals("force")
                ? UndoRules.Mode.FORCE : UndoRules.Mode.SAFE;
        return new JournalService.UndoArgs(journal, mode, ArgParse.optBoolean(o, "dryRun", false),
                ArgParse.optBoolean(o, "allowBlockEntityReplacement", false), McVerify.bounded(o, "samples", 20, 0, 200));
    }

    @Override
    public CompletableFuture<ToolResult> call(InvocationContext ctx, JsonObject args) {
        if (ArgParse.has(args, "journal")) {
            return ToolRunner.runText("mc_restore", () -> {
                JournalService.UndoArgs undo = parseUndo(args);
                List<String> override = ProtectionGuard.parseOverride(args);
                if (journals == null) throw new ToolArgError("the build journal is unavailable");
                return journals.undo(ctx, undo, protection == null ? null : protection.gate(ctx, "mc_restore", override));
            });
        }
        return ToolRunner.runText("mc_restore", () -> {
            Args a = Args.parse(args);
            List<String> override = ProtectionGuard.parseOverride(args);
            JsonObject params = new JsonObject();
            params.addProperty("id", a.id());
            List<String> guardLines = List.of();
            var snapshot = protection == null || snapshots == null ? java.util.Optional.<cc.wujm.ashlar.snapshot.Snapshot>empty() : snapshots.apply(a.id());
            if (snapshot.isPresent()) {
                // A restore may write any cell of the snapshot box. An unknown id is left to the restore handler's error.
                guardLines = protection.enforce(ctx, "mc_restore", snapshot.get().world(), override,
                        check -> check.box(snapshot.get().region(), cc.wujm.ashlar.protect.ProtectionCheck.Shape.SOLID));
            }
            List<String> extra = guardLines;
            if (journals == null) return restoreText(restoreHandler.handle(ctx, params)).thenApply(text -> append(text, extra));
            return journals.journalled(ctx, true, "mc_restore", "restore of " + a.id(), c -> restoreText(restoreHandler.handle(c, params)),
                    (text, commit) -> append(commit.lines().isEmpty() ? text : text + "\n" + String.join("\n", commit.lines()), extra));
        });
    }

    private static String append(String text, List<String> lines) {
        return lines.isEmpty() ? text : text + "\n" + String.join("\n", lines);
    }

    private static CompletableFuture<String> restoreText(CompletableFuture<JsonElement> restore) {
        return restore.thenApply(el -> {
            JsonObject r = el.getAsJsonObject();
            List<String> lines = new ArrayList<>();
            lines.add("Restored snapshot " + r.get("id").getAsString() + ": " + r.get("restored").getAsLong() + "/"
                    + r.get("volume").getAsLong() + " blocks changed in " + r.get("elapsedMs").getAsLong() + "ms.");
            List<WarningText.SupportWarning> warnings = new ArrayList<>();
            for (JsonElement we : r.getAsJsonArray("warnings")) {
                JsonObject w = we.getAsJsonObject();
                JsonArray pos = w.getAsJsonArray("pos");
                warnings.add(new WarningText.SupportWarning(pos.get(0).getAsInt(), pos.get(1).getAsInt(), pos.get(2).getAsInt(),
                        w.get("block").getAsString(), w.get("reason").getAsString()));
            }
            boolean truncated = r.has("warningsTruncated") && r.get("warningsTruncated").getAsBoolean();
            lines.addAll(WarningText.formatWarnings(warnings, truncated));
            return String.join("\n", lines);
        });
    }
}
