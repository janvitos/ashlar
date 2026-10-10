// SPDX-License-Identifier: AGPL-3.0-or-later
package cc.wujm.ashlar.render;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Line-of-sight tests from an eye position, pure Java over {@link VoxelRay}. A cell blocks sight when
 * its shape is mostly opaque (opacity at least {@link #BLOCKING_OPACITY}: glass, panes, iron bars and
 * water do not block, leaves do) and it matches no ignore pattern. Only the real cuboids of partial
 * blocks count, so a bottom slab does not hide what is above its top face.
 */
public final class Sightline {
    private Sightline() {}

    public static final double BLOCKING_OPACITY = .6;
    public static final int MAX_TARGETS = 256;

    public enum Status { VISIBLE, BLOCKED, UNKNOWN }

    /** First blocking cell, or the unknown cell where tracing stopped. */
    public record Result(int[] target, Status status, int[] at, String state, double distance) {}

    /**
     * An ignore pattern: a block id (namespace optional, {@code *} wildcard) with optional
     * {@code [property=value]} or numeric {@code [property<=n]} / {@code >=} / {@code <} / {@code >}
     * conditions, comma separated, e.g. {@code snow[layers<=3]} or {@code *_leaves}.
     */
    public record Ignore(Pattern id, List<String[]> conditions) {
        private static final Pattern SYNTAX = Pattern.compile("([a-z0-9_:*./-]+)(?:\\[([^\\]]*)])?");
        private static final Pattern COND = Pattern.compile("([a-z0-9_]+)(<=|>=|=|<|>)([a-z0-9_]+)");

        public static Ignore parse(String raw) {
            Matcher m = SYNTAX.matcher(raw.trim().toLowerCase(Locale.ROOT));
            if (!m.matches()) throw new IllegalArgumentException("ignore pattern '" + raw + "' must look like name or name[prop<=n,...]");
            String name = m.group(1).replace("minecraft:", "");
            if (name.isEmpty() || name.chars().allMatch(c -> c == '*')) throw new IllegalArgumentException("ignore pattern '" + raw + "' must name a block");
            List<String[]> conds = new ArrayList<>();
            if (m.group(2) != null) {
                for (String c : m.group(2).split(",")) {
                    Matcher cm = COND.matcher(c.trim());
                    if (!cm.matches()) throw new IllegalArgumentException("ignore condition '" + c + "' must be prop=value or prop<=n");
                    if (!cm.group(2).equals("=") && !cm.group(3).matches("-?\\d+")) {
                        throw new IllegalArgumentException("ignore condition '" + c + "' compares a number");
                    }
                    conds.add(new String[]{cm.group(1), cm.group(2), cm.group(3)});
                }
            }
            return new Ignore(Pattern.compile(Pattern.quote(name).replace("*", "\\E.*\\Q")), List.copyOf(conds));
        }

        public boolean matches(String state) {
            if (!id.matcher(BlockShapes.material(state)).matches()) return false;
            Map<String, String> p = BlockShapes.properties(state);
            for (String[] c : conditions) {
                String v = p.get(c[0]);
                if (v == null) return false;
                if (c[1].equals("=")) {
                    if (!v.equals(c[2])) return false;
                    continue;
                }
                if (!v.matches("-?\\d+")) return false;
                long a = Long.parseLong(v), b = Long.parseLong(c[2]);
                boolean ok = switch (c[1]) {
                    case "<=" -> a <= b;
                    case ">=" -> a >= b;
                    case "<" -> a < b;
                    default -> a > b;
                };
                if (!ok) return false;
            }
            return true;
        }
    }

    static boolean blocks(VoxelRay.Voxels v, int id, List<Ignore> ignore) {
        if (v.shape(id).opacity() < BLOCKING_OPACITY) return false;
        String state = v.state(id);
        for (Ignore i : ignore) if (i.matches(state)) return false;
        return true;
    }

    /**
     * Can the eye see block {@code target}? Aims at its center and at the center of each face turned
     * toward the eye (a ground block's center is usually hidden by its neighbours even when its top
     * is in plain view); visible when any of those lines is clear. Otherwise the center line's
     * result. The target's own cell never blocks.
     */
    public static Result target(VoxelRay.Voxels v, double ex, double ey, double ez, int[] target, List<Ignore> ignore) {
        Result center = line(v, ex, ey, ez, target, target[0] + .5, target[1] + .5, target[2] + .5, ignore);
        if (center.status() == Status.VISIBLE) return center;
        double[] eye = {ex, ey, ez};
        for (int axis = 0; axis < 3; axis++) {
            double lo = target[axis], hi = target[axis] + 1.0;
            if (eye[axis] > lo && eye[axis] < hi) continue;
            double[] p = {target[0] + .5, target[1] + .5, target[2] + .5};
            p[axis] = eye[axis] <= lo ? lo + 1e-3 : hi - 1e-3;
            Result face = line(v, ex, ey, ez, target, p[0], p[1], p[2], ignore);
            if (face.status() == Status.VISIBLE) return face;
        }
        return center;
    }

    private static Result line(VoxelRay.Voxels v, double ex, double ey, double ez, int[] target, double tx, double ty, double tz,
            List<Ignore> ignore) {
        double dx = tx - ex, dy = ty - ey, dz = tz - ez, len = Math.sqrt(dx * dx + dy * dy + dz * dz);
        double dist = Math.round(len * 100) / 100.0;
        if (len < 1e-9) return new Result(target, Status.VISIBLE, null, null, 0);
        Result[] out = new Result[1];
        VoxelRay.trace(v, ex, ey, ez, dx / len, dy / len, dz / len, len, new VoxelRay.Visitor() {
            @Override
            public boolean enter(int x, int y, int z, double t) {
                return !(x == target[0] && y == target[1] && z == target[2]);
            }

            @Override
            public boolean surface(int x, int y, int z, int id, double t, int axis, int sign) {
                if (!blocks(v, id, ignore)) return true;
                out[0] = new Result(target, Status.BLOCKED, new int[]{x, y, z}, v.state(id), Math.round(t * 100) / 100.0);
                return false;
            }

            @Override
            public void unknown(int x, int y, int z, double t) {
                out[0] = new Result(target, Status.UNKNOWN, new int[]{x, y, z}, null, Math.round(t * 100) / 100.0);
            }
        });
        return out[0] != null ? out[0] : new Result(target, Status.VISIBLE, null, null, dist);
    }

    /** One ray of a cone: the first blocking cell within {@code maxT}, or null for none. */
    public record ConeHit(int[] at, String state, double distance, boolean unknown) {}

    /** A {@code rays} x {@code rays} grid of first hits over a square field of view, rows top to bottom. */
    public static ConeHit[][] cone(VoxelRay.Voxels v, double ex, double ey, double ez, double yaw, double pitch, double fov,
            int rays, double maxT, List<Ignore> ignore) {
        FirstPersonCamera cam = new FirstPersonCamera(ex, ey, ez, yaw, pitch, fov, (int) maxT, 64, 64);
        double[][] basis = cam.basis();
        ConeHit[][] grid = new ConeHit[rays][rays];
        for (int r = 0; r < rays; r++) {
            for (int c = 0; c < rays; c++) {
                double[] d = cam.ray(basis, (c + .5) * 64.0 / rays - .5, (r + .5) * 64.0 / rays - .5);
                ConeHit[] hit = new ConeHit[1];
                VoxelRay.trace(v, ex, ey, ez, d[0], d[1], d[2], maxT, new VoxelRay.Visitor() {
                    @Override
                    public boolean surface(int x, int y, int z, int id, double t, int axis, int sign) {
                        if (!blocks(v, id, ignore)) return true;
                        hit[0] = new ConeHit(new int[]{x, y, z}, v.state(id), Math.round(t * 100) / 100.0, false);
                        return false;
                    }

                    @Override
                    public void unknown(int x, int y, int z, double t) {
                        hit[0] = new ConeHit(new int[]{x, y, z}, null, Math.round(t * 100) / 100.0, true);
                    }
                });
                grid[r][c] = hit[0];
            }
        }
        return grid;
    }
}
