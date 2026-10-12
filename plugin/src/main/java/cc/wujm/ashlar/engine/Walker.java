// SPDX-License-Identifier: AGPL-3.0-or-later
package cc.wujm.ashlar.engine;

import cc.wujm.ashlar.engine.WalkShapes.Kind;
import cc.wujm.ashlar.engine.WalkShapes.Shape;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Player-movement reachability inside a box, pure Java (no Bukkit). A node is a place a player can
 * stand: a column (x, z) and a feet height h on top of a collision box, with 1.8 blocks clear above
 * and no lava, fire or (unless allowed) water around the body. Ladder, vine and (when allowed)
 * water cells add nodes at their own height, climbable up and down.
 *
 * <p>Moves go to the four horizontal neighbours. Walking onto a surface may rise at most
 * {@code stepHeight} (0.6), or 1.25 with jumping; a bottom stair is a 0.5 step only when entered
 * head-on, moving toward its {@code facing} (sideways or from the back it is a 1.0 rise). A move may
 * drop at most {@code maxDrop}. The body must fit through the destination column from the new feet
 * height up to the higher of both heights plus 1.8, so walls, closed doors, fences (1.5) and low
 * ceilings stop it. Cells above the box are assumed empty; columns outside it are never entered.
 */
public final class Walker {

    public static final double HEIGHT = 1.8;
    public static final double JUMP = 1.25;
    static final double EPS = 1e-6;
    static final int[][] DIRS = {{0, -1}, {1, 0}, {0, 1}, {-1, 0}};
    static final String[] DIR_NAMES = {"north", "east", "south", "west"};

    public record Rules(boolean jump, double stepHeight, boolean ladders, boolean water, boolean anyDoor, double maxDrop) {
        public static final Rules DEFAULT = new Rules(false, .6, true, false, false, 3);

        double maxUp() {
            return jump ? Math.max(stepHeight, JUMP) : stepHeight;
        }
    }

    /** Why a move or node fails, and the cell responsible (null when there is none). */
    public record Block(String reason, int[] at, String state) {}

    /** The nearest reached node to a target and the move toward it that fails. */
    public record Diagnosis(int node, Block block) {}

    private final Region box;
    private final int sx, sz;
    private final int[] ids;
    private final List<String> palette;
    private final Shape[] shapes;
    private final Rules rules;
    private final int[] colStart;
    private final double[] nodeH;
    private final int[] nodeSupport;
    private final Block[] nodeProblem;
    private final int[] nodeCol;

    /** {@code ids} holds palette indexes in RegionData order: y outer, z middle, x inner. */
    public Walker(Region box, int[] ids, List<String> palette, Rules rules) {
        this.box = box;
        this.sx = box.maxX() - box.minX() + 1;
        this.sz = box.maxZ() - box.minZ() + 1;
        if ((long) sx * sz * (box.maxY() - box.minY() + 1) != ids.length) throw new IllegalArgumentException("ids do not match the box");
        this.ids = ids;
        this.palette = List.copyOf(palette);
        this.rules = rules;
        this.shapes = new Shape[palette.size()];
        for (int i = 0; i < shapes.length; i++) shapes[i] = WalkShapes.of(palette.get(i), rules.anyDoor());

        int cols = sx * sz;
        colStart = new int[cols + 1];
        double[] h = new double[64];
        int[] sup = new int[64];
        int n = 0;
        for (int c = 0; c < cols; c++) {
            colStart[c] = n;
            int x = box.minX() + c % sx, z = box.minZ() + c / sx;
            double last = Double.NaN;
            for (int y = box.minY(); y <= box.maxY(); y++) {
                Shape s = shape(x, y, z);
                double stand = Double.NaN;
                if (s.standable() && s.collides() && !s.hurts()) stand = y + s.top();
                else if (s.kind() == Kind.LADDER && rules.ladders() || s.kind() == Kind.WATER && rules.water()) stand = y;
                if (Double.isNaN(stand) || Math.abs(stand - last) < EPS) continue;
                if (n == h.length) {
                    h = Arrays.copyOf(h, n * 2);
                    sup = Arrays.copyOf(sup, n * 2);
                }
                h[n] = stand;
                sup[n] = y;
                n++;
                last = stand;
            }
        }
        colStart[cols] = n;
        nodeH = Arrays.copyOf(h, n);
        nodeSupport = Arrays.copyOf(sup, n);
        nodeProblem = new Block[n];
        nodeCol = new int[n];
        for (int c = 0; c < cols; c++) {
            int x = box.minX() + c % sx, z = box.minZ() + c / sx;
            for (int i = colStart[c]; i < colStart[c + 1]; i++) {
                nodeCol[i] = c;
                nodeProblem[i] = standProblem(x, z, nodeH[i]);
            }
        }
    }

    public Region box() {
        return box;
    }

    public int nodeCount() {
        return nodeH.length;
    }

    public int x(int node) {
        return box.minX() + col(node) % sx;
    }

    public int z(int node) {
        return box.minZ() + col(node) / sx;
    }

    public double h(int node) {
        return nodeH[node];
    }

    /** Why nobody can stand at this node, or null when it is a valid standing place. */
    public Block problem(int node) {
        return nodeProblem[node];
    }

    private int col(int node) {
        return nodeCol[node];
    }

    public boolean inside(int x, int z) {
        return x >= box.minX() && x <= box.maxX() && z >= box.minZ() && z <= box.maxZ();
    }

    /** The block state at a cell; cells above or below the box read as air. */
    public String state(int x, int y, int z) {
        int id = id(x, y, z);
        return id < 0 ? "minecraft:air" : palette.get(id);
    }

    private int id(int x, int y, int z) {
        if (y < box.minY() || y > box.maxY() || !inside(x, z)) return -1;
        return ids[((y - box.minY()) * sz + (z - box.minZ())) * sx + (x - box.minX())];
    }

    Shape shape(int x, int y, int z) {
        int id = id(x, y, z);
        return id < 0 ? WalkShapes.NONE : shapes[id];
    }

    private int[] range(int x, int z) {
        int c = (z - box.minZ()) * sx + (x - box.minX());
        return new int[]{colStart[c], colStart[c + 1]};
    }

    /**
     * The node in column (x, z) whose feet height is closest to {@code y}, within half a block; -1 if
     * none. A valid standing place wins over a closer one with a problem: for a bottom slab at y, feet
     * height y (under the slab, blocked by it) is closer than y + 0.5 (on top of it).
     */
    public int nodeAt(int x, int y, int z) {
        if (!inside(x, z)) return -1;
        int[] r = range(x, z);
        int best = -1;
        for (int i = r[0]; i < r[1]; i++) {
            double d = Math.abs(nodeH[i] - y);
            if (d > .5 + EPS) continue;
            if (best >= 0) {
                boolean valid = nodeProblem[i] == null;
                if (valid != (nodeProblem[best] == null) ? !valid : d >= Math.abs(nodeH[best] - y)) continue;
            }
            best = i;
        }
        return best;
    }

    /** True when a reached node in column (x, z) has feet height in [lo, hi]. */
    public boolean reached(int[] parent, int x, int z, double lo, double hi) {
        if (!inside(x, z)) return false;
        int[] r = range(x, z);
        for (int i = r[0]; i < r[1]; i++) if (parent[i] != -1 && nodeH[i] >= lo - EPS && nodeH[i] <= hi + EPS) return true;
        return false;
    }

    /** The lowest cell whose collision overlaps the open height range (lo, hi) in column (x, z), skipping {@code skipY}. */
    private int obstruction(int x, int z, double lo, double hi, int skipY) {
        for (int y = (int) Math.floor(lo) - 1; y <= (int) Math.floor(hi - EPS); y++) {
            if (y == skipY) continue;
            Shape s = shape(x, y, z);
            if (s.collides() && y + s.bottom() < hi - EPS && y + s.top() > lo + EPS) return y;
        }
        return Integer.MIN_VALUE;
    }

    private Block standProblem(int x, int z, double h) {
        int oy = obstruction(x, z, h, h + HEIGHT, Integer.MIN_VALUE);
        if (oy != Integer.MIN_VALUE) return block("headroom: less than 1.8 blocks clear above", x, oy, z);
        for (int y = (int) Math.floor(h + EPS); y <= (int) Math.floor(h + HEIGHT - EPS); y++) {
            Kind k = shape(x, y, z).kind();
            if (k == Kind.HAZARD) return block("hazard", x, y, z);
            if (k == Kind.WATER && !rules.water()) return block("water (allowWater is false)", x, y, z);
        }
        return null;
    }

    private Block block(String reason, int x, int y, int z) {
        return new Block(reason, new int[]{x, y, z}, state(x, y, z));
    }

    private boolean climbable(int node) {
        Kind k = shape(x(node), (int) Math.floor(nodeH[node] + EPS), z(node)).kind();
        return k == Kind.LADDER && rules.ladders() || k == Kind.WATER && rules.water();
    }

    /** A move from {@code node} one block in direction {@code dir}: the destination node, or why it fails. */
    record Step(int node, Block block) {}

    Step step(int node, int dir) {
        Step s = step(x(node), z(node), nodeH[node], nodeSupport[node], dir);
        if (s.node() < 0 && climbable(node) && s.block() != null) {
            // Climbing to the top of a ladder or swimming up: try again from one block higher.
            int x = x(node), z = z(node);
            double h = nodeH[node];
            if (obstruction(x, z, h, h + 1 + HEIGHT, Integer.MIN_VALUE) == Integer.MIN_VALUE) {
                Step up = step(x, z, h + 1, Integer.MIN_VALUE, dir);
                if (up.node() >= 0) return up;
            }
        }
        return s;
    }

    private Step step(int x, int z, double h, int supportY, int dir) {
        int dx = DIRS[dir][0], dz = DIRS[dir][1], x2 = x + dx, z2 = z + dz;
        if (!inside(x2, z2)) return new Step(-1, new Block("edge of the box", new int[]{x2, (int) Math.floor(h + EPS), z2}, null));
        double hEff = h;
        if (supportY != Integer.MIN_VALUE) {
            Shape src = shape(x, supportY, z);
            if (src.kind() == Kind.STAIR && opposite(src.facing(), DIR_NAMES[dir]) && Math.abs(supportY + 1 - h) < EPS) hEff = h - .5;
        }
        int[] r = range(x2, z2);
        int best = -1;
        boolean headOn = false;
        for (int i = r[1] - 1; i >= r[0]; i--) {
            double h2 = nodeH[i];
            if (h2 <= h + rules.maxUp() + EPS) {
                best = i;
                break;
            }
            Shape s2 = shape(x2, nodeSupport[i], z2);
            if (s2.kind() == Kind.STAIR && DIR_NAMES[dir].equals(s2.facing()) && Math.abs(nodeSupport[i] + 1 - h2) < EPS
                    && nodeSupport[i] + .5 <= h + rules.stepHeight() + EPS) {
                best = i;
                headOn = true;
                break;
            }
        }
        if (best >= 0 && hEff - nodeH[best] > rules.maxDrop() + EPS) {
            int oy = obstruction(x2, z2, h, h + HEIGHT, Integer.MIN_VALUE);
            if (oy != Integer.MIN_VALUE) return new Step(-1, passBlock(x2, oy, z2, h));
            return new Step(-1, new Block(String.format(java.util.Locale.ROOT, "gap: a drop of %.2f is more than maxDrop %.2f",
                    hEff - nodeH[best], rules.maxDrop()), new int[]{x2, (int) Math.floor(nodeH[best] + EPS), z2}, null));
        }
        if (best < 0) {
            int oy = obstruction(x2, z2, h, h + HEIGHT, Integer.MIN_VALUE);
            if (oy != Integer.MIN_VALUE) return new Step(-1, passBlock(x2, oy, z2, h));
            return new Step(-1, new Block("gap: no floor below within the box", new int[]{x2, (int) Math.floor(h + EPS), z2}, null));
        }
        double h2 = nodeH[best];
        int sup = nodeSupport[best];
        if (headOn) {
            int oy = obstruction(x2, z2, sup + .5, sup + .5 + HEIGHT, sup);
            if (oy == Integer.MIN_VALUE) oy = obstruction(x2, z2, h2, h2 + HEIGHT, Integer.MIN_VALUE);
            if (oy != Integer.MIN_VALUE) return new Step(-1, block("headroom: less than 1.8 blocks clear above", x2, oy, z2));
        } else {
            int oy = obstruction(x2, z2, h2, Math.max(h, h2) + HEIGHT, Integer.MIN_VALUE);
            if (oy != Integer.MIN_VALUE) return new Step(-1, passBlock(x2, oy, z2, h));
            if (h2 > h + rules.stepHeight() + EPS) {
                int jy = obstruction(x, z, h, h2 + HEIGHT, Integer.MIN_VALUE);
                if (jy != Integer.MIN_VALUE) return new Step(-1, block("headroom: no room to jump", x, jy, z));
            }
        }
        if (nodeProblem[best] != null) return new Step(-1, nodeProblem[best]);
        return new Step(best, null);
    }

    /** Names what stops the body entering a column at feet height {@code h}. */
    private Block passBlock(int x, int y, int z, double h) {
        Shape s = shape(x, y, z);
        if (y + s.bottom() > h + rules.maxUp() + EPS) return block("headroom: less than 1.8 blocks clear above", x, y, z);
        if (s.kind() == Kind.DOOR) return block("closed door", x, y, z);
        if (s.kind() == Kind.STAIR) return block("stair approached from the side or back (only head-on is a step)", x, y, z);
        return block(String.format(java.util.Locale.ROOT, "step too high (rise %.2f)", y + s.top() - h), x, y, z);
    }

    private static boolean opposite(String a, String b) {
        int i = Arrays.asList(DIR_NAMES).indexOf(a), j = Arrays.asList(DIR_NAMES).indexOf(b);
        return i >= 0 && j >= 0 && (i + 2) % 4 == j;
    }

    private List<Integer> moves(int node) {
        List<Integer> out = new ArrayList<>(6);
        for (int d = 0; d < 4; d++) {
            Step s = step(node, d);
            if (s.node() >= 0) out.add(s.node());
        }
        if (climbable(node)) {
            int x = x(node), z = z(node);
            double h = nodeH[node];
            int[] r = range(x, z);
            for (int i = r[0]; i < r[1]; i++) {
                if (i == node || nodeProblem[i] != null || Math.abs(nodeH[i] - h) > 1 + EPS) continue;
                double lo = Math.min(h, nodeH[i]), hi = Math.max(h, nodeH[i]);
                if (obstruction(x, z, lo, hi + HEIGHT, Integer.MIN_VALUE) == Integer.MIN_VALUE) out.add(i);
            }
        }
        return out;
    }

    /**
     * Breadth-first search from {@code start}. Returns a parent per node: -1 unreached, the start
     * points to itself. Stops early once {@code goal} (or -1 for none) is reached.
     */
    public int[] search(int start, int goal) {
        int[] parent = new int[nodeH.length];
        Arrays.fill(parent, -1);
        parent[start] = start;
        ArrayDeque<Integer> queue = new ArrayDeque<>();
        queue.add(start);
        while (!queue.isEmpty()) {
            int n = queue.poll();
            if (n == goal) break;
            for (int m : moves(n)) {
                if (parent[m] != -1) continue;
                parent[m] = n;
                queue.add(m);
            }
        }
        return parent;
    }

    /** The path from the search start to {@code goal}, start first; empty when unreached. */
    public static List<Integer> path(int[] parent, int goal) {
        List<Integer> p = new ArrayList<>();
        if (goal < 0 || parent[goal] == -1) return p;
        for (int n = goal; ; n = parent[n]) {
            p.add(0, n);
            if (parent[n] == n) break;
        }
        return p;
    }

    public static int reachedCount(int[] parent) {
        int c = 0;
        for (int p : parent) if (p != -1) c++;
        return c;
    }

    /**
     * Explains why the place with feet at (tx, ty, tz) was not reached: the nearest reached node and
     * the first failing move from it toward the target.
     */
    public Diagnosis diagnose(int[] parent, int tx, int ty, int tz) {
        int best = -1;
        double bestD = Double.MAX_VALUE;
        for (int i = 0; i < parent.length; i++) {
            if (parent[i] == -1) continue;
            double d = Math.abs(x(i) - tx) + Math.abs(z(i) - tz) + Math.abs(nodeH[i] - ty);
            if (d < bestD) {
                bestD = d;
                best = i;
            }
        }
        if (best < 0) return new Diagnosis(-1, new Block("nothing reachable", null, null));
        int dxs = Integer.signum(tx - x(best)), dzs = Integer.signum(tz - z(best));
        List<Integer> dirs = new ArrayList<>();
        if (Math.abs(tx - x(best)) >= Math.abs(tz - z(best))) {
            if (dxs != 0) dirs.add(dxs > 0 ? 1 : 3);
            if (dzs != 0) dirs.add(dzs > 0 ? 2 : 0);
        } else {
            if (dzs != 0) dirs.add(dzs > 0 ? 2 : 0);
            if (dxs != 0) dirs.add(dxs > 0 ? 1 : 3);
        }
        for (int d : dirs) {
            Step s = step(best, d);
            if (s.node() < 0) return new Diagnosis(best, s.block());
        }
        if (dirs.isEmpty()) {
            int target = nodeAt(tx, ty, tz);
            if (target >= 0 && nodeProblem[target] != null) return new Diagnosis(best, nodeProblem[target]);
            String what = target < 0 ? "no standing place at that height" : "a different level of this column; no way up or down here";
            return new Diagnosis(best, new Block(what, new int[]{tx, ty, tz}, state(tx, ty, tz)));
        }
        return new Diagnosis(best, new Block("no route inside the box (a detour may leave it)", null, null));
    }
}
