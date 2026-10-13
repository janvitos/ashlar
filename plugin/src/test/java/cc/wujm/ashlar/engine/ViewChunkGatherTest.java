// SPDX-License-Identifier: AGPL-3.0-or-later
package cc.wujm.ashlar.engine;

import cc.wujm.ashlar.render.FirstPersonCamera;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ViewChunkGatherTest {

    /** Loaded columns, generated columns and the loads asked for, completed by the test. */
    static final class FakeSource implements ViewChunkGather.Source<String> {
        final Set<Long> loaded = new HashSet<>();
        final Set<Long> generated = new HashSet<>();
        final Map<Long, CompletableFuture<Boolean>> pending = new LinkedHashMap<>();
        final List<Long> requests = new ArrayList<>();

        @Override
        public boolean isLoaded(int cx, int cz) {
            return loaded.contains(FirstPersonCamera.pack(cx, cz));
        }

        @Override
        public String copy(int cx, int cz) {
            assertTrue(isLoaded(cx, cz), "copied a column that is not loaded");
            return cx + "," + cz;
        }

        @Override
        public CompletableFuture<Boolean> load(int cx, int cz) {
            long c = FirstPersonCamera.pack(cx, cz);
            requests.add(c);
            CompletableFuture<Boolean> f = new CompletableFuture<>();
            pending.put(c, f);
            return f;
        }

        /** Finishes every pending load: generated columns become loaded unless {@code unloadAgain}. */
        void finishAll(boolean unloadAgain) {
            for (Map.Entry<Long, CompletableFuture<Boolean>> e : new ArrayList<>(pending.entrySet())) {
                boolean exists = generated.contains(e.getKey());
                if (exists && !unloadAgain) loaded.add(e.getKey());
                e.getValue().complete(exists);
            }
            pending.clear();
        }
    }

    private static long c(int cx, int cz) {
        return FirstPersonCamera.pack(cx, cz);
    }

    private static List<Long> row(int n) {
        List<Long> out = new ArrayList<>();
        for (int i = 0; i < n; i++) out.add(c(i, 0));
        return out;
    }

    @Test
    void loadedColumnsAreCopiedWithoutLoads() {
        FakeSource src = new FakeSource();
        src.loaded.addAll(row(3));
        ViewChunkGather<String> g = new ViewChunkGather<>(src, row(3), 256);
        assertTrue(g.step(() -> true));
        assertEquals(3, g.copies().size());
        assertEquals(3, g.alreadyLoaded());
        assertTrue(src.requests.isEmpty());
    }

    @Test
    void unloadedColumnsLoadInBatchesAndAreCopiedNextStep() {
        FakeSource src = new FakeSource();
        src.generated.addAll(row(20));
        ViewChunkGather<String> g = new ViewChunkGather<>(src, row(20), 256);
        int steps = 0;
        while (!g.step(() -> true)) {
            assertTrue(src.pending.size() <= ViewChunkGather.MAX_IN_FLIGHT, "too many loads in flight");
            src.finishAll(false);
            steps++;
        }
        assertEquals(20, g.copies().size());
        assertEquals(20, g.loadedForView());
        assertEquals(20, src.requests.size());
        assertTrue(steps >= 3, "20 loads at 8 in flight need at least 3 rounds");
    }

    @Test
    void neverGeneratedColumnsStayUnknown() {
        FakeSource src = new FakeSource();
        src.generated.add(c(0, 0));
        ViewChunkGather<String> g = new ViewChunkGather<>(src, row(3), 256);
        while (!g.step(() -> true)) src.finishAll(false);
        assertEquals(1, g.copies().size());
        assertEquals(2, g.notGenerated());
    }

    @Test
    void capLimitsLoadsAndCountsTheRest() {
        FakeSource src = new FakeSource();
        src.generated.addAll(row(10));
        src.loaded.add(c(9, 0));
        ViewChunkGather<String> g = new ViewChunkGather<>(src, row(10), 4);
        while (!g.step(() -> true)) src.finishAll(false);
        assertEquals(4, src.requests.size());
        assertEquals(List.of(c(0, 0), c(1, 0), c(2, 0), c(3, 0)), src.requests, "first columns in order get the loads");
        assertEquals(5, g.overCap());
        assertEquals(5, g.copies().size());
    }

    @Test
    void zeroCapReadsLoadedOnly() {
        FakeSource src = new FakeSource();
        src.generated.addAll(row(3));
        src.loaded.add(c(1, 0));
        ViewChunkGather<String> g = new ViewChunkGather<>(src, row(3), 0);
        assertTrue(g.step(() -> true));
        assertTrue(src.requests.isEmpty());
        assertEquals(1, g.copies().size());
        assertEquals(2, g.overCap());
    }

    @Test
    void columnUnloadedBeforeCopyIsRequestedOnceMore() {
        FakeSource src = new FakeSource();
        src.generated.add(c(0, 0));
        ViewChunkGather<String> g = new ViewChunkGather<>(src, row(1), 256);
        assertFalse(g.step(() -> true));
        src.finishAll(true); // loaded, then unloaded before the next step
        assertFalse(g.step(() -> true));
        assertEquals(2, src.requests.size());
        src.finishAll(true); // again
        assertTrue(g.step(() -> true));
        assertEquals(1, g.failed());
        assertEquals(0, g.copies().size());
    }

    @Test
    void failedLoadCountsAsFailed() {
        FakeSource src = new FakeSource();
        ViewChunkGather<String> g = new ViewChunkGather<>(src, row(1), 256);
        assertFalse(g.step(() -> true));
        src.pending.values().forEach(f -> f.completeExceptionally(new IllegalStateException("io")));
        assertTrue(g.step(() -> true));
        assertEquals(1, g.failed());
    }

    @Test
    void loadsThatNeverFinishAreGivenUp() {
        FakeSource src = new FakeSource();
        src.generated.addAll(row(2));
        ViewChunkGather<String> g = new ViewChunkGather<>(src, row(2), 256);
        int steps = 0;
        while (!g.step(() -> true)) steps++;
        assertEquals(ViewChunkGather.MAX_WAIT_STEPS, steps);
        assertEquals(2, g.failed());
    }

    @Test
    void noTimeMeansNoWork() {
        FakeSource src = new FakeSource();
        src.loaded.addAll(row(3));
        ViewChunkGather<String> g = new ViewChunkGather<>(src, row(3), 256);
        assertFalse(g.step(() -> false));
        assertEquals(0, g.copies().size());
        assertTrue(g.step(() -> true));
        assertEquals(3, g.copies().size());
    }

    @Test
    void byDistanceSortsNearestFirst() {
        List<Long> chunks = List.of(c(5, 5), c(0, 0), c(-2, 0), c(1, 0));
        assertEquals(List.of(c(0, 0), c(1, 0), c(-2, 0), c(5, 5)), ViewChunkGather.byDistance(chunks, 8, 8));
    }
}
