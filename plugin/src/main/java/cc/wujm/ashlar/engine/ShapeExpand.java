// SPDX-License-Identifier: AGPL-3.0-or-later
package cc.wujm.ashlar.engine;

import java.util.ArrayList;
import java.util.List;

/**
 * Turns a {@code mc_build} {@code shapes} entry into axis-aligned block runs. Every shape is
 * inscribed in its inclusive {@code from}/{@code to} box, so odd and even sizes and ellipses need
 * no extra parameters. A cell belongs to the shape when its centre lies inside the solid:
 * <ul>
 * <li>{@code cylinder}: elliptic cross-section across {@code axis}, full length along it;
 * <li>{@code sphere}: the inscribed ellipsoid;
 * <li>{@code dome}: the upper half of an ellipsoid whose flat base is the box face at the start of {@code axis};
 * <li>{@code cone}/{@code pyramid}: an elliptic or rectangular base on that face narrowing linearly to the
 *     opposite face; every layer keeps at least the centre cells, so the tip is never missing.
 * </ul>
 * {@code axis} is {@code x}, {@code y} or {@code z} (pointing towards +), or {@code -x}, {@code -y}, {@code -z}.
 * {@code thickness} 0 is solid; otherwise only cells within {@code thickness} face steps of the outside
 * are kept. Base faces (both ends of a cylinder) stay open, so a hollow cylinder is a ring wall and a
 * hollow dome has no floor. Face-step distance makes every shell watertight: no face-connected path
 * leads from the inside to the outside. Runs are emitted along whichever axis needs the fewest.
 * Pure Java.
 */
public final class ShapeExpand {
    private ShapeExpand() {}

    public static final List<String> SHAPES = List.of("cylinder", "sphere", "dome", "cone", "pyramid");
    public static final List<String> AXES = List.of("y", "x", "z", "-y", "-x", "-z");
    public static final int MAX_THICKNESS = 16;
    /** Bounding-box cells per shape: about a 200-block cube. */
    public static final long MAX_BOX = 8_000_000;
    private static final double EPS = 1e-9;

    /** Inclusive run of blocks; {@code from} is the min corner. */
    public record Run(int[] from, int[] to) {
        public long volume() {
            return ((long) to[0] - from[0] + 1) * ((long) to[1] - from[1] + 1) * ((long) to[2] - from[2] + 1);
        }
    }

    /** {@code runs} cover exactly {@code blocks} cells, all inside {@code min}..{@code max}. */
    public record Result(List<Run> runs, int[] min, int[] max, long blocks) {}

    public static Result expand(String shape, int[] from, int[] to, String axis, int thickness) {
        if (!SHAPES.contains(shape)) throw new IllegalArgumentException("shape: must be one of " + String.join(", ", SHAPES));
        if (!AXES.contains(axis)) throw new IllegalArgumentException("axis: must be one of " + String.join(", ", AXES));
        if (thickness < 0 || thickness > MAX_THICKNESS)
            throw new IllegalArgumentException("thickness: must be between 0 and " + MAX_THICKNESS + ", got " + thickness);
        int[] min = new int[3], n = new int[3];
        long box = 1;
        for (int d = 0; d < 3; d++) {
            min[d] = Math.min(from[d], to[d]);
            long size = Math.abs((long) from[d] - to[d]) + 1;
            box *= size;
            if (box > MAX_BOX) throw new IllegalArgumentException("box: at most " + MAX_BOX + " cells per shape");
            n[d] = (int) size;
        }
        int a = "xyz".indexOf(axis.charAt(axis.length() - 1));
        boolean up = !axis.startsWith("-");
        boolean[] inside = inside(shape, n, a, up);
        boolean[] keep = thickness == 0 ? inside : shell(inside, n, a, up, shape.equals("sphere") ? 0 : shape.equals("cylinder") ? 2 : 1, thickness);
        int runAxis = 0;
        long best = Long.MAX_VALUE;
        for (int d = 0; d < 3; d++) {
            long c = runs(keep, n, d, min, null);
            if (c < best) { best = c; runAxis = d; }
        }
        List<Run> out = new ArrayList<>((int) best);
        runs(keep, n, runAxis, min, out);
        long blocks = 0;
        for (Run r : out) blocks += r.volume();
        int[] max = {min[0] + n[0] - 1, min[1] + n[1] - 1, min[2] + n[2] - 1};
        return new Result(List.copyOf(out), min, max, blocks);
    }

    private static int index(int[] n, int x, int y, int z) {
        return (x * n[1] + y) * n[2] + z;
    }

    /** Cell centre in [-1, 1] across a box side of {@code size} cells. */
    private static double u(int i, int size) {
        return (2.0 * i + 1 - size) / size;
    }

    /** Smallest |u| along a side: 0 for odd sizes, 1/size for even ones. */
    private static double centre(int size) {
        return size % 2 == 1 ? 0 : 1.0 / size;
    }

    static boolean[] inside(String shape, int[] n, int a, boolean up) {
        int p = a == 0 ? 1 : 0, q = a == 2 ? 1 : 2;
        boolean[] in = new boolean[n[0] * n[1] * n[2]];
        int[] c = new int[3];
        for (c[0] = 0; c[0] < n[0]; c[0]++) for (c[1] = 0; c[1] < n[1]; c[1]++) for (c[2] = 0; c[2] < n[2]; c[2]++) {
            double up2 = u(c[p], n[p]), uq = u(c[q], n[q]);
            double r2 = up2 * up2 + uq * uq;
            int k = up ? c[a] : n[a] - 1 - c[a];
            double v = (k + 0.5) / n[a], scale = (double) (n[a] - k) / n[a];
            boolean hit = switch (shape) {
                case "cylinder" -> r2 <= 1 + EPS;
                case "sphere" -> r2 + u(c[a], n[a]) * u(c[a], n[a]) <= 1 + EPS;
                case "dome" -> r2 + v * v <= 1 + EPS;
                case "cone" -> {
                    double floor = centre(n[p]) * centre(n[p]) + centre(n[q]) * centre(n[q]);
                    yield r2 <= Math.max(scale * scale, floor) + EPS;
                }
                default -> Math.max(Math.abs(up2), Math.abs(uq)) <= Math.max(scale, Math.max(centre(n[p]), centre(n[q]))) + EPS;
            };
            in[index(n, c[0], c[1], c[2])] = hit;
        }
        return in;
    }

    /**
     * Cells of {@code in} within {@code t} face steps of the outside. Beyond the box along the axis
     * counts as outside except at open ends: {@code open} 0 = none, 1 = the base, 2 = both ends.
     */
    static boolean[] shell(boolean[] in, int[] n, int a, boolean up, int open, int t) {
        byte[] dist = new byte[in.length];
        int[][] steps = {{1, 0, 0}, {-1, 0, 0}, {0, 1, 0}, {0, -1, 0}, {0, 0, 1}, {0, 0, -1}};
        int[] c = new int[3];
        for (int layer = 1; layer <= t; layer++) {
            boolean grew = false;
            for (c[0] = 0; c[0] < n[0]; c[0]++) for (c[1] = 0; c[1] < n[1]; c[1]++) for (c[2] = 0; c[2] < n[2]; c[2]++) {
                int i = index(n, c[0], c[1], c[2]);
                if (!in[i] || dist[i] != 0) continue;
                for (int[] s : steps) {
                    int x = c[0] + s[0], y = c[1] + s[1], z = c[2] + s[2];
                    boolean beyond = x < 0 || y < 0 || z < 0 || x >= n[0] || y >= n[1] || z >= n[2];
                    boolean edge;
                    if (beyond) {
                        int along = c[a] + s[a];
                        boolean low = along < 0, high = along >= n[a];
                        boolean base = up ? low : high;
                        boolean openEnd = (low || high) && (open == 2 || open == 1 && base);
                        edge = layer == 1 && !openEnd;
                    } else {
                        int j = index(n, x, y, z);
                        edge = layer == 1 ? !in[j] : dist[j] == layer - 1;
                    }
                    if (edge) { dist[i] = (byte) layer; grew = true; break; }
                }
            }
            if (!grew) break;
        }
        boolean[] keep = new boolean[in.length];
        for (int i = 0; i < in.length; i++) keep[i] = dist[i] != 0;
        return keep;
    }

    /** Counts (and with {@code out}, emits) maximal runs along {@code d}. */
    private static long runs(boolean[] keep, int[] n, int d, int[] min, List<Run> out) {
        int e = d == 0 ? 1 : 0, f = d == 2 ? 1 : 2;
        long count = 0;
        int[] c = new int[3];
        for (c[e] = 0; c[e] < n[e]; c[e]++) for (c[f] = 0; c[f] < n[f]; c[f]++) {
            int start = -1;
            for (c[d] = 0; c[d] <= n[d]; c[d]++) {
                boolean on = c[d] < n[d] && keep[index(n, c[0], c[1], c[2])];
                if (on && start < 0) start = c[d];
                if (!on && start >= 0) {
                    count++;
                    if (out != null) {
                        int[] lo = {min[0] + c[0], min[1] + c[1], min[2] + c[2]}, hi = lo.clone();
                        lo[d] = min[d] + start;
                        hi[d] = min[d] + c[d] - 1;
                        out.add(new Run(lo, hi));
                    }
                    start = -1;
                }
            }
        }
        return count;
    }
}
