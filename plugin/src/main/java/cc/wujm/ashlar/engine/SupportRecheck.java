// SPDX-License-Identifier: AGPL-3.0-or-later
package cc.wujm.ashlar.engine;

import cc.wujm.ashlar.config.ConfigHolder;
import cc.wujm.ashlar.rpc.InvocationContext;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import org.bukkit.World;

import java.util.List;
import java.util.concurrent.CompletableFuture;

/**
 * Re-runs the support rules on positions an earlier write phase already warned about. A multi-phase
 * build (fills, shapes, text, blocks, or several cells parts) checks support after each phase, so a
 * lantern hung under a ceiling that a later phase adds is flagged although the finished build holds
 * it. Rechecking only those positions at the end drops such stale warnings. Read-only.
 */
public final class SupportRecheck {

    private final ConfigHolder configHolder;
    private final TickBudgetExecutor executor;

    public SupportRecheck(ConfigHolder configHolder, TickBudgetExecutor executor) {
        this.configHolder = configHolder;
        this.executor = executor;
    }

    /** Positions still unsupported, in the {@code warnings} shape of {@code fill_batch}. Not from the main thread. */
    public CompletableFuture<JsonElement> recheck(String worldName, List<int[]> positions, InvocationContext ctx) {
        JsonObject params = new JsonObject();
        if (worldName != null) params.addProperty("world", worldName);
        World world = new RequestValidator(configHolder.get()).resolveWorld(params);
        return executor.submit(new Task(world, positions), ctx);
    }

    private static Region bounds(List<int[]> positions) {
        int[] min = positions.getFirst().clone();
        int[] max = positions.getFirst().clone();
        for (int[] p : positions) {
            for (int i = 0; i < 3; i++) {
                min[i] = Math.min(min[i], p[i]);
                max[i] = Math.max(max[i], p[i]);
            }
        }
        return new Region(min[0], min[1], min[2], max[0], max[1], max[2]);
    }

    private static final class Task extends BuildTask {

        private final World world;
        private final List<int[]> positions;
        private final SupportCheck check;

        Task(World world, List<int[]> positions) {
            super(bounds(positions));
            this.world = world;
            this.positions = positions;
            this.check = new SupportCheck(world, positions, List.of(), true);
        }

        @Override
        public World world() {
            return world;
        }

        @Override
        public long volume() {
            return positions.size();
        }

        @Override
        public boolean step(long deadlineNanos) {
            return check.step(deadlineNanos);
        }

        @Override
        public JsonElement buildResult(long queuedMs, long elapsedMs) {
            JsonObject r = new JsonObject();
            SupportWarnings.addTo(r, check, false);
            return r;
        }
    }
}
