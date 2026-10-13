// SPDX-License-Identifier: AGPL-3.0-or-later
package cc.wujm.ashlar.engine;

import cc.wujm.ashlar.render.FirstPersonCamera;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.function.BooleanSupplier;

/**
 * Main-thread state machine behind {@link ViewSnapshotTask}. It copies the chunk columns that are
 * already loaded and, up to {@code loadCap}, asks the server to load the others in the background:
 * never generating terrain, at most {@link #MAX_IN_FLIGHT} at a time, and without holding them
 * afterwards. A finished load is copied in the next step; a column that was unloaded again before
 * that is requested once more. Pure Java over a {@link Source}, so ordering, caps and retries are
 * unit-tested without a server.
 */
final class ViewChunkGather<T> {

    /** Main-thread access to the world's chunk columns. */
    interface Source<T> {
        boolean isLoaded(int cx, int cz);

        /** Copies a loaded column. */
        T copy(int cx, int cz);

        /** Background load without generation: true once loaded, false when the column was never generated. */
        CompletableFuture<Boolean> load(int cx, int cz);
    }

    /** Loads requested at once; each finished load costs one main-thread copy. */
    static final int MAX_IN_FLIGHT = 8;
    /** Load requests per column (the first, plus one if it was unloaded before it could be copied). */
    static final int MAX_ATTEMPTS = 2;
    /** Steps (one per tick while waiting) before loads still outstanding are given up, about 30 s. */
    static final int MAX_WAIT_STEPS = 600;

    private final Source<T> source;
    private final List<Long> order;
    private final int loadCap;
    private final Map<Long, T> copies = new HashMap<>();
    private final ArrayDeque<Long> queue = new ArrayDeque<>();
    private final Map<Long, CompletableFuture<Boolean>> inFlight = new LinkedHashMap<>();
    private final Map<Long, Integer> attempts = new HashMap<>();
    private int index, queued, waitSteps;
    private int alreadyLoaded, loadedForView, notGenerated, overCap, failed;

    /** {@code order}: packed columns ({@link FirstPersonCamera#pack}), nearest first; {@code loadCap} 0 loads nothing. */
    ViewChunkGather(Source<T> source, List<Long> order, int loadCap) {
        this.source = source;
        this.order = List.copyOf(order);
        this.loadCap = Math.max(0, loadCap);
    }

    /** Columns sorted by horizontal distance from (x, z) to the column centre, nearest first; ties keep their order. */
    static List<Long> byDistance(Collection<Long> chunks, double x, double z) {
        List<Long> out = new ArrayList<>(chunks);
        out.sort(Comparator.comparingDouble(c -> {
            double dx = FirstPersonCamera.chunkX(c) * 16 + 8 - x, dz = FirstPersonCamera.chunkZ(c) * 16 + 8 - z;
            return dx * dx + dz * dz;
        }));
        return out;
    }

    /** Does work while {@code hasTime} holds; true once every column is copied or given up. */
    boolean step(BooleanSupplier hasTime) {
        // Finished loads first: nothing holds those columns for long.
        for (Iterator<Map.Entry<Long, CompletableFuture<Boolean>>> it = inFlight.entrySet().iterator(); it.hasNext(); ) {
            if (!hasTime.getAsBoolean()) return false;
            Map.Entry<Long, CompletableFuture<Boolean>> e = it.next();
            if (!e.getValue().isDone()) continue;
            it.remove();
            long c = e.getKey();
            boolean exists;
            try {
                exists = Boolean.TRUE.equals(e.getValue().join());
            } catch (RuntimeException ex) {
                failed++;
                continue;
            }
            if (!exists) {
                notGenerated++;
            } else if (source.isLoaded(FirstPersonCamera.chunkX(c), FirstPersonCamera.chunkZ(c))) {
                copies.put(c, source.copy(FirstPersonCamera.chunkX(c), FirstPersonCamera.chunkZ(c)));
                loadedForView++;
            } else if (attempts.getOrDefault(c, 0) < MAX_ATTEMPTS) {
                queue.addFirst(c);
            } else {
                failed++;
            }
        }
        while (index < order.size()) {
            if (!hasTime.getAsBoolean()) return false;
            long c = order.get(index++);
            int cx = FirstPersonCamera.chunkX(c), cz = FirstPersonCamera.chunkZ(c);
            if (source.isLoaded(cx, cz)) {
                copies.put(c, source.copy(cx, cz));
                alreadyLoaded++;
            } else if (queued < loadCap) {
                queue.add(c);
                queued++;
            } else {
                overCap++;
            }
        }
        while (inFlight.size() < MAX_IN_FLIGHT && !queue.isEmpty()) {
            if (!hasTime.getAsBoolean()) return false;
            long c = queue.poll();
            int cx = FirstPersonCamera.chunkX(c), cz = FirstPersonCamera.chunkZ(c);
            if (source.isLoaded(cx, cz)) {
                // Loaded by someone else in the meantime.
                copies.put(c, source.copy(cx, cz));
                alreadyLoaded++;
                continue;
            }
            attempts.merge(c, 1, Integer::sum);
            inFlight.put(c, source.load(cx, cz));
        }
        if (inFlight.isEmpty() && queue.isEmpty()) return true;
        if (++waitSteps > MAX_WAIT_STEPS) {
            failed += inFlight.size() + queue.size();
            inFlight.clear();
            queue.clear();
            return true;
        }
        return false;
    }

    Map<Long, T> copies() {
        return copies;
    }

    int requested() {
        return order.size();
    }

    /** Columns that were loaded already (by players or anything else). */
    int alreadyLoaded() {
        return alreadyLoaded;
    }

    /** Columns this view loaded in the background. */
    int loadedForView() {
        return loadedForView;
    }

    /** Columns that were never generated; they stay unknown. */
    int notGenerated() {
        return notGenerated;
    }

    /** Unloaded columns beyond {@code loadCap}; they stay unknown. */
    int overCap() {
        return overCap;
    }

    /** Loads that failed, timed out or were unloaded again twice; they stay unknown. */
    int failed() {
        return failed;
    }
}
