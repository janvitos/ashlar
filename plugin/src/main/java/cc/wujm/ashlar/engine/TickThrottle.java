// SPDX-License-Identifier: AGPL-3.0-or-later
package cc.wujm.ashlar.engine;

/**
 * Load-aware per-tick budget for {@link TickBudgetExecutor}. The server's own tick time,
 * minus what Ashlar itself spent, is the load from everything else; Ashlar gets its full
 * {@code limits.tick-budget-ms} while that load is low, a linearly shrinking share as it
 * climbs, and pauses near the 50 ms tick, except for one short slice per second so queued
 * work still finishes eventually. Pure arithmetic; the caller feeds it measurements.
 */
public final class TickThrottle {

    /** Other load (ms per tick) up to which Ashlar gets its full budget. */
    static final double FULL_BELOW_MS = 30.0;
    /** Other load (ms per tick) from which Ashlar pauses. */
    static final double PAUSE_FROM_MS = 45.0;
    /** Smallest budget handed out while scaling down, and the slice run once per second while paused. */
    static final long MIN_BUDGET_NANOS = 1_000_000L;
    static final int PAUSED_SLICE_EVERY_TICKS = 20;
    /** Matches the window of Paper's {@code Server#getAverageTickTime()} (the last 100 ticks). */
    static final int WINDOW = 100;

    private final long maxBudgetNanos;
    private final long[] own = new long[WINDOW];
    private long ownSum;
    private int index;
    private int pausedTicks;
    private long lastBudgetNanos;
    private double lastOtherMs;

    public TickThrottle(long maxBudgetMs) {
        this.maxBudgetNanos = maxBudgetMs * 1_000_000L;
        this.lastBudgetNanos = maxBudgetNanos;
    }

    /** Budget for one tick, given the server's average full tick time in ms (Ashlar's own work included). */
    public long budgetNanos(double serverAverageTickMs) {
        double otherMs = Math.max(0.0, serverAverageTickMs - ownSum / (double) WINDOW / 1_000_000.0);
        lastOtherMs = otherMs;
        long budget = scaled(maxBudgetNanos, otherMs);
        if (budget == 0) {
            budget = ++pausedTicks >= PAUSED_SLICE_EVERY_TICKS ? Math.min(MIN_BUDGET_NANOS, maxBudgetNanos) : 0;
            if (budget > 0) pausedTicks = 0;
        } else {
            pausedTicks = 0;
        }
        lastBudgetNanos = budget;
        return budget;
    }

    /** Records how long Ashlar actually worked this tick (0 when idle), so it is not mistaken for other load. */
    public void record(long usedNanos) {
        ownSum += usedNanos - own[index];
        own[index] = usedNanos;
        index = (index + 1) % WINDOW;
    }

    static long scaled(long maxBudgetNanos, double otherMs) {
        if (otherMs <= FULL_BELOW_MS) return maxBudgetNanos;
        if (otherMs >= PAUSE_FROM_MS) return 0;
        double share = (PAUSE_FROM_MS - otherMs) / (PAUSE_FROM_MS - FULL_BELOW_MS);
        return Math.max(Math.min(MIN_BUDGET_NANOS, maxBudgetNanos), (long) (maxBudgetNanos * share));
    }

    /** The budget handed out for the latest tick, for {@code health}. */
    public double lastBudgetMs() {
        return lastBudgetNanos / 1_000_000.0;
    }

    /** The server load other than Ashlar seen for the latest tick, for {@code health}. */
    public double lastOtherMs() {
        return lastOtherMs;
    }
}
