// SPDX-License-Identifier: AGPL-3.0-or-later
package cc.wujm.ashlar.render;

import cc.wujm.ashlar.engine.Region;
import cc.wujm.ashlar.engine.RegionData;

import java.util.Set;

/**
 * Per-cell comparison of a live region read with a stored snapshot, for {@code mc_render compareTo} (Step 20).
 * Marks follow the region's y/z/x order. Pure Java; runs off the main thread.
 */
public final class SnapshotDiff {
    private SnapshotDiff() {}

    public static final byte SAME = 0, CHANGED = 1, ADDED = 2, REMOVED = 3, OUTSIDE = 4;
    private static final Set<String> AIR = Set.of("air", "cave_air", "void_air");

    /** Marks every cell of {@code now} against {@code before}; cells outside the snapshot's box are {@link #OUTSIDE}. */
    public static byte[] compute(RegionData now, RegionData before) {
        Region r = now.region(), s = before.region();
        int[] is = expand(now);
        int dx = r.maxX() - r.minX() + 1, dz = r.maxZ() - r.minZ() + 1;
        int sx = s.maxX() - s.minX() + 1, sz = s.maxZ() - s.minZ() + 1;
        // Snapshot palette index for each cell of the render region it covers; -1 elsewhere.
        int[] was = new int[is.length];
        java.util.Arrays.fill(was, -1);
        long pos = 0;
        int[] ids = before.runIndex(), lengths = before.runLength();
        for (int k = 0; k < ids.length; k++) {
            if (ids[k] < 0 || ids[k] >= before.palette().size() || lengths[k] <= 0) throw new IllegalArgumentException("invalid snapshot runs");
            for (long p = pos; p < pos + lengths[k]; p++) {
                int x = s.minX() + (int) (p % sx), z = s.minZ() + (int) ((p / sx) % sz), y = s.minY() + (int) (p / ((long) sx * sz));
                if (x < r.minX() || x > r.maxX() || y < r.minY() || y > r.maxY() || z < r.minZ() || z > r.maxZ()) continue;
                was[((y - r.minY()) * dz + (z - r.minZ())) * dx + (x - r.minX())] = ids[k];
            }
            pos += lengths[k];
        }
        if (pos != before.volume()) throw new IllegalArgumentException("incomplete snapshot runs");
        byte[] out = new byte[is.length];
        for (int i = 0; i < is.length; i++) {
            out[i] = was[i] < 0 ? OUTSIDE : mark(before.palette().get(was[i]), now.palette().get(is[i]));
        }
        return out;
    }

    static byte mark(String before, String now) {
        if (before.equals(now)) return SAME;
        boolean a = air(before), b = air(now);
        if (a && b) return SAME;
        if (a) return ADDED;
        if (b) return REMOVED;
        return CHANGED;
    }

    static boolean air(String state) {
        return AIR.contains(BlockShapes.material(state));
    }

    /** Palette index per cell. */
    private static int[] expand(RegionData d) {
        long v = d.volume();
        if (v > 200_000) throw new IllegalArgumentException("region of " + v + " cells is too large to compare (at most 200000)");
        int[] out = new int[(int) v];
        int pos = 0;
        int[] ids = d.runIndex(), lengths = d.runLength();
        for (int i = 0; i < ids.length; i++) {
            if (lengths[i] <= 0 || lengths[i] > out.length - pos) throw new IllegalArgumentException("invalid region runs");
            if (ids[i] < 0 || ids[i] >= d.palette().size()) throw new IllegalArgumentException("invalid palette index");
            java.util.Arrays.fill(out, pos, pos + lengths[i], ids[i]);
            pos += lengths[i];
        }
        if (pos != out.length) throw new IllegalArgumentException("incomplete region runs");
        return out;
    }
}
