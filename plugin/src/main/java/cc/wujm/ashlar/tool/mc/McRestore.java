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

    static final int MAX_JOURNALS = 50;

    /** {@code mc_restore {journal, mode, dryRun, allowBlockEntityReplacement, samples}}. */
    static JournalService.UndoArgs parseUndo(JsonObject o) {
        if (ArgParse.has(o, "id")) throw new ToolArgError("pass either id (a snapshot) or journal (a journal entry), not both");
        String journal = ArgParse.requireString(o, "journal");
        checkJournalId(journal, "journal");
        return undoOptions(o, journal);
    }

    /** {@code mc_restore {journals:[...], ...}}: the ids, distinct and well-formed. */
    static List<String> parseJournals(JsonObject o) {
        if (ArgParse.has(o, "id")) throw new ToolArgError("pass either id (a snapshot) or journals, not both");
        if (ArgParse.has(o, "journal")) throw new ToolArgError("pass either journal (one id) or journals (a list), not both");
        if (!o.get("journals").isJsonArray()) throw new ToolArgError("journals must be an array of journal ids");
        JsonArray arr = o.getAsJsonArray("journals");
        if (arr.isEmpty() || arr.size() > MAX_JOURNALS) throw new ToolArgError("journals: 1 to " + MAX_JOURNALS + " ids");
        List<String> ids = new ArrayList<>();
        for (int i = 0; i < arr.size(); i++) {
            JsonElement el = arr.get(i);
            if (!el.isJsonPrimitive() || !el.getAsJsonPrimitive().isString()) throw new ToolArgError("journals[" + i + "] must be a string");
            String id = el.getAsString();
            checkJournalId(id, "journals[" + i + "]");
            if (ids.contains(id)) throw new ToolArgError("journals[" + i + "]: " + id + " is listed twice");
            ids.add(id);
        }
        return ids;
    }

    private static void checkJournalId(String id, String field) {
        if (!id.matches("jrn-\\d{8}-\\d{6}-[0-9a-f]{4}")) {
            throw new ToolArgError(field + " must be an id like \"jrn-20261010-120239-aa42\"");
        }
    }

    private static JournalService.UndoArgs undoOptions(JsonObject o, String journal) {
        UndoRules.Mode mode = ArgParse.optEnum(o, "mode", List.of("safe", "force"), "safe").equals("force")
                ? UndoRules.Mode.FORCE : UndoRules.Mode.SAFE;
        return new JournalService.UndoArgs(journal, mode, ArgParse.optBoolean(o, "dryRun", false),
                ArgParse.optBoolean(o, "allowBlockEntityReplacement", false), McVerify.bounded(o, "samples", 20, 0, 200));
    }

    @Override
    public CompletableFuture<ToolResult> call(InvocationContext ctx, JsonObject args) {
        if (ArgParse.has(args, "journals")) {
            return ToolRunner.runText("mc_restore", () -> {
                List<String> ids = parseJournals(args);
                JournalService.UndoArgs undo = undoOptions(args, null);
                List<String> override = ProtectionGuard.parseOverride(args);
                if (journals == null) throw new ToolArgError("the build journal is unavailable");
                return journals.undoAll(ctx, ids, undo, protection == null ? null : protection.gate(ctx, "mc_restore", override));
            });
        }
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
            if (r.has("extras")) {
                String x = cc.wujm.ashlar.engine.SnapshotExtrasTask.text(r.getAsJsonObject("extras"));
                if (x != null) lines.add(x);
            }
            return String.join("\n", lines);
        });
    }
}
