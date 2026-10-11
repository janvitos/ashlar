// SPDX-License-Identifier: AGPL-3.0-or-later
package cc.wujm.ashlar.tool.mc;

import cc.wujm.ashlar.tool.ToolArgError;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * A set of block cells {@code [x,y,z,"state"]} in primitive arrays, sorted y, then z, then x, with one
 * state per position. Input order never matters: equal duplicates collapse and conflicting ones are
 * rejected, so the same cells always give the same set. Pure Java.
 */
final class CellSet {

    static final int Y_OFFSET = 2048;
    static final int XZ_OFFSET = 1 << 25;
    private static final Pattern STATE = Pattern.compile("[a-z0-9_.:/-]+(\\[[a-z0-9_]+=[a-z0-9_]+(,[a-z0-9_]+=[a-z0-9_]+)*])?");

    final long[] keys;
    final int[] ids;
    final List<String> palette;

    private CellSet(long[] keys, int[] ids, List<String> palette) {
        this.keys = keys;
        this.ids = ids;
        this.palette = palette;
    }

    int size() {
        return keys.length;
    }

    static long key(int x, int y, int z) {
        return ((long) (y + Y_OFFSET) << 52) | ((long) (z + XZ_OFFSET) << 26) | (x + XZ_OFFSET);
    }

    static int x(long key) {
        return (int) (key & ((1 << 26) - 1)) - XZ_OFFSET;
    }

    static int z(long key) {
        return (int) ((key >>> 26) & ((1 << 26) - 1)) - XZ_OFFSET;
    }

    static int y(long key) {
        return (int) (key >>> 52) - Y_OFFSET;
    }

    /** Index of {@code key}, or -1. */
    int find(long key) {
        int i = Arrays.binarySearch(keys, key);
        return i < 0 ? -1 : i;
    }

    String state(int i) {
        return palette.get(ids[i]);
    }

    /** Collects cells in any order; {@link #build} sorts and checks them. */
    static final class Builder {
        private long[] keys = new long[1024];
        private int[] ids = new int[1024];
        private int n;
        private final List<String> palette = new ArrayList<>();
        private final Map<String, Integer> index = new HashMap<>();
        private final long max;
        private final String what;

        Builder(long max, String what) {
            this.max = max;
            this.what = what;
        }

        int size() {
            return n;
        }

        void add(int x, int y, int z, String state) {
            if (n >= max) throw new ToolArgError(what + ": more than " + max + " cells");
            if (y < -Y_OFFSET || y >= Y_OFFSET || Math.abs((long) x) >= 30_000_000 || Math.abs((long) z) >= 30_000_000) {
                throw new ToolArgError(what + ": cell [" + x + "," + y + "," + z + "] is outside the world");
            }
            Integer id = index.get(state);
            if (id == null) {
                String s = state.trim();
                if (s.isEmpty() || s.length() > 512 || !STATE.matcher(s).matches()) {
                    throw new ToolArgError(what + ": \"" + abbreviate(state) + "\" is not a block state like minecraft:oak_stairs[facing=east]");
                }
                if (!s.contains(":")) s = "minecraft:" + s;
                id = index.get(s);
                if (id == null) {
                    id = palette.size();
                    palette.add(s);
                    index.put(s, id);
                }
                index.put(state, id);
            }
            if (n == keys.length) {
                keys = Arrays.copyOf(keys, n * 2);
                ids = Arrays.copyOf(ids, n * 2);
            }
            keys[n] = key(x, y, z);
            ids[n] = id;
            n++;
        }

        CellSet build() {
            if (n == 0) throw new ToolArgError(what + ": no cells");
            // Sorted palette: ids then never depend on input order either.
            List<String> sorted = new ArrayList<>(palette);
            sorted.sort(null);
            int[] remap = new int[palette.size()];
            for (int i = 0; i < remap.length; i++) remap[i] = Collections.binarySearch(sorted, palette.get(i));
            long[] k = Arrays.copyOf(keys, n);
            int[] v = new int[n];
            for (int i = 0; i < n; i++) v[i] = remap[ids[i]];
            sort(k, v, 0, n - 1);
            int m = 0;
            for (int i = 0; i < n; i++) {
                if (m > 0 && k[m - 1] == k[i]) {
                    if (v[m - 1] != v[i]) {
                        throw new ToolArgError(what + ": cell [" + x(k[i]) + "," + y(k[i]) + "," + z(k[i]) + "] is given twice with different states ("
                                + sorted.get(v[m - 1]) + " and " + sorted.get(v[i]) + ")");
                    }
                    continue;
                }
                k[m] = k[i];
                v[m] = v[i];
                m++;
            }
            return new CellSet(Arrays.copyOf(k, m), Arrays.copyOf(v, m), List.copyOf(sorted));
        }
    }

    private static String abbreviate(String s) {
        return s.length() > 60 ? s.substring(0, 60) + "..." : s;
    }

    /** In-place sort of parallel arrays by key (iterative quicksort with insertion sort for short ranges). */
    static void sort(long[] k, int[] v, int lo, int hi) {
        int[] stack = new int[128];
        int sp = 0;
        stack[sp++] = lo;
        stack[sp++] = hi;
        while (sp > 0) {
            hi = stack[--sp];
            lo = stack[--sp];
            while (hi - lo > 16) {
                int mid = (lo + hi) >>> 1;
                if (k[mid] < k[lo]) swap(k, v, mid, lo);
                if (k[hi] < k[lo]) swap(k, v, hi, lo);
                if (k[hi] < k[mid]) swap(k, v, hi, mid);
                long pivot = k[mid];
                int i = lo, j = hi;
                while (i <= j) {
                    while (k[i] < pivot) i++;
                    while (k[j] > pivot) j--;
                    if (i <= j) swap(k, v, i++, j--);
                }
                // Recurse into the smaller half via the stack, loop on the larger: depth stays logarithmic.
                if (j - lo < hi - i) {
                    if (lo < j) {
                        stack[sp++] = lo;
                        stack[sp++] = j;
                    }
                    lo = i;
                } else {
                    if (i < hi) {
                        stack[sp++] = i;
                        stack[sp++] = hi;
                    }
                    hi = j;
                }
            }
            for (int i = lo + 1; i <= hi; i++) {
                long kk = k[i];
                int vv = v[i];
                int j = i - 1;
                while (j >= lo && k[j] > kk) {
                    k[j + 1] = k[j];
                    v[j + 1] = v[j];
                    j--;
                }
                k[j + 1] = kk;
                v[j + 1] = vv;
            }
        }
    }

    private static void swap(long[] k, int[] v, int a, int b) {
        long t = k[a];
        k[a] = k[b];
        k[b] = t;
        int u = v[a];
        v[a] = v[b];
        v[b] = u;
    }
}
