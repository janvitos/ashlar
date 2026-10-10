// SPDX-License-Identifier: AGPL-3.0-or-later
package cc.wujm.ashlar.tool.mc;

import cc.wujm.ashlar.config.ConfigHolder;
import cc.wujm.ashlar.config.PluginConfig;
import cc.wujm.ashlar.engine.BlockDataParser;
import cc.wujm.ashlar.engine.JournalCapture;
import cc.wujm.ashlar.engine.JournalRestoreTask;
import cc.wujm.ashlar.engine.Region;
import cc.wujm.ashlar.engine.TickBudgetExecutor;
import cc.wujm.ashlar.journal.JournalCells;
import cc.wujm.ashlar.journal.JournalRecorder;
import cc.wujm.ashlar.journal.JournalStore;
import cc.wujm.ashlar.journal.UndoRules;
import cc.wujm.ashlar.rpc.InvocationContext;
import cc.wujm.ashlar.tool.ToolArgError;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import org.bukkit.Bukkit;
import org.bukkit.World;
import org.bukkit.block.data.BlockData;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.function.Function;
import java.util.function.Supplier;

/**
 * Build journal for the tool layer (Step 12): opens a {@link JournalCapture} for one writing call,
 * commits it to the {@link JournalStore} afterwards, and serves undo, list and delete. Every store
 * access runs on the store's I/O thread; world access happens only in tasks on the main thread.
 */
public final class JournalService {

    /** What a commit produced: an entry, or a note why none was kept ({@code null} for nothing to say). */
    public record Commit(JournalStore.Entry entry, String note) {
        static final Commit NONE = new Commit(null, null);

        /** Adds {@code journal} / {@code journalNote} properties to a JSON tool result. */
        public String addTo(JsonElement result) {
            JsonObject o = result.getAsJsonObject();
            if (entry != null) {
                o.addProperty("journal", entry.id());
                o.addProperty("journalCells", entry.cells());
                if (entry.blockEntityCells() > 0) o.addProperty("journalBlockEntityCells", entry.blockEntityCells());
            }
            if (note != null) o.addProperty("journalNote", note);
            return o.toString();
        }

        /** Result-text lines for the calling tool; empty when journalling was off. */
        public List<String> lines() {
            List<String> out = new ArrayList<>();
            if (entry != null) {
                out.add("Journal: " + entry.id() + " (" + entry.cells() + " cells). Undo only this call with mc_restore {\"journal\":\""
                        + entry.id() + "\"}; cells changed later are kept.");
                if (entry.blockEntityCells() > 0) {
                    out.add("Journal note: " + entry.blockEntityCells() + " overwritten cells held block-entity data (sign text,"
                            + " inventories) that an undo cannot bring back.");
                }
            }
            if (note != null) out.add(note);
            return out;
        }
    }

    private final ConfigHolder config;
    private final TickBudgetExecutor executor;
    private final JournalStore store;

    public JournalService(ConfigHolder config, TickBudgetExecutor executor, JournalStore store) {
        this.config = config;
        this.executor = executor;
        this.store = store;
    }

    static JournalStore.Viewer viewer(InvocationContext ctx) {
        return new JournalStore.Viewer(VerificationService.owner(ctx), ctx.principal().kind() != InvocationContext.Kind.PLAYER);
    }

    /** A capture for one writing call, or {@code null} when journalling is off for it. */
    public JournalCapture open(boolean requested) {
        PluginConfig.JournalConfig c = config.get().journal();
        return requested && c.enabled() ? new JournalCapture(c.maxCellsPerEntry()) : null;
    }

    /**
     * Runs {@code body} with a journalled context and commits whatever it wrote, also when it
     * failed (a partial call stays undoable). {@code finish} turns the body's result plus the
     * commit into the tool's text.
     */
    public <T> CompletableFuture<String> journalled(InvocationContext ctx, boolean requested, String tool, String label,
            Function<InvocationContext, CompletableFuture<T>> body, java.util.function.BiFunction<T, Commit, String> finish) {
        JournalCapture capture = open(requested);
        InvocationContext jctx = capture == null ? ctx : ctx.withJournal(capture);
        CompletableFuture<T> run;
        try {
            run = body.apply(jctx);
        } catch (RuntimeException e) {
            run = CompletableFuture.failedFuture(e);
        }
        return run.handle((value, error) -> new Object[]{value, error})
                .thenComposeAsync(pair -> commit(ctx, capture, tool, label, null).thenApply(commit -> {
                    if (pair[1] != null) throw pair[1] instanceof CompletionException ce ? ce : new CompletionException((Throwable) pair[1]);
                    @SuppressWarnings("unchecked") T value = (T) pair[0];
                    return finish.apply(value, commit);
                }), store.io());
    }

    /** Stores the capture as an entry on the I/O thread. Never fails: problems become a note. */
    public CompletableFuture<Commit> commit(InvocationContext ctx, JournalCapture capture, String tool, String label, String undoOf) {
        if (capture == null) return CompletableFuture.completedFuture(Commit.NONE);
        return CompletableFuture.supplyAsync(() -> {
            JournalRecorder r = capture.recorder();
            if (r.overflowed()) {
                return new Commit(null, "Journal: not kept - this call changed more than " + r.maxCells()
                        + " cells (journal.max-cells-per-entry); use snapshots for calls this large.");
            }
            if (capture.mixedWorlds()) return new Commit(null, "Journal: not kept - this call wrote to more than one world.");
            JournalCells cells = r.toCells();
            if (cells.size() == 0 || capture.world() == null) return Commit.NONE;
            try {
                var e = store.put(VerificationService.owner(ctx), capture.world().getName(), tool, label, undoOf, r.blockEntityCells(), cells);
                String note = r.blockEntityWrites() > 0 ? "Journal note: " + r.blockEntityWrites()
                        + " sign text writes without a block change are not journalled." : null;
                return new Commit(e, note);
            } catch (IOException | RuntimeException ex) {
                return new Commit(null, "Journal: not kept - " + ex.getMessage());
            }
        }, store.io());
    }

    record UndoArgs(String id, UndoRules.Mode mode, boolean dryRun, boolean allowBlockEntityReplacement, int samples) {
    }

    /** Undoes one entry cell by cell; the undo itself is journalled unless it is a dry run. */
    public CompletableFuture<String> undo(InvocationContext ctx, UndoArgs a) {
        JournalStore.Viewer viewer = viewer(ctx);
        return CompletableFuture.supplyAsync(() -> {
            JournalStore.Entry e = io(() -> store.get(a.id(), viewer));
            World world = new cc.wujm.ashlar.engine.RequestValidator(config.get()).resolveWorld(worldParams(e.world()));
            io(() -> {
                store.acquire(e);
                return null;
            });
            try {
                JournalCells cells = io(() -> {
                    try {
                        return store.cells(e);
                    } catch (IOException ex) {
                        throw new UncheckedIOException(ex);
                    }
                });
                BlockData[] palette = new BlockData[cells.palette().size()];
                for (int i = 0; i < palette.length; i++) palette[i] = BlockDataParser.parse(cells.palette().get(i));
                return new Object[]{e, world, cells, palette};
            } catch (RuntimeException ex) {
                store.release(e);
                throw ex;
            }
        }, store.io()).thenCompose(prep -> {
            JournalStore.Entry e = (JournalStore.Entry) prep[0];
            World world = (World) prep[1];
            JournalCells cells = (JournalCells) prep[2];
            BlockData[] palette = (BlockData[]) prep[3];
            JournalCapture capture = a.dryRun() ? null : open(true);
            InvocationContext jctx = capture == null ? ctx : ctx.withJournal(capture);
            JournalRestoreTask task = new JournalRestoreTask(e.bounds(), world, cells, palette, a.mode(),
                    a.allowBlockEntityReplacement(), a.dryRun(), a.samples());
            CompletableFuture<JsonElement> run;
            try {
                run = executor.submit(task, jctx);
            } catch (RuntimeException ex) {
                run = CompletableFuture.failedFuture(ex);
            }
            return run.handle((r, err) -> new Object[]{r, err}).thenComposeAsync(pair ->
                    commit(ctx, capture, "mc_restore", "undo of " + e.id(), e.id()).thenApplyAsync(commit -> {
                        try {
                            if (commit.entry() != null) store.markUndone(e.id(), commit.entry().id());
                        } catch (IOException ex) {
                            // The undo happened; only the marker is missing.
                        } finally {
                            store.release(e);
                        }
                        if (pair[1] != null) {
                            Throwable t = (Throwable) pair[1];
                            throw t instanceof CompletionException ce ? ce : new CompletionException(t);
                        }
                        return undoText(e, a, ((JsonElement) pair[0]).getAsJsonObject(), commit);
                    }, store.io()), store.io());
        });
    }

    static String undoText(JournalStore.Entry e, UndoArgs a, JsonObject r, Commit commit) {
        List<String> out = new ArrayList<>();
        String verb = a.dryRun() ? "Dry run of undo" : "Undid";
        out.add(verb + " journal " + e.id() + " (" + e.tool() + (e.label() != null ? ", \"" + e.label() + "\"" : "") + ", mode "
                + a.mode().name().toLowerCase(java.util.Locale.ROOT) + "): " + r.get("restored").getAsLong() + "/"
                + r.get("cells").getAsLong() + " cells " + (a.dryRun() ? "would be restored" : "restored")
                + (r.get("overwritten").getAsLong() > 0 ? " (" + r.get("overwritten").getAsLong() + " overwrote later changes)" : "")
                + ", " + r.get("alreadyOriginal").getAsLong() + " already original.");
        long changed = r.get("conflictsChanged").getAsLong(), entity = r.get("conflictsBlockEntity").getAsLong();
        if (changed + entity > 0) {
            JsonArray b = r.getAsJsonArray("conflictBounds");
            out.add("Kept " + (changed + entity) + " cells: " + changed + " changed since the journalled call, " + entity
                    + " hold block entities (allowBlockEntityReplacement:true replaces them). Bounds [" + b.get(0) + "," + b.get(1)
                    + "," + b.get(2) + "]..[" + b.get(3) + "," + b.get(4) + "," + b.get(5) + "].");
            for (JsonElement se : r.getAsJsonArray("samples")) {
                JsonObject s = se.getAsJsonObject();
                JsonArray p = s.getAsJsonArray("pos");
                out.add("  [" + p.get(0) + "," + p.get(1) + "," + p.get(2) + "] " + s.get("kind").getAsString() + ": journalled "
                        + shortState(s.get("journalled").getAsString()) + ", now " + shortState(s.get("live").getAsString()));
            }
            if (a.mode() == UndoRules.Mode.SAFE && changed > 0 && !a.dryRun()) {
                out.add("mode \"force\" would also overwrite the changed cells.");
            }
        }
        if (e.undoneBy() != null) out.add("Note: this entry had already been undone by " + e.undoneBy() + ".");
        out.addAll(commit.lines());
        return String.join("\n", out);
    }

    private static String shortState(String s) {
        return s.startsWith("minecraft:") ? s.substring("minecraft:".length()) : s;
    }

    record ListArgs(Instant since, String label, String world, Region touches, int limit) {
    }

    public CompletableFuture<String> list(InvocationContext ctx, ListArgs a) {
        JournalStore.Viewer viewer = viewer(ctx);
        return CompletableFuture.supplyAsync(() -> {
            List<JournalStore.Entry> entries;
            try {
                entries = store.list(viewer, a.since(), a.label(), a.world(), a.touches(), a.limit());
            } catch (IOException ex) {
                throw new ToolArgError("journal list failed: " + ex.getMessage());
            }
            if (entries.isEmpty()) return "No journal entries" + (a.touches() != null || a.since() != null || a.label() != null ? " match." : ".");
            List<String> out = new ArrayList<>();
            out.add(entries.size() + " journal entr" + (entries.size() == 1 ? "y" : "ies") + ", newest first:");
            for (JournalStore.Entry e : entries) {
                Region b = e.bounds();
                out.add(e.id() + "  " + e.createdAt() + "  " + e.tool() + "  " + e.world() + " [" + b.minX() + "," + b.minY() + ","
                        + b.minZ() + "]..[" + b.maxX() + "," + b.maxY() + "," + b.maxZ() + "]  " + e.cells() + " cells"
                        + (e.label() != null ? "  \"" + e.label() + "\"" : "")
                        + (e.undoOf() != null ? "  undo of " + e.undoOf() : "")
                        + (e.undoneBy() != null ? "  undone by " + e.undoneBy() : "")
                        + (viewer.all() ? "  owner " + e.owner() : ""));
            }
            return String.join("\n", out);
        }, store.io());
    }

    public CompletableFuture<String> delete(InvocationContext ctx, String id) {
        JournalStore.Viewer viewer = viewer(ctx);
        return CompletableFuture.supplyAsync(() -> {
            io(() -> {
                store.delete(id, viewer);
                return null;
            });
            return "Deleted journal entry " + id + ".";
        }, store.io());
    }

    private static JsonObject worldParams(String world) {
        JsonObject o = new JsonObject();
        o.addProperty("world", world);
        return o;
    }

    /** Store lookups throw IllegalArgumentException for user-facing problems; surface them as tool errors. */
    private static <T> T io(Supplier<T> s) {
        try {
            return s.get();
        } catch (IllegalArgumentException e) {
            throw new ToolArgError(e.getMessage());
        } catch (UncheckedIOException e) {
            throw new ToolArgError("journal entry could not be read: " + e.getCause().getMessage());
        }
    }
}
