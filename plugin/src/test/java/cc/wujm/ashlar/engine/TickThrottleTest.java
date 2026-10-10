// SPDX-License-Identifier: AGPL-3.0-or-later
package cc.wujm.ashlar.engine;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class TickThrottleTest {

    private static final long MS = 1_000_000L;

    @Test
    void fullBudgetWhileTheServerIsQuiet() {
        TickThrottle t = new TickThrottle(5);
        assertEquals(5 * MS, t.budgetNanos(2.0));
        assertEquals(5 * MS, t.budgetNanos(30.0));
        assertEquals(5.0, t.lastBudgetMs());
    }

    @Test
    void budgetShrinksLinearlyBetweenThirtyAndFortyFive() {
        assertEquals(20 * MS, TickThrottle.scaled(20 * MS, 30.0));
        assertEquals(10 * MS, TickThrottle.scaled(20 * MS, 37.5));
        assertEquals(MS, TickThrottle.scaled(20 * MS, 44.9));
        assertEquals(0, TickThrottle.scaled(20 * MS, 45.0));
    }

    @Test
    void pausedExceptForOneSliceASecond() {
        TickThrottle t = new TickThrottle(5);
        int slices = 0;
        for (int i = 0; i < 100; i++) {
            long b = t.budgetNanos(60.0);
            if (b > 0) {
                assertEquals(MS, b);
                slices++;
            }
        }
        assertEquals(5, slices);
    }

    @Test
    void ashlarsOwnWorkIsNotCountedAsServerLoad() {
        TickThrottle t = new TickThrottle(20);
        for (int i = 0; i < TickThrottle.WINDOW; i++) t.record(20 * MS);
        // Server average 35 ms, of which Ashlar spent 20: the rest of the server needs only 15.
        assertEquals(20 * MS, t.budgetNanos(35.0));
        assertEquals(15.0, t.lastOtherMs(), 1e-9);
        // Once Ashlar idles for a full window, the same 35 ms is all other load.
        for (int i = 0; i < TickThrottle.WINDOW; i++) t.record(0);
        assertEquals(TickThrottle.scaled(20 * MS, 35.0), t.budgetNanos(35.0));
    }

    @Test
    void aTinyCeilingIsNeverExceeded() {
        TickThrottle t = new TickThrottle(1);
        assertEquals(MS, t.budgetNanos(40.0));
        for (int i = 0; i < TickThrottle.PAUSED_SLICE_EVERY_TICKS; i++) assertTrue(t.budgetNanos(50.0) <= MS);
    }
}
