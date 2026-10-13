// SPDX-License-Identifier: AGPL-3.0-or-later
package cc.wujm.ashlar.tool.mc;

import cc.wujm.ashlar.config.ConfigHolder;
import cc.wujm.ashlar.config.PluginConfig;
import cc.wujm.ashlar.engine.BlockDataParser;
import cc.wujm.ashlar.engine.BuildExpectation;
import cc.wujm.ashlar.engine.BukkitStateDefaults;
import cc.wujm.ashlar.engine.CompositionScan;
import cc.wujm.ashlar.engine.DiffScanTask;
import cc.wujm.ashlar.engine.Region;
import cc.wujm.ashlar.engine.RepairTask;
import cc.wujm.ashlar.engine.RequestValidator;
import cc.wujm.ashlar.engine.TickBudgetExecutor;
import cc.wujm.ashlar.rpc.InvocationContext;
import cc.wujm.ashlar.rpc.MainThread;
import cc.wujm.ashlar.rpc.RpcHandler;
import cc.wujm.ashlar.snapshot.Snapshot;
import cc.wujm.ashlar.snapshot.SnapshotStore;
import cc.wujm.ashlar.tool.ToolArgError;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import org.bukkit.World;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.function.UnaryOperator;

/** {@code mc_diff} orchestration and the {@code mc_repair diffId} path. Continuations run off the main thread. */
public final class DiffService {

    private static final long MAX_EXPECTED_FILE_BYTES = 64L * 1024 * 1024;
    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("HH:mm").withZone(ZoneOffset.UTC);

    private final McBuild build;
    private final BuildPreflight preflight;
    private final TickBudgetExecutor executor;
    private final ConfigHolder config;
    private final DiffStore store;
    private final SnapshotStore snapshots;
    private final RpcHandler snapshot;
    private final Path expectedDir;

    public DiffService(McBuild build, BuildPreflight preflight, TickBudgetExecutor executor, ConfigHolder config,
            DiffStore store, SnapshotStore snapshots, RpcHandler snapshot, Path expectedDir) {
        this.build = build;
        this.preflight = preflight;
        this.executor = executor;
        this.config = config;
        this.store = store;
        this.snapshots = snapshots;
        this.snapshot = snapshot;
        this.expectedDir = expectedDir;
    }

    private static String canon(String raw) {
        return BukkitStateDefaults.CANON.canon(raw);
    }

    /** Validates a block state through the server registry and returns its full {@code getAsString()} form. */
    private static String fullState(String raw) {
        return BlockDataParser.parse(raw).getAsString();
    }

    // ------------------------------------------------------------------ diff

    public CompletableFuture<String> diff(InvocationContext ctx, McDiff.Args a) {
        PluginConfig settings = config.get();
        RequestValidator validator = new RequestValidator(settings);
        JsonObject params = new JsonObject();
        if (a.world() != null) params.addProperty("world", a.world());
        params.add("from", JsonUtil.intArray(a.from()));
        params.add("to", JsonUtil.intArray(a.to()));
        World world = validator.resolveWorld(params);
        DiffIgnore ignore = DiffIgnore.parse(a.ignore());
        return MainThread.call(() -> new int[]{world.getMinHeight(), world.getMaxHeight()}).thenComposeAsync(h -> {
            Region box = validator.validateReadRegion(params, h[0], h[1], settings.limits().maxDiffVolume());
            Set<String> scanAnomalies = new HashSet<>(a.anomalies());
            scanAnomalies.remove("enclosedAir");
            CompositionScan composition = scanAnomalies.remove("composition") ? new CompositionScan(Set.of(), a.samples()) : null;
            int margin = composition != null ? CompositionScan.MARGIN : scanAnomalies.isEmpty() ? 0 : 1;
            List<DiffPlan.Part> parts = DiffPlan.split(box, settings.limits().maxReadVolume(), margin, h[0], h[1] - 1);
            DiffReference baseline = a.baselineSnapshot() == null ? null : snapshotReference(a.baselineSnapshot(), world, box, "baselineSnapshot");
            return reference(ctx, a, world, box).thenComposeAsync(ref -> {
                var acc = new DiffAccumulator(ref, baseline, new DiffAccumulator.Settings(a.declaredOnly(), a.materialOnly(), a.samples(),
                        DiffStore.MAX_CELLS_PER_RECEIPT, a.anomalies().contains("enclosedAir")), DiffService::canon, ignore);
                return scan(ctx, world, parts, 0, acc, scanAnomalies, ignore, a.samples(), composition)
                        .thenApplyAsync(done -> {
                            if (composition != null) done.addAnomalies(composition.counts(), composition.hits());
                            return report(ctx, a, world, box, parts.size(), done);
                        });
            });
        });
    }

    private CompletableFuture<DiffAccumulator> scan(InvocationContext ctx, World world, List<DiffPlan.Part> parts, int i,
            DiffAccumulator acc, Set<String> anomalies, DiffIgnore ignore, int samples, CompositionScan composition) {
        if (i == parts.size()) return CompletableFuture.completedFuture(acc);
        if (ctx.isCancelled()) return CompletableFuture.failedFuture(new ToolArgError("mc_diff was cancelled"));
        DiffPlan.Part p = parts.get(i);
        DiffScanTask task = new DiffScanTask(p.outer(), p.inner(), world, anomalies, ignore, samples);
        return executor.submit(task, ctx).thenComposeAsync(r -> {
            acc.feed(p.inner(), p.outer(), task.palette(), task.grid());
            acc.addAnomalies(task.anomalyCounts(), task.anomalyHits());
            if (composition != null) composition.scan(p.outer(), p.inner(), task.palette(), task.grid());
            return scan(ctx, world, parts, i + 1, acc, anomalies, ignore, samples, composition);
        });
    }

    private String report(InvocationContext ctx, McDiff.Args a, World world, Region box, int reads, DiffAccumulator acc) {
        acc.finishEnclosedAir();
        List<String> out = new ArrayList<>();
        out.add("mc_diff " + world.getName() + " [" + box.minX() + "," + box.minY() + "," + box.minZ() + "].."
                + "[" + box.maxX() + "," + box.maxY() + "," + box.maxZ() + "] (" + box.volume() + " cells, " + reads + " live read"
                + (reads == 1 ? "" : "s") + ") " + (a.against() == null ? "anomaly checks only."
                : "against " + a.against().describe() + (a.baselineSnapshot() != null ? " + baseline " + a.baselineSnapshot() : "")
                + "; scope " + (a.declaredOnly() ? "declared" : "box") + ", compare " + (a.materialOnly() ? "material" : "exact") + "."));
        if (a.against() == null) {
            List<String> body = acc.bodyLines(a.format());
            out.addAll(body.isEmpty() ? List.of("No anomalies found.") : body);
            return String.join("\n", out);
        }
        out.add(acc.summaryLine());
        out.add(acc.detailLine());
        out.addAll(acc.bodyLines(a.format()));
        long differing = acc.differing();
        if (differing == 0) {
            out.add("No differences; no diffId issued.");
        } else {
            var r = store.put(VerificationService.owner(ctx), world.getName(), acc.receipt, differing);
            out.add("diffId: " + r.id + " (" + r.cells.size() + (r.cells.size() < differing ? " of " + differing : "")
                    + " differing cells stored; expires " + TIME.format(r.expires) + " UTC). mc_repair {diffId} writes the reference states"
                    + " back to these cells; review them first.");
        }
        return String.join("\n", out);
    }

    // ------------------------------------------------------------------ references

    private DiffReference snapshotReference(String id, World world, Region box, String what) {
        Snapshot s = snapshots.get(id).orElseThrow(() -> new ToolArgError(what + ": unknown snapshot id '" + id + "'"));
        if (!s.world().equals(world.getName()))
            throw new ToolArgError(what + " '" + id + "' is in world '" + s.world() + "', not '" + world.getName() + "'");
        return DiffReference.fromRegionData(box, s.data(), what + " '" + id + "'");
    }

    private CompletableFuture<DiffReference> reference(InvocationContext ctx, McDiff.Args a, World world, Region box) {
        McDiff.Against against = a.against();
        if (against == null) return CompletableFuture.completedFuture(new DiffReference(box, DiffService::fullState));
        if (against.snapshot() != null) return CompletableFuture.completedFuture(snapshotReference(against.snapshot(), world, box, "snapshot"));
        if (against.expected() != null) return CompletableFuture.completedFuture(fromCells(box, against.expected()));
        if (against.expectedFile() != null) return CompletableFuture.completedFuture(fromCells(box, readExpectedFile(against.expectedFile())));
        JsonObject request = new JsonObject();
        request.addProperty("world", world.getName());
        request.add("blueprint", against.blueprint().deepCopy());
        if (against.transform() != null) request.add("transform", against.transform().deepCopy());
        return build.prepare(request, false).thenCompose(preflight::validate)
                .thenCompose(v -> preflight.analyze(ctx, v, null, true))
                .thenApplyAsync(result -> {
                    var e = result.task().expectation(world.getName(), config.get().engine().connectBlocks(), false);
                    DiffReference ref = new DiffReference(box, DiffService::fullState);
                    for (BuildExpectation.Cell c : e.cells()) ref.add(c.pos().x(), c.pos().y(), c.pos().z(), c.state());
                    return ref;
                });
    }

    private DiffReference fromCells(Region box, List<McDiff.Cell> cells) {
        UnaryOperator<String> normalize = DiffService::fullState;
        DiffReference ref = new DiffReference(box, normalize);
        for (McDiff.Cell c : cells) ref.add(c.x(), c.y(), c.z(), c.state());
        return ref;
    }

    private CellFiles cellFiles;

    /** Lets {@code expectedFile} also name finished staged uploads and exports (Step 16). */
    public DiffService withCells(CellFiles files) {
        this.cellFiles = files;
        return this;
    }

    /**
     * Reads {@code plugins/Ashlar/expected/<name>.json} or {@code .json.gz}, else a finished staged
     * upload or export of that name: {@code [[x,y,z,"state"],...]}, {@code {"cells":[...]}} or a
     * palette-rle export. Streams the file, so no JSON tree of the whole file is built.
     */
    private List<McDiff.Cell> readExpectedFile(String name) {
        Path plain = expectedDir.resolve(name + ".json"), gz = expectedDir.resolve(name + ".json.gz");
        Path file = Files.isRegularFile(gz, LinkOption.NOFOLLOW_LINKS) ? gz : plain;
        if (!Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS)) file = cellFiles == null ? null : cellFiles.find(name);
        if (file == null)
            throw new ToolArgError("expected file '" + name + "' not found as plugins/Ashlar/expected/" + name + ".json(.gz), a finished staged upload or an export (symlinks are not followed)");
        try {
            if (Files.size(file) > MAX_EXPECTED_FILE_BYTES && file.startsWith(expectedDir)) throw new ToolArgError("expected file '" + name + "' is larger than 64 MiB");
        } catch (IOException e) {
            throw new ToolArgError("expected file '" + name + "' could not be read: " + e.getClass().getSimpleName());
        }
        long cap = config.get().limits().maxDiffVolume();
        List<McDiff.Cell> cells = new ArrayList<>();
        CellFiles.read(file, "expected file '" + name + "'", (x, y, z, state) -> {
            if (cells.size() >= cap) throw new ToolArgError("expected file '" + name + "' has more than limits.max-diff-volume " + cap + " cells");
            if (state.isBlank()) throw new ToolArgError("expected file '" + name + "' has a blank block state");
            cells.add(new McDiff.Cell(x, y, z, state));
        });
        return cells;
    }

    // ------------------------------------------------------------------ repair

    static List<BuildExpectation.Observed> select(DiffStore.Receipt r, Set<BuildExpectation.Pos> positions, int max) {
        List<BuildExpectation.Observed> selected = r.cells.stream()
                .filter(o -> positions == null || positions.contains(o.expected().pos())).toList();
        if (positions != null && selected.size() != positions.size())
            throw new ToolArgError("positions must be differing cells stored in this diff receipt");
        if (selected.size() > max)
            throw new ToolArgError("repair selection of " + selected.size() + " cells exceeds maxChanges; select explicit positions or raise maxChanges (at most 10000)");
        return selected;
    }

    public CompletableFuture<JsonObject> repair(InvocationContext ctx, String diffId, Set<BuildExpectation.Pos> positions, int max,
            boolean backup, boolean allowEntities) {
        return repair(ctx, diffId, positions, max, backup, allowEntities, null);
    }

    /** {@code gate} (nullable) applies protected regions to the selected cells before any snapshot or write. */
    public CompletableFuture<JsonObject> repair(InvocationContext ctx, String diffId, Set<BuildExpectation.Pos> positions, int max,
            boolean backup, boolean allowEntities, ProtectionGuard.Gate gate) {
        DiffStore.Receipt r = store.get(diffId, VerificationService.owner(ctx));
        store.acquire(r);
        try {
            List<BuildExpectation.Observed> selected = select(r, positions, max);
            List<String> protection = gate == null ? List.of() : gate.enforce(r.world, ProtectionGuard.cells(selected));
            List<McBuild.SparseOpArg> blocks = new ArrayList<>();
            for (var o : selected) {
                var p = o.expected().pos();
                blocks.add(new McBuild.SparseOpArg(new int[]{p.x(), p.y(), p.z()}, o.expected().state(), null));
            }
            return preflight.validate(new McBuild.Args(r.world, List.of(), blocks, List.of(), backup, false, "static")).thenComposeAsync(v -> {
                RepairTask guard = new RepairTask(v.bounds(), v.world(), selected, v.blocks(), true, allowEntities, true);
                return executor.submit(guard, ctx).thenComposeAsync(g -> {
                    if (!g.getAsJsonObject().get("guardPassed").getAsBoolean())
                        throw new ToolArgError("repair guard rejected before snapshot/write: " + g);
                    CompletableFuture<JsonObject> snap;
                    if (backup) {
                        JsonObject s = new JsonObject();
                        s.addProperty("world", r.world);
                        s.add("from", JsonUtil.intArray(v.bounds().minX(), v.bounds().minY(), v.bounds().minZ()));
                        s.add("to", JsonUtil.intArray(v.bounds().maxX(), v.bounds().maxY(), v.bounds().maxZ()));
                        s.addProperty("label", "mc_repair diff snapshot (block states only)");
                        snap = snapshot.handle(ctx, s).thenApply(JsonElement::getAsJsonObject);
                    } else {
                        snap = CompletableFuture.completedFuture(null);
                    }
                    return snap.thenComposeAsync(s -> executor.submit(new RepairTask(v.bounds(), v.world(), selected, v.blocks(), false, allowEntities, true), ctx)
                            .thenApply(repaired -> {
                                JsonObject out = new JsonObject();
                                out.addProperty("diffId", r.id);
                                out.add("repair", repaired);
                                if (s != null) out.add("snapshot", s);
                                if (!protection.isEmpty()) out.add("protection", JsonUtil.stringArray(protection));
                                if (selected.size() == r.cells.size()) store.consume(r);
                                out.addProperty("note", "Run mc_diff again to confirm. Repaired cells are stale in this receipt"
                                        + (selected.size() == r.cells.size() ? "; the receipt was retired." : "; other stored cells stay repairable."));
                                out.addProperty("snapshotLimitations", "Snapshots store block states only, not sign text/colors, inventory or arbitrary NBT. No atomic world lock; late edits are skipped.");
                                return out;
                            }));
                });
            }).whenComplete((x, t) -> store.release(r));
        } catch (RuntimeException e) {
            store.release(r);
            throw e;
        }
    }
}
