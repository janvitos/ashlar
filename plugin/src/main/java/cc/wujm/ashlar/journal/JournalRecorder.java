// SPDX-License-Identifier: AGPL-3.0-or-later
package cc.wujm.ashlar.journal;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.BitSet;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Per-call record of every cell a write touched: its state before the first write and, once
 * {@link #setNew} has run, its final state. Positions are packed into one {@code long} (see
 * {@link #pack}) and deduplicated in an open-addressing table, so a million cells cost tens of
 * megabytes rather than hundreds. States are palette indices. A cell touched again after it was
 * finalized is marked pending again so its final state is re-read. Past {@code maxCells} the
 * recorder overflows: it frees its arrays and records nothing more.
 *
 * <p>Pure Java. Not thread-safe: the owning task writes it on the main thread, and it is only read
 * elsewhere after that task's future completed.
 */
public final class JournalRecorder {

    private static final int X_BITS = 26, Z_BITS = 26, Y_BITS = 12;

    private final int maxCells;
    private final List<String> palette = new ArrayList<>();
    private final Map<String, Integer> paletteIndex = new HashMap<>();
    private long[] table = new long[64];
    private int[] slots = new int[64];
    private long[] pos = new long[16];
    private int[] oldState = new int[16];
    private int[] newState = new int[16];
    private int size;
    private int[] pending = new int[16];
    private int pendingSize;
    private BitSet pendingFlag = new BitSet();
    private boolean overflowed;
    private long blockEntityCells;
    private long blockEntityWrites;

    public JournalRecorder(int maxCells) {
        this.maxCells = maxCells;
    }

    /** Packs x (26 bits), z (26 bits) and y (12 bits), all two's complement. */
    public static long pack(int x, int y, int z) {
        return ((long) (x & 0x3FFFFFF) << (Z_BITS + Y_BITS)) | ((long) (z & 0x3FFFFFF) << Y_BITS) | (y & 0xFFF);
    }

    public static int x(long key) {
        return (int) (key >> (Z_BITS + Y_BITS));
    }

    public static int z(long key) {
        return (int) (key << X_BITS >> (X_BITS + Y_BITS));
    }

    public static int y(long key) {
        return (int) (key << (X_BITS + Z_BITS) >> (X_BITS + Z_BITS));
    }

    public int paletteIndex(String state) {
        Integer i = paletteIndex.get(state);
        if (i != null) return i;
        palette.add(state);
        paletteIndex.put(state, palette.size() - 1);
        return palette.size() - 1;
    }

    /**
     * Records {@code key} with original state {@code oldIdx} the first time it is seen and returns
     * {@code true}; for a known cell only marks it pending again and returns {@code false}.
     */
    public boolean touch(long key, int oldIdx) {
        if (overflowed) return false;
        int mask = table.length - 1;
        int h = mix(key) & mask;
        while (slots[h] != 0) {
            if (table[h] == key) {
                markPending(slots[h] - 1);
                return false;
            }
            h = (h + 1) & mask;
        }
        if (size >= maxCells) {
            overflow();
            return false;
        }
        if (size == pos.length) {
            int n = pos.length * 2;
            pos = Arrays.copyOf(pos, n);
            oldState = Arrays.copyOf(oldState, n);
            newState = Arrays.copyOf(newState, n);
        }
        pos[size] = key;
        oldState[size] = oldIdx;
        newState[size] = oldIdx;
        table[h] = key;
        slots[h] = size + 1;
        markPending(size);
        size++;
        if (size * 2 > table.length) rehash();
        return true;
    }

    private void markPending(int entry) {
        if (pendingFlag.get(entry)) return;
        pendingFlag.set(entry);
        if (pendingSize == pending.length) pending = Arrays.copyOf(pending, pending.length * 2);
        pending[pendingSize++] = entry;
    }

    private void rehash() {
        long[] oldTable = table;
        int[] oldSlots = slots;
        table = new long[oldTable.length * 2];
        slots = new int[oldTable.length * 2];
        int mask = table.length - 1;
        for (int i = 0; i < oldTable.length; i++) {
            if (oldSlots[i] == 0) continue;
            int h = mix(oldTable[i]) & mask;
            while (slots[h] != 0) h = (h + 1) & mask;
            table[h] = oldTable[i];
            slots[h] = oldSlots[i];
        }
    }

    private static int mix(long key) {
        long h = key * 0x9E3779B97F4A7C15L;
        return (int) (h ^ (h >>> 32));
    }

    private void overflow() {
        overflowed = true;
        table = new long[0];
        slots = new int[0];
        pos = new long[0];
        oldState = new int[0];
        newState = new int[0];
        pending = new int[0];
        pendingFlag = new BitSet();
        size = 0;
        pendingSize = 0;
    }

    public int pendingSize() {
        return pendingSize;
    }

    public int pendingEntry(int i) {
        return pending[i];
    }

    public long key(int entry) {
        return pos[entry];
    }

    public void setNew(int entry, int stateIdx) {
        newState[entry] = stateIdx;
    }

    public void clearPending() {
        pendingSize = 0;
        pendingFlag.clear();
    }

    public void addBlockEntityCell() {
        blockEntityCells++;
    }

    /** A block-entity value (sign text) written without a block state change: not journalled. */
    public void addBlockEntityWrite() {
        blockEntityWrites++;
    }

    public boolean overflowed() {
        return overflowed;
    }

    public int maxCells() {
        return maxCells;
    }

    public int size() {
        return size;
    }

    public long blockEntityCells() {
        return blockEntityCells;
    }

    public long blockEntityWrites() {
        return blockEntityWrites;
    }

    /** The changed cells only (final state differs from the original), with a compacted palette. */
    public JournalCells toCells() {
        int n = 0;
        for (int i = 0; i < size; i++) if (oldState[i] != newState[i]) n++;
        long[] p = new long[n];
        int[] o = new int[n];
        int[] w = new int[n];
        int[] remap = new int[palette.size()];
        Arrays.fill(remap, -1);
        List<String> used = new ArrayList<>();
        int k = 0;
        for (int i = 0; i < size; i++) {
            if (oldState[i] == newState[i]) continue;
            p[k] = pos[i];
            o[k] = remapped(remap, used, oldState[i]);
            w[k] = remapped(remap, used, newState[i]);
            k++;
        }
        return new JournalCells(used, p, o, w);
    }

    private int remapped(int[] remap, List<String> used, int idx) {
        if (remap[idx] < 0) {
            remap[idx] = used.size();
            used.add(palette.get(idx));
        }
        return remap[idx];
    }
}
