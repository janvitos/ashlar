// SPDX-License-Identifier: AGPL-3.0-or-later
package cc.wujm.ashlar.render;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Pinhole camera at an eye position, using Minecraft's yaw/pitch convention (as F3 shows them:
 * yaw 0 = south, 90 = west, 180 = north, -90 = east; pitch -90 = up, 90 = down). {@code fov} is
 * the vertical field of view in degrees, like the game's FOV setting. Pure Java.
 */
public record FirstPersonCamera(double x, double y, double z, double yaw, double pitch, double fov, int distance,
        int width, int height) {

    public static final int MAX_DISTANCE = 256;
    public static final long MAX_PIXELS = 1_000_000;

    public FirstPersonCamera {
        if (!Double.isFinite(x) || !Double.isFinite(y) || !Double.isFinite(z) || Math.abs(x) >= 30_000_000 || Math.abs(z) >= 30_000_000) {
            throw new IllegalArgumentException("eye must be finite and inside horizontal +/-30000000");
        }
        if (!Double.isFinite(yaw) || yaw < -360 || yaw > 360) throw new IllegalArgumentException("yaw must be in [-360,360]");
        if (!Double.isFinite(pitch) || pitch < -90 || pitch > 90) throw new IllegalArgumentException("pitch must be in [-90,90]");
        if (!Double.isFinite(fov) || fov < 10 || fov > 110) throw new IllegalArgumentException("fov must be in [10,110]");
        if (distance < 8 || distance > MAX_DISTANCE) throw new IllegalArgumentException("distance must be 8-" + MAX_DISTANCE);
        if (width < 64 || height < 64 || width > 1920 || height > 1080) throw new IllegalArgumentException("width must be 64-1920 and height 64-1080");
        if ((long) width * height > MAX_PIXELS) throw new IllegalArgumentException("width*height must be at most " + MAX_PIXELS);
    }

    /** Forward, right and up unit vectors (each {x,y,z}). */
    public double[][] basis() {
        double[] f = VoxelRay.look(yaw, pitch);
        double yr = Math.toRadians(yaw);
        double[] r = {-Math.cos(yr), 0, -Math.sin(yr)};
        double[] u = {r[1] * f[2] - r[2] * f[1], r[2] * f[0] - r[0] * f[2], r[0] * f[1] - r[1] * f[0]};
        return new double[][]{f, r, u};
    }

    /** Unit ray through the center of pixel (px, py); py grows downward. */
    public double[] ray(double[][] basis, double px, double py) {
        double tv = Math.tan(Math.toRadians(fov) / 2), th = tv * width / height;
        double sx = (2 * (px + .5) / width - 1) * th, sy = (1 - 2 * (py + .5) / height) * tv;
        double[] f = basis[0], r = basis[1], u = basis[2];
        double dx = f[0] + r[0] * sx + u[0] * sy, dy = f[1] + r[1] * sx + u[1] * sy, dz = f[2] + r[2] * sx + u[2] * sy;
        double n = Math.sqrt(dx * dx + dy * dy + dz * dz);
        return new double[]{dx / n, dy / n, dz / n};
    }

    /**
     * Chunk columns (packed with {@link #pack}) that rays of this camera can reach within
     * {@code distance}: every chunk meeting the x/z convex hull of the eye and a 65x65 grid of ray
     * end points, expanded by one block.
     */
    public Set<Long> chunks() {
        double[][] b = basis();
        List<double[]> outline = new ArrayList<>();
        outline.add(new double[]{x, z});
        // A full grid, not just the border: a ray inside the image can reach further horizontally than
        // the border rays of its column (e.g. the horizon ray between an upward and a downward edge).
        int n = 64;
        for (int i = 0; i <= n; i++) {
            for (int j = 0; j <= n; j++) {
                double[] d = ray(b, i * (double) width / n - .5, j * (double) height / n - .5);
                outline.add(new double[]{x + d[0] * distance, z + d[2] * distance});
            }
        }
        // Looking steeply up or down, the horizontal footprint wraps around the eye: use the full circle.
        boolean wraps = Math.abs(pitch) + fov / 2 >= 89.9;
        List<double[]> hull = wraps ? circle(x, z, distance) : hull(outline);
        double minX = Double.POSITIVE_INFINITY, maxX = -minX, minZ = minX, maxZ = -minX;
        for (double[] p : hull) {
            minX = Math.min(minX, p[0]);
            maxX = Math.max(maxX, p[0]);
            minZ = Math.min(minZ, p[1]);
            maxZ = Math.max(maxZ, p[1]);
        }
        Set<Long> out = new LinkedHashSet<>();
        int cx0 = Math.floorDiv((int) Math.floor(minX), 16) - 1, cx1 = Math.floorDiv((int) Math.floor(maxX), 16) + 1;
        int cz0 = Math.floorDiv((int) Math.floor(minZ), 16) - 1, cz1 = Math.floorDiv((int) Math.floor(maxZ), 16) + 1;
        for (int cx = cx0; cx <= cx1; cx++) {
            for (int cz = cz0; cz <= cz1; cz++) {
                // Expanded by one block so rays grazing a chunk corner still find it.
                if (squareMeetsPolygon(cx * 16 - 1, cz * 16 - 1, cx * 16 + 17, cz * 16 + 17, hull)) out.add(pack(cx, cz));
            }
        }
        return out;
    }

    public static long pack(int cx, int cz) {
        return ((long) cx << 32) | (cz & 0xFFFFFFFFL);
    }

    public static int chunkX(long packed) {
        return (int) (packed >> 32);
    }

    public static int chunkZ(long packed) {
        return (int) packed;
    }

    private static List<double[]> circle(double cx, double cz, double r) {
        List<double[]> c = new ArrayList<>();
        // A 64-gon circumscribing the circle.
        double R = r / Math.cos(Math.PI / 64);
        for (int i = 0; i < 64; i++) {
            double a = 2 * Math.PI * i / 64;
            c.add(new double[]{cx + R * Math.cos(a), cz + R * Math.sin(a)});
        }
        return c;
    }

    /** Monotone-chain convex hull, counter-clockwise. */
    static List<double[]> hull(List<double[]> pts) {
        List<double[]> p = new ArrayList<>(pts);
        p.sort((a, b) -> a[0] != b[0] ? Double.compare(a[0], b[0]) : Double.compare(a[1], b[1]));
        double[][] h = new double[p.size() * 2][];
        int k = 0;
        for (double[] q : p) {
            while (k >= 2 && cross(h[k - 2], h[k - 1], q) <= 0) k--;
            h[k++] = q;
        }
        for (int i = p.size() - 2, lo = k + 1; i >= 0; i--) {
            double[] q = p.get(i);
            while (k >= lo && cross(h[k - 2], h[k - 1], q) <= 0) k--;
            h[k++] = q;
        }
        List<double[]> out = new ArrayList<>();
        for (int i = 0; i < Math.max(1, k - 1); i++) out.add(h[i]);
        return out;
    }

    private static double cross(double[] o, double[] a, double[] b) {
        return (a[0] - o[0]) * (b[1] - o[1]) - (a[1] - o[1]) * (b[0] - o[0]);
    }

    /** Separating-axis test between an axis-aligned square and a convex polygon. */
    static boolean squareMeetsPolygon(double x0, double z0, double x1, double z1, List<double[]> poly) {
        double[][] sq = {{x0, z0}, {x1, z0}, {x1, z1}, {x0, z1}};
        if (poly.size() < 3) {
            for (double[] p : poly) if (p[0] >= x0 && p[0] <= x1 && p[1] >= z0 && p[1] <= z1) return true;
            return false;
        }
        List<double[]> axes = new ArrayList<>(List.of(new double[]{1, 0}, new double[]{0, 1}));
        for (int i = 0; i < poly.size(); i++) {
            double[] a = poly.get(i), b = poly.get((i + 1) % poly.size());
            axes.add(new double[]{-(b[1] - a[1]), b[0] - a[0]});
        }
        for (double[] ax : axes) {
            double pa = Double.POSITIVE_INFINITY, pb = -pa, sa = pa, sb = -pa;
            for (double[] p : poly) {
                double d = p[0] * ax[0] + p[1] * ax[1];
                pa = Math.min(pa, d);
                pb = Math.max(pb, d);
            }
            for (double[] p : sq) {
                double d = p[0] * ax[0] + p[1] * ax[1];
                sa = Math.min(sa, d);
                sb = Math.max(sb, d);
            }
            if (pb < sa || sb < pa) return false;
        }
        return true;
    }
}
