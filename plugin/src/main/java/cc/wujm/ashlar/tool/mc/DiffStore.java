// SPDX-License-Identifier: AGPL-3.0-or-later
package cc.wujm.ashlar.tool.mc;

import cc.wujm.ashlar.engine.BuildExpectation;
import cc.wujm.ashlar.tool.ToolArgError;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Bounded, owner-scoped {@code mc_diff} receipts (same pattern as {@link VerificationStore}). Each
 * receipt keeps the differing cells (reference state, observed live state) so {@code mc_repair} can
 * write exactly those cells back. New receipts evict the oldest idle ones when a quota is reached.
 * Reload/restart intentionally invalidates IDs.
 */
public final class DiffStore {

    public static final class Receipt {
        public final String id, owner, world;
        public final Instant created, expires;
        public final List<BuildExpectation.Observed> cells;
        public final long differing;
        private boolean busy;

        Receipt(String id, String owner, String world, Instant now, Duration ttl, List<BuildExpectation.Observed> cells, long differing) {
            this.id = id;
            this.owner = owner;
            this.world = world;
            this.created = now;
            this.expires = now.plus(ttl);
            this.cells = List.copyOf(cells);
            this.differing = differing;
        }
    }

    static final int MAX_CELLS_PER_RECEIPT = 100_000;

    private final Map<String, Receipt> receipts = new LinkedHashMap<>();
    private final Clock clock;
    private final int maxReceipts;
    private final long maxCells;
    private final Duration ttl;

    public DiffStore() {
        this(Clock.systemUTC(), 32, 1_000_000, Duration.ofHours(2));
    }

    DiffStore(Clock clock, int maxReceipts, long maxCells, Duration ttl) {
        this.clock = clock;
        this.maxReceipts = maxReceipts;
        this.maxCells = maxCells;
        this.ttl = ttl;
    }

    private void purge() {
        Instant now = clock.instant();
        receipts.values().removeIf(r -> !r.busy && !now.isBefore(r.expires));
    }

    private long storedCells() {
        return receipts.values().stream().mapToLong(r -> r.cells.size()).sum();
    }

    public synchronized Receipt put(String owner, String world, List<BuildExpectation.Observed> cells, long differing) {
        purge();
        if (cells.size() > maxCells) throw new ToolArgError("diff receipt is larger than the receipt storage");
        Iterator<Receipt> oldest = receipts.values().iterator();
        while ((receipts.size() >= maxReceipts || storedCells() + cells.size() > maxCells) && oldest.hasNext()) {
            if (!oldest.next().busy) oldest.remove();
        }
        if (receipts.size() >= maxReceipts || storedCells() + cells.size() > maxCells) {
            throw new ToolArgError("diff receipt storage is busy; retry after running repairs finish");
        }
        Receipt r = new Receipt("diff-" + UUID.randomUUID(), owner, world, clock.instant(), ttl, cells, differing);
        receipts.put(r.id, r);
        return r;
    }

    public synchronized Receipt get(String id, String owner) {
        purge();
        Receipt r = receipts.get(id);
        if (r == null || !r.owner.equals(owner)) throw new ToolArgError("diff receipt not found or expired; run mc_diff again");
        return r;
    }

    public synchronized void acquire(Receipt r) {
        if (receipts.get(r.id) != r) throw new ToolArgError("diff receipt expired; run mc_diff again");
        if (r.busy) throw new ToolArgError("diff receipt is busy");
        r.busy = true;
    }

    public synchronized void release(Receipt r) {
        r.busy = false;
    }

    /** Removes a receipt whose cells were written: its observed states are stale from now on. */
    public synchronized void consume(Receipt r) {
        receipts.remove(r.id, r);
    }
}
