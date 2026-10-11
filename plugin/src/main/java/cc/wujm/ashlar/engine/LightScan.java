// SPDX-License-Identifier: AGPL-3.0-or-later
package cc.wujm.ashlar.engine;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.PriorityQueue;

/**
 * Step 18 {@code mc_light}, pure Java over a {@link Cells} view: counts spawn spaces in a box, the
 * dark ones among them, and optionally plans light positions greedily so every dark space ends
 * above the threshold. Light from a planned source loses one level per step through cells that
 * transmit light (6-neighbour flood, as in vanilla), never leaving the box.
 */
public final class LightScan {

    /** Block states and light levels at world positions (cells outside the copied chunks read as stone, light 15). */
    public interface Cells {
        String state(int x, int y, int z);

        int blockLight(int x, int y, int z);

        int skyLight(int x, int y, int z);
    }

    /** {@code spawnable:false} counts every open cell (feet and head clear, any floor) instead of spawn spaces. */
    public record Options(int threshold, boolean includeSky, boolean spawnable) {}

    public record Suggest(String block, int level, int spacing, String placement) {}

    public record Result(long spaces, int[][] dark, Map<Integer, Integer> darkByY, List<int[]> lights, int covered, int uncoverable,
            String note) {}

    /** At most this many dark spaces are planned for; a larger set asks for a smaller box. */
    public static final int MAX_PLAN = 20_000;

    private LightScan() {}

    public static Result scan(Cells c, Region box, Options o, Suggest s) {
        long spaces = 0;
        List<int[]> dark = new ArrayList<>();
        Map<Integer, Integer> byY = new java.util.TreeMap<>();
        for (int y = box.minY(); y <= box.maxY(); y++) {
            for (int z = box.minZ(); z <= box.maxZ(); z++) {
                for (int x = box.minX(); x <= box.maxX(); x++) {
                    if (o.spawnable() ? !LightRules.feet(c.state(x, y, z)) || !LightRules.floor(c.state(x, y - 1, z))
                            || !LightRules.head(c.state(x, y + 1, z)) : !LightRules.head(c.state(x, y, z))) {
                        continue;
                    }
                    spaces++;
                    int light = c.blockLight(x, y, z);
                    if (o.includeSky()) light = Math.max(light, c.skyLight(x, y, z));
                    if (light <= o.threshold()) {
                        dark.add(new int[]{x, y, z});
                        byY.merge(y, 1, Integer::sum);
                    }
                }
            }
        }
        int[][] darkArr = dark.toArray(new int[0][]);
        if (s == null || darkArr.length == 0) return new Result(spaces, darkArr, byY, List.of(), 0, 0, null);
        if (darkArr.length > MAX_PLAN) {
            return new Result(spaces, darkArr, byY, List.of(), 0, darkArr.length,
                    "too many dark spaces to plan (" + darkArr.length + " > " + MAX_PLAN + "); ask for suggestions over a smaller box");
        }
        return plan(c, box, o, s, spaces, darkArr, byY);
    }

    private static Result plan(Cells c, Region box, Options o, Suggest s, long spaces, int[][] dark, Map<Integer, Integer> byY) {
        int reach = s.level() - o.threshold() - 1;
        Map<Long, Integer> darkIndex = new HashMap<>();
        for (int i = 0; i < dark.length; i++) darkIndex.put(key(dark[i][0], dark[i][1], dark[i][2]), i);
        // Candidate positions, one per dark space (deduplicated): the space itself, or the cell under its ceiling.
        Map<Long, int[]> candidates = new java.util.LinkedHashMap<>();
        for (int[] d : dark) {
            int[] p = s.placement().equals("ceiling") ? underCeiling(c, box, d) : d;
            if (p != null) candidates.putIfAbsent(key(p[0], p[1], p[2]), p);
        }
        List<int[]> pos = new ArrayList<>(candidates.values());
        int[][] covers = new int[pos.size()][];
        for (int i = 0; i < pos.size(); i++) covers[i] = flood(c, box, pos.get(i), reach, darkIndex);
        // Lazy greedy: gains only shrink, so a stale queue entry is re-scored and pushed back.
        boolean[] done = new boolean[dark.length];
        PriorityQueue<int[]> queue = new PriorityQueue<>((a, b) -> a[1] != b[1] ? Integer.compare(b[1], a[1]) : Integer.compare(a[0], b[0]));
        for (int i = 0; i < covers.length; i++) if (covers[i].length > 0) queue.add(new int[]{i, covers[i].length});
        List<int[]> lights = new ArrayList<>();
        int covered = 0;
        while (!queue.isEmpty() && covered < dark.length) {
            int[] top = queue.poll();
            int gain = 0;
            for (int d : covers[top[0]]) if (!done[d]) gain++;
            if (gain == 0) continue;
            if (gain < top[1]) {
                queue.add(new int[]{top[0], gain});
                continue;
            }
            int[] p = pos.get(top[0]);
            if (tooClose(lights, p, s.spacing())) continue;
            lights.add(p);
            for (int d : covers[top[0]]) {
                if (!done[d]) {
                    done[d] = true;
                    covered++;
                }
            }
        }
        return new Result(spaces, dark, byY, lights, covered, dark.length - covered, null);
    }

    private static boolean tooClose(List<int[]> lights, int[] p, int spacing) {
        if (spacing <= 1) return false;
        for (int[] l : lights) {
            if (Math.max(Math.abs(l[0] - p[0]), Math.max(Math.abs(l[1] - p[1]), Math.abs(l[2] - p[2]))) < spacing) return true;
        }
        return false;
    }

    /** The highest open cell above a dark space with a block over it, at most 16 up and inside the box. */
    private static int[] underCeiling(Cells c, Region box, int[] d) {
        for (int y = d[1]; y <= Math.min(box.maxY(), d[1] + 16); y++) {
            if (!LightRules.transmits(c.state(d[0], y, d[2])) || WalkShapes.of(c.state(d[0], y, d[2]), false).collides()) return null;
            if (LightRules.ceiling(c.state(d[0], y + 1, d[2]))) return new int[]{d[0], y, d[2]};
        }
        return null;
    }

    /** Dark-space indices a source at {@code p} lights above the threshold: flood fill up to {@code reach} steps. */
    static int[] flood(Cells c, Region box, int[] p, int reach, Map<Long, Integer> darkIndex) {
        if (reach < 0) return new int[0];
        Map<Long, Integer> seen = new HashMap<>();
        ArrayDequeInts queue = new ArrayDequeInts();
        seen.put(key(p[0], p[1], p[2]), 0);
        queue.add(p[0], p[1], p[2]);
        int[] out = new int[16];
        int n = 0;
        int[][] dirs = {{1, 0, 0}, {-1, 0, 0}, {0, 1, 0}, {0, -1, 0}, {0, 0, 1}, {0, 0, -1}};
        while (!queue.isEmpty()) {
            int x = queue.x(), y = queue.y(), z = queue.z();
            queue.pop();
            long k = key(x, y, z);
            int d = seen.get(k);
            Integer idx = darkIndex.get(k);
            if (idx != null) {
                if (n == out.length) out = Arrays.copyOf(out, n * 2);
                out[n++] = idx;
            }
            if (d == reach) continue;
            for (int[] v : dirs) {
                int nx = x + v[0], ny = y + v[1], nz = z + v[2];
                if (nx < box.minX() || nx > box.maxX() || ny < box.minY() - 1 || ny > box.maxY() + 1 || nz < box.minZ() || nz > box.maxZ()) continue;
                long nk = key(nx, ny, nz);
                if (seen.containsKey(nk) || !LightRules.transmits(c.state(nx, ny, nz))) continue;
                seen.put(nk, d + 1);
                queue.add(nx, ny, nz);
            }
        }
        return Arrays.copyOf(out, n);
    }

    static long key(int x, int y, int z) {
        return ((long) (x & 0x3FFFFFF) << 38) | ((long) (z & 0x3FFFFFF) << 12) | (y & 0xFFF);
    }

    /** A FIFO of int triples without boxing. */
    private static final class ArrayDequeInts {
        private int[] a = new int[3 * 256];
        private int head, tail;

        void add(int x, int y, int z) {
            if (tail + 3 > a.length) {
                if (head > 0) {
                    System.arraycopy(a, head, a, 0, tail - head);
                    tail -= head;
                    head = 0;
                }
                if (tail + 3 > a.length) a = Arrays.copyOf(a, a.length * 2);
            }
            a[tail++] = x;
            a[tail++] = y;
            a[tail++] = z;
        }

        boolean isEmpty() {
            return head == tail;
        }

        int x() {
            return a[head];
        }

        int y() {
            return a[head + 1];
        }

        int z() {
            return a[head + 2];
        }

        void pop() {
            head += 3;
        }
    }
}
