// SPDX-License-Identifier: AGPL-3.0-or-later
package cc.wujm.ashlar.journal;

import cc.wujm.ashlar.engine.Region;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.*;
import java.util.List;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.*;

class JournalStoreTest {
    @TempDir Path dir;
    private static final class MutableClock extends Clock {Instant now=Instant.parse("2026-10-10T12:00:00Z");@Override public ZoneId getZone(){return ZoneOffset.UTC;}@Override public Clock withZone(ZoneId zone){return this;}@Override public Instant instant(){return now;}}
    private final MutableClock clock = new MutableClock();
    private JournalStore.Limits limits = new JournalStore.Limits(3, 1000, 30);
    private static final JournalStore.Viewer ALL = new JournalStore.Viewer("WS_TOKEN:127.0.0.1", true);
    private static final JournalStore.Viewer ALICE = new JournalStore.Viewer("PLAYER:alice", false);

    private JournalStore store() {
        var s = new JournalStore(dir, () -> limits, clock, Logger.getLogger("test"));
        s.loadFromDisk();
        return s;
    }

    /** {@code n} changed cells in a row along x starting at {@code x0}. */
    private static JournalCells cells(int x0, int n) {
        long[] pos = new long[n];
        for (int i = 0; i < n; i++) pos[i] = JournalRecorder.pack(x0 + i, 64, 0);
        return new JournalCells(List.of("minecraft:air", "minecraft:stone"), pos, new int[n], filled(n));
    }

    private static int[] filled(int n) {
        int[] a = new int[n];
        java.util.Arrays.fill(a, 1);
        return a;
    }

    private JournalStore.Entry put(JournalStore s, String owner, String label, JournalCells c) throws IOException {
        clock.now = clock.now.plusSeconds(1);
        return s.put(owner, "world", "mc_build", label, null, 0, c);
    }

    @Test void visibilityAndDelete() throws IOException {
        var s = store();
        var mine = put(s, "PLAYER:alice", "a", cells(0, 2));
        var theirs = put(s, "PLAYER:bob", "b", cells(10, 2));
        assertSame(mine, s.get(mine.id(), ALICE));
        assertThrows(IllegalArgumentException.class, () -> s.get(theirs.id(), ALICE));
        assertEquals(2, s.list(ALL, null, null, null, null, 50).size());
        assertEquals(List.of(mine), s.list(ALICE, null, null, null, null, 50));
        assertThrows(IllegalArgumentException.class, () -> s.delete(theirs.id(), ALICE));
        s.delete(mine.id(), ALICE);
        assertFalse(Files.exists(dir.resolve(mine.id() + ".bin.gz")));
        assertEquals(1, s.size());
    }

    @Test void evictsOldestByCountCellsAndAgeButNotBusy() throws IOException {
        var s = store();
        var a = put(s, "o", null, cells(0, 10));
        var b = put(s, "o", null, cells(0, 10));
        var c = put(s, "o", null, cells(0, 10));
        s.acquire(a);
        var d = put(s, "o", null, cells(0, 10));
        assertEquals(List.of(a, c, d), s.all());
        s.release(a);
        assertThrows(IllegalArgumentException.class, () -> s.get(b.id(), ALL));
        put(s, "o", null, cells(0, 975));
        assertTrue(s.all().stream().mapToLong(JournalStore.Entry::cells).sum() <= 1000);
        clock.now = clock.now.plus(Duration.ofDays(31));
        put(s, "o", null, cells(0, 1));
        assertEquals(1, s.size());
    }

    @Test void listFiltersAreExact() throws IOException {
        var s = store();
        var early = put(s, "o", "Berg lookout", cells(0, 3));
        clock.now = clock.now.plusSeconds(10);
        Instant mid = clock.now;
        // Bounds [100..200] intersect the query box below, but no cell lies inside it.
        var sparse = s.put("o", "world", "mc_build", "deck", null, 0, new JournalCells(List.of("minecraft:air", "minecraft:stone"),
                new long[]{JournalRecorder.pack(100, 64, 0), JournalRecorder.pack(200, 64, 0)}, new int[]{0, 0}, new int[]{1, 1}));
        assertEquals(List.of(early), s.list(ALL, null, "lookout", null, null, 50));
        assertEquals(List.of(sparse), s.list(ALL, mid, null, null, null, 50));
        assertTrue(s.list(ALL, null, null, null, new Region(150, 0, -5, 160, 100, 5), 50).isEmpty());
        assertEquals(List.of(sparse), s.list(ALL, null, null, null, new Region(200, 64, 0, 200, 64, 0), 50));
        assertEquals(List.of(early), s.list(ALL, null, null, null, new Region(1, 64, 0, 1, 64, 0), 50));
        assertEquals(1, s.list(ALL, null, null, null, null, 1).size());
        assertTrue(s.list(ALL, null, null, "world_nether", null, 50).isEmpty());
    }

    @Test void reloadKeepsMetadataUndoLinksAndCells() throws IOException {
        var s = store();
        var e = put(s, "o", "x", cells(5, 4));
        s.markUndone(e.id(), "jrn-20261010-120000-abcd");
        var reloaded = store();
        var back = reloaded.get(e.id(), ALL);
        assertEquals("jrn-20261010-120000-abcd", back.undoneBy());
        assertEquals("x", back.label());
        assertEquals(new Region(5, 64, 0, 8, 64, 0), back.bounds());
        assertEquals(4, reloaded.cells(back).size());
    }

    @Test void busyEntryCannotBeAcquiredTwiceOrDeleted() throws IOException {
        var s = store();
        var e = put(s, "o", null, cells(0, 1));
        s.acquire(e);
        assertThrows(IllegalArgumentException.class, () -> s.acquire(e));
        assertThrows(IllegalArgumentException.class, () -> s.delete(e.id(), ALL));
        s.release(e);
        s.delete(e.id(), ALL);
    }
}
