// SPDX-License-Identifier: AGPL-3.0-or-later
package cc.wujm.ashlar.tool.mc;

import cc.wujm.ashlar.config.ConfigHolder;
import cc.wujm.ashlar.config.PluginConfig;
import cc.wujm.ashlar.engine.AnomalyHit;
import cc.wujm.ashlar.engine.CompositionScan;
import cc.wujm.ashlar.engine.DiffScanTask;
import cc.wujm.ashlar.engine.Region;
import cc.wujm.ashlar.engine.RequestValidator;
import cc.wujm.ashlar.engine.TickBudgetExecutor;
import cc.wujm.ashlar.rpc.InvocationContext;
import cc.wujm.ashlar.rpc.MainThread;
import com.google.gson.JsonObject;
import org.bukkit.World;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;

/**
 * The advice section of the {@code mc_build} reply: reads the finished build's bounding box (plus
 * {@link CompositionScan#MARGIN}) under the tick budget and runs the composition rules on it. One line
 * per rule with a count and one example, so the reply grows by at most a dozen lines. Read-only; a
 * failure here never fails the build.
 */
public final class CompositionCheck {

    /**
     * Largest build box checked automatically; larger builds name mc_diff instead. The read runs under
     * the tick budget at roughly 170,000 cells a second, so this adds at most about 2.5 s to a reply.
     */
    static final long MAX_VOLUME = 250_000;

    private final TickBudgetExecutor executor;
    private final ConfigHolder config;

    public CompositionCheck(TickBudgetExecutor executor, ConfigHolder config) {
        this.executor = executor;
        this.config = config;
    }

    /** Report lines for {@code box} ({min, max} corners), empty when nothing is found. Not from the main thread. */
    CompletableFuture<List<String>> lines(InvocationContext ctx, String worldName, int[][] box, Set<String> skip) {
        if (box[0][0] > box[1][0]) return CompletableFuture.completedFuture(List.of());
        try {
            PluginConfig settings = config.get();
            JsonObject params = new JsonObject();
            if (worldName != null) params.addProperty("world", worldName);
            World world = new RequestValidator(settings).resolveWorld(params);
            return MainThread.call(() -> new int[] {world.getMinHeight(), world.getMaxHeight()}).thenComposeAsync(h -> {
                Region r = new Region(box[0][0], Math.max(box[0][1], h[0]), box[0][2], box[1][0], Math.min(box[1][1], h[1] - 1), box[1][2]);
                if (r.minY() > r.maxY()) return CompletableFuture.completedFuture(List.<String>of());
                if (r.volume() > MAX_VOLUME)
                    return CompletableFuture.completedFuture(List.of("Composition: not checked; the build box has " + r.volume()
                            + " cells (over " + MAX_VOLUME + "). Check parts with mc_diff anomalies [\"composition\"]."));
                List<DiffPlan.Part> parts = DiffPlan.split(r, settings.limits().maxReadVolume(), CompositionScan.MARGIN, h[0], h[1] - 1);
                CompositionScan scan = new CompositionScan(skip, 1);
                return scan(ctx, world, parts, 0, scan).thenApply(CompositionCheck::format);
            }).exceptionally(t -> List.of("Composition: not checked (" + rootMessage(t) + ")."));
        } catch (RuntimeException e) {
            return CompletableFuture.completedFuture(List.of("Composition: not checked (" + rootMessage(e) + ")."));
        }
    }

    private CompletableFuture<CompositionScan> scan(InvocationContext ctx, World world, List<DiffPlan.Part> parts, int i, CompositionScan scan) {
        if (i == parts.size()) return CompletableFuture.completedFuture(scan);
        DiffPlan.Part p = parts.get(i);
        DiffScanTask task = new DiffScanTask(p.outer(), p.inner(), world, Set.of(), s -> false, 1);
        return executor.submit(task, ctx).thenComposeAsync(r -> {
            scan.scan(p.outer(), p.inner(), task.palette(), task.grid());
            return scan(ctx, world, parts, i + 1, scan);
        });
    }

    /** One header plus one line per rule: count, the first position and the reason. */
    static List<String> format(CompositionScan scan) {
        if (scan.isEmpty()) return List.of();
        List<String> out = new ArrayList<>();
        out.add("Composition advice (fix each, or say why it is deliberate):");
        Map<String, Long> counts = scan.counts();
        for (AnomalyHit h : scan.hits()) {
            long n = counts.get(h.kind());
            out.add("  " + h.kind() + (n > 1 ? " x" + n + ", e.g." : "") + " " + h.x() + "," + h.y() + "," + h.z() + ": " + h.reason());
        }
        return out;
    }

    /** Union of the bounding boxes of several {@code {min, max}} pairs. */
    static int[][] union(List<int[][]> boxes) {
        int[] min = {Integer.MAX_VALUE, Integer.MAX_VALUE, Integer.MAX_VALUE};
        int[] max = {Integer.MIN_VALUE, Integer.MIN_VALUE, Integer.MIN_VALUE};
        for (int[][] b : boxes) {
            for (int k = 0; k < 3; k++) {
                min[k] = Math.min(min[k], b[0][k]);
                max[k] = Math.max(max[k], b[1][k]);
            }
        }
        return new int[][] {min, max};
    }

    private static String rootMessage(Throwable t) {
        while (t.getCause() != null && t.getCause() != t) t = t.getCause();
        return t.getMessage() == null ? t.getClass().getSimpleName() : t.getMessage();
    }
}
