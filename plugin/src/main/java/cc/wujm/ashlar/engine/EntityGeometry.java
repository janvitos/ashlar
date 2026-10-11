// SPDX-License-Identifier: AGPL-3.0-or-later
package cc.wujm.ashlar.engine;

import java.util.ArrayList;
import java.util.List;

/**
 * Facing and painting footprint math for decorative entities (Step 17). Pure Java.
 *
 * <p>Minecraft anchors a painting at one block: its centre sits half a block toward the facing's
 * counter-clockwise side for even widths and half a block up for even heights. Seen by someone
 * facing the painting, counter-clockwise of its facing is their right, so from the lower-left
 * cell the anchor is {@code floor((w-1)/2)} to the right and {@code floor((h-1)/2)} up.
 */
public final class EntityGeometry {
    private EntityGeometry() {}

    public static final List<String> HORIZONTAL = List.of("north", "east", "south", "west");
    public static final List<String> ALL = List.of("north", "east", "south", "west", "up", "down");

    /** Unit vector of a facing name. */
    public static int[] vector(String facing) {
        return switch (facing) {
            case "north" -> new int[]{0, 0, -1};
            case "south" -> new int[]{0, 0, 1};
            case "east" -> new int[]{1, 0, 0};
            case "west" -> new int[]{-1, 0, 0};
            case "up" -> new int[]{0, 1, 0};
            case "down" -> new int[]{0, -1, 0};
            default -> throw new IllegalArgumentException("unknown facing " + facing);
        };
    }

    /** Facing name of a unit vector. */
    public static String name(int dx, int dy, int dz) {
        if (dy > 0) return "up";
        if (dy < 0) return "down";
        if (dx > 0) return "east";
        if (dx < 0) return "west";
        return dz > 0 ? "south" : "north";
    }

    /** The viewer's right when looking at something that faces {@code facing} (counter-clockwise from above). */
    public static String right(String facing) {
        return switch (facing) {
            case "south" -> "east";
            case "east" -> "north";
            case "north" -> "west";
            case "west" -> "south";
            default -> throw new IllegalArgumentException("paintings face north, east, south or west, not " + facing);
        };
    }

    /** The anchor block Minecraft uses for a {@code w} x {@code h} painting with this lower-left cell. */
    public static int[] anchor(int[] lowerLeft, int w, int h, String facing) {
        int[] r = vector(right(facing));
        int a = (w - 1) / 2, b = (h - 1) / 2;
        return new int[]{lowerLeft[0] + r[0] * a, lowerLeft[1] + b, lowerLeft[2] + r[2] * a};
    }

    /** Lower-left cell from a lower-right one (a mirrored transform swaps the viewer's sides). */
    public static int[] lowerLeft(int[] cell, boolean fromRight, int w, String facing) {
        if (!fromRight) return cell.clone();
        int[] r = vector(right(facing));
        return new int[]{cell[0] - r[0] * (w - 1), cell[1], cell[2] - r[2] * (w - 1)};
    }

    /** Every cell a painting covers, lower row first, left to right. */
    public static List<int[]> cells(int[] lowerLeft, int w, int h, String facing) {
        int[] r = vector(right(facing));
        List<int[]> out = new ArrayList<>(w * h);
        for (int j = 0; j < h; j++) {
            for (int i = 0; i < w; i++) out.add(new int[]{lowerLeft[0] + r[0] * i, lowerLeft[1] + j, lowerLeft[2] + r[2] * i});
        }
        return out;
    }

    /** The supporting block behind (or under, or above) a cell. */
    public static int[] support(int[] cell, String facing) {
        int[] v = vector(facing);
        return new int[]{cell[0] - v[0], cell[1] - v[1], cell[2] - v[2]};
    }

    /** Lower-left cell of a painting from the inclusive block range its bounding box covers. */
    public static int[] lowerLeftFromRange(int[] min, int[] max, String facing) {
        int[] r = vector(right(facing));
        int x = r[0] > 0 ? min[0] : r[0] < 0 ? max[0] : min[0];
        int z = r[2] > 0 ? min[2] : r[2] < 0 ? max[2] : min[2];
        return new int[]{x, min[1], z};
    }

    /** Yaw (degrees, Minecraft: 0 south, 90 west) after mirroring then rotating clockwise by {@code rotation}. */
    public static float transformYaw(float yaw, int rotation, String mirror) {
        double rad = Math.toRadians(yaw);
        double vx = -Math.sin(rad), vz = Math.cos(rad);
        if (mirror.equals("x")) vx = -vx;
        if (mirror.equals("z")) vz = -vz;
        double rx = switch (rotation) { case 90 -> -vz; case 180 -> -vx; case 270 -> vz; default -> vx; };
        double rz = switch (rotation) { case 90 -> vx; case 180 -> -vz; case 270 -> -vx; default -> vz; };
        return (float) wrapYaw(Math.toDegrees(Math.atan2(-rx, rz)));
    }

    /** Angle in [-180, 180). */
    public static double wrapYaw(double a) {
        double r = ((a + 180) % 360 + 360) % 360 - 180;
        return Math.abs(r) < 1e-9 ? 0 : r;
    }
}
