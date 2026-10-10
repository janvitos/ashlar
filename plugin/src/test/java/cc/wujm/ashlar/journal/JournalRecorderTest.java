// SPDX-License-Identifier: AGPL-3.0-or-later
package cc.wujm.ashlar.journal;

import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class JournalRecorderTest {
    @Test void packRoundTripsWorldExtremes() {
        int[][] cases = {{0, 0, 0}, {-1, -1, -1}, {29_999_999, 2031, -29_999_999}, {-30_000_000, -2032, 30_000_000}, {576, 100, -40}};
        for (int[] c : cases) {
            long k = JournalRecorder.pack(c[0], c[1], c[2]);
            assertArrayEquals(c, new int[]{JournalRecorder.x(k), JournalRecorder.y(k), JournalRecorder.z(k)});
        }
    }

    @Test void firstTouchKeepsOriginalAndRetouchIsPendingAgain() {
        var r = new JournalRecorder(100);
        int stone = r.paletteIndex("minecraft:stone"), air = r.paletteIndex("minecraft:air"), dirt = r.paletteIndex("minecraft:dirt");
        long a = JournalRecorder.pack(1, 2, 3), b = JournalRecorder.pack(4, 5, 6);
        assertTrue(r.touch(a, air));
        assertTrue(r.touch(b, stone));
        assertFalse(r.touch(a, stone));
        assertEquals(2, r.pendingSize());
        r.setNew(r.pendingEntry(0), stone);
        r.setNew(r.pendingEntry(1), stone);
        r.clearPending();
        assertFalse(r.touch(a, dirt));
        assertEquals(1, r.pendingSize());
        r.setNew(r.pendingEntry(0), dirt);
        JournalCells cells = r.toCells();
        assertEquals(1, cells.size());
        assertEquals(a, cells.pos()[0]);
        assertEquals("minecraft:air", cells.palette().get(cells.oldState()[0]));
        assertEquals("minecraft:dirt", cells.palette().get(cells.newState()[0]));
        assertEquals(2, cells.palette().size());
    }

    @Test void manyCellsSurviveRehash() {
        var r = new JournalRecorder(1_000_000);
        int s = r.paletteIndex("minecraft:stone"), d = r.paletteIndex("minecraft:dirt");
        for (int x = 0; x < 100; x++) for (int z = 0; z < 100; z++) assertTrue(r.touch(JournalRecorder.pack(x, 64, z), s));
        for (int x = 0; x < 100; x++) assertFalse(r.touch(JournalRecorder.pack(x, 64, 0), s));
        for (int i = 0; i < r.pendingSize(); i++) r.setNew(r.pendingEntry(i), d);
        assertEquals(10_000, r.toCells().size());
    }

    @Test void overflowStopsRecording() {
        var r = new JournalRecorder(2);
        int s = r.paletteIndex("minecraft:stone");
        r.touch(JournalRecorder.pack(0, 0, 0), s);
        r.touch(JournalRecorder.pack(1, 0, 0), s);
        assertFalse(r.overflowed());
        r.touch(JournalRecorder.pack(2, 0, 0), s);
        assertTrue(r.overflowed());
        assertEquals(0, r.size());
        assertFalse(r.touch(JournalRecorder.pack(3, 0, 0), s));
    }

    @Test void codecRoundTripAndRejectsGarbage() throws IOException {
        var cells = new JournalCells(List.of("minecraft:air", "minecraft:oak_fence[east=true]"),
                new long[]{JournalRecorder.pack(-5, 70, 9), JournalRecorder.pack(6, -64, 1)}, new int[]{0, 1}, new int[]{1, 0});
        var out = new ByteArrayOutputStream();
        JournalCodec.write(cells, out);
        JournalCells back = JournalCodec.read(new ByteArrayInputStream(out.toByteArray()));
        assertEquals(cells.palette(), back.palette());
        assertArrayEquals(cells.pos(), back.pos());
        assertArrayEquals(cells.oldState(), back.oldState());
        assertArrayEquals(cells.newState(), back.newState());
        assertEquals(new cc.wujm.ashlar.engine.Region(-5, -64, 1, 6, 70, 9), back.bounds());
        assertThrows(IOException.class, () -> JournalCodec.read(new ByteArrayInputStream(new byte[]{1, 2, 3})));
    }

    @Test void undoRules() {
        var safe = UndoRules.Mode.SAFE;
        var force = UndoRules.Mode.FORCE;
        assertEquals(UndoRules.Decision.ALREADY_ORIGINAL, UndoRules.decide(true, false, false, force, false));
        assertEquals(UndoRules.Decision.RESTORE, UndoRules.decide(false, true, false, safe, false));
        assertEquals(UndoRules.Decision.CONFLICT_CHANGED, UndoRules.decide(false, false, false, safe, false));
        assertEquals(UndoRules.Decision.RESTORE_OVERWRITE, UndoRules.decide(false, false, false, force, false));
        assertEquals(UndoRules.Decision.CONFLICT_BLOCK_ENTITY, UndoRules.decide(false, true, true, safe, false));
        assertEquals(UndoRules.Decision.RESTORE, UndoRules.decide(false, true, true, safe, true));
        assertEquals(UndoRules.Decision.CONFLICT_BLOCK_ENTITY, UndoRules.decide(false, false, true, force, false));
    }
}
