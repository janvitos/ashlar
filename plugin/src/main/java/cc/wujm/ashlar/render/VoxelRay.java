// SPDX-License-Identifier: AGPL-3.0-or-later
package cc.wujm.ashlar.render;

/**
 * 3D voxel traversal (Amanatides-Woo) against {@link BlockShapes} cuboids, so slabs, stairs, snow
 * layers, panes and fences only block where they really have shape. Pure Java, no Bukkit access.
 */
public final class VoxelRay {
    private VoxelRay() {}

    /** Block ids of a world view. {@link #UNKNOWN} marks a cell whose chunk was not loaded. */
    public interface Voxels {
        int UNKNOWN = -1;
        int AIR = 0;

        int id(int x, int y, int z);

        BlockShapes.Shape shape(int id);

        String state(int id);

        /** Lowest buildable y (inclusive). */
        int minY();

        /** Highest buildable y (inclusive). */
        int maxY();
    }

    /** Receives every shape surface along the ray, nearest first. Return false to stop. */
    public interface Visitor {
        boolean surface(int x, int y, int z, int id, double t, int axis, int sign);

        /** The ray entered an unknown (unloaded) cell at distance {@code t}; tracing stops. */
        void unknown(int x, int y, int z, double t);

        /** Called before a cell is tested; return false to stop (used by sightlines to end at the target). */
        default boolean enter(int x, int y, int z, double t) {
            return true;
        }
    }

    record Hit(double enter, double exit, int axis, int sign) {}

    static Hit intersect(double ox, double oy, double oz, double dx, double dy, double dz,
            double x0, double y0, double z0, double x1, double y1, double z1) {
        double lo = Double.NEGATIVE_INFINITY, hi = Double.POSITIVE_INFINITY;
        int axis = 1, sign = 1;
        for (int a = 0; a < 3; a++) {
            double p = a == 0 ? ox : a == 1 ? oy : oz, v = a == 0 ? dx : a == 1 ? dy : dz;
            double min = a == 0 ? x0 : a == 1 ? y0 : z0, max = a == 0 ? x1 : a == 1 ? y1 : z1;
            if (Math.abs(v) < 1e-12) {
                if (p < min || p > max) return null;
                continue;
            }
            double t0 = (min - p) / v, t1 = (max - p) / v;
            int s = v > 0 ? -1 : 1;
            if (t0 > t1) {
                double t = t0;
                t0 = t1;
                t1 = t;
            }
            if (t0 > lo) {
                lo = t0;
                axis = a;
                sign = s;
            }
            hi = Math.min(hi, t1);
            if (hi < lo) return null;
        }
        return new Hit(lo, hi, axis, sign);
    }

    /**
     * Walks from {@code (ox,oy,oz)} along the unit direction {@code (dx,dy,dz)} for at most
     * {@code maxT} blocks. Returns the number of cells visited.
     */
    public static long trace(Voxels voxels, double ox, double oy, double oz, double dx, double dy, double dz,
            double maxT, Visitor visitor) {
        int x = (int) Math.floor(ox), y = (int) Math.floor(oy), z = (int) Math.floor(oz);
        int sx = dx >= 0 ? 1 : -1, sy = dy >= 0 ? 1 : -1, sz = dz >= 0 ? 1 : -1;
        double tx = boundary(ox, dx, x, sx), ty = boundary(oy, dy, y, sy), tz = boundary(oz, dz, z, sz);
        double ix = Math.abs(dx) < 1e-12 ? Double.POSITIVE_INFINITY : Math.abs(1 / dx);
        double iy = Math.abs(dy) < 1e-12 ? Double.POSITIVE_INFINITY : Math.abs(1 / dy);
        double iz = Math.abs(dz) < 1e-12 ? Double.POSITIVE_INFINITY : Math.abs(1 / dz);
        double t = 0;
        long steps = 0;
        while (t <= maxT) {
            // Above the world nothing can be hit once the ray climbs; below it is the void.
            if (y > voxels.maxY() && dy >= 0) return steps;
            if (y < voxels.minY()) return steps;
            double next = Math.min(tx, Math.min(ty, tz));
            steps++;
            if (!visitor.enter(x, y, z, t)) return steps;
            if (y <= voxels.maxY()) {
                int id = voxels.id(x, y, z);
                if (id == Voxels.UNKNOWN) {
                    visitor.unknown(x, y, z, t);
                    return steps;
                }
                if (id != Voxels.AIR && !surfaces(voxels, id, x, y, z, ox, oy, oz, dx, dy, dz, t, Math.min(next, maxT), visitor)) {
                    return steps;
                }
            }
            t = next;
            // Move every tied axis so zero-width corner/edge crossings are not tested as extra cells.
            if (tx <= next + 1e-9) {
                x += sx;
                tx += ix;
            }
            if (ty <= next + 1e-9) {
                y += sy;
                ty += iy;
            }
            if (tz <= next + 1e-9) {
                z += sz;
                tz += iz;
            }
        }
        return steps;
    }

    private static boolean surfaces(Voxels voxels, int id, int x, int y, int z, double ox, double oy, double oz,
            double dx, double dy, double dz, double t, double next, Visitor visitor) {
        Hit closest = null;
        for (BlockShapes.Box b : voxels.shape(id).boxes()) {
            Hit hit = intersect(ox, oy, oz, dx, dy, dz, x + b.x0(), y + b.y0(), z + b.z0(), x + b.x1(), y + b.y1(), z + b.z1());
            if (hit != null && hit.exit() >= t - 1e-7 && hit.enter() <= next + 1e-7 && (closest == null || hit.enter() < closest.enter())) {
                closest = hit;
            }
        }
        return closest == null || visitor.surface(x, y, z, id, Math.max(t, closest.enter()), closest.axis(), closest.sign());
    }

    private static double boundary(double origin, double direction, int cell, int step) {
        return Math.abs(direction) < 1e-12 ? Double.POSITIVE_INFINITY : ((step > 0 ? cell + 1 : cell) - origin) / direction;
    }

    /** Unit look vector for Minecraft yaw/pitch in degrees (yaw 0 = south/+z, 90 = west; pitch 90 = straight down). */
    public static double[] look(double yaw, double pitch) {
        double y = Math.toRadians(yaw), p = Math.toRadians(pitch);
        return new double[]{-Math.sin(y) * Math.cos(p), -Math.sin(p), Math.cos(y) * Math.cos(p)};
    }

    /** Minecraft yaw/pitch (degrees) of the direction from one point to another. */
    public static double[] angles(double dx, double dy, double dz) {
        double h = Math.sqrt(dx * dx + dz * dz);
        double yaw = Math.toDegrees(Math.atan2(-dx, dz));
        double pitch = Math.toDegrees(Math.atan2(-dy, h));
        return new double[]{yaw, pitch};
    }
}
