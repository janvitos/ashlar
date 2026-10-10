// SPDX-License-Identifier: AGPL-3.0-or-later
package cc.wujm.ashlar.protect;

import cc.wujm.ashlar.engine.Region;

import java.util.ArrayList;
import java.util.List;

/**
 * Collects the write targets of one call and counts how many fall inside each protected region of
 * the target world. Boxes are tested against every region; a fill that writes only its shell
 * ({@code outline}) or its four sides ({@code walls}) is split into disjoint slabs first, so a
 * region inside a hollow room is not hit. Cells are prefiltered by the bounding box of the
 * candidate regions. With no region in the world every method returns at once.
 *
 * <p>Counts are per write target: a cell written by two operations of the same call counts twice.
 */
public final class ProtectionCheck {

    /** How a box writes: every cell, only its single-layer shell, or only its four vertical sides. */
    public enum Shape { SOLID, SHELL, SIDES }

    /** Writes of one call that land inside {@code region}, with the bounds of those writes. */
    public record Overlap(ProtectedRegion region, long cells, Region bounds) {
    }

    private final List<ProtectedRegion> regions;
    private final long[] counts;
    private final int[][] bounds;
    private final Region envelope;

    public ProtectionCheck(List<ProtectedRegion> regionsInWorld) {
        this.regions = List.copyOf(regionsInWorld);
        this.counts = new long[regions.size()];
        this.bounds = new int[regions.size()][];
        Region env = null;
        for (ProtectedRegion p : regions) {
            Region b = p.box();
            env = env == null ? b : new Region(Math.min(env.minX(), b.minX()), Math.min(env.minY(), b.minY()), Math.min(env.minZ(), b.minZ()),
                    Math.max(env.maxX(), b.maxX()), Math.max(env.maxY(), b.maxY()), Math.max(env.maxZ(), b.maxZ()));
        }
        this.envelope = env;
    }

    public boolean empty() {
        return regions.isEmpty();
    }

    public void box(Region r, Shape shape) {
        if (envelope == null || !overlaps(envelope, r)) return;
        switch (shape) {
            case SOLID -> solid(r);
            case SHELL -> {
                if ((long) r.maxY() - r.minY() < 2) {
                    solid(r);
                } else {
                    solid(new Region(r.minX(), r.minY(), r.minZ(), r.maxX(), r.minY(), r.maxZ()));
                    solid(new Region(r.minX(), r.maxY(), r.minZ(), r.maxX(), r.maxY(), r.maxZ()));
                    sides(r.minX(), r.minY() + 1, r.minZ(), r.maxX(), r.maxY() - 1, r.maxZ());
                }
            }
            case SIDES -> sides(r.minX(), r.minY(), r.minZ(), r.maxX(), r.maxY(), r.maxZ());
        }
    }

    public void cell(int x, int y, int z) {
        if (envelope == null || x < envelope.minX() || x > envelope.maxX() || y < envelope.minY() || y > envelope.maxY()
                || z < envelope.minZ() || z > envelope.maxZ()) return;
        for (int i = 0; i < regions.size(); i++) {
            if (regions.get(i).contains(x, y, z)) add(i, new Region(x, y, z, x, y, z), 1);
        }
    }

    public List<Overlap> overlaps() {
        List<Overlap> out = new ArrayList<>();
        for (int i = 0; i < regions.size(); i++) {
            if (counts[i] == 0) continue;
            int[] b = bounds[i];
            out.add(new Overlap(regions.get(i), counts[i], new Region(b[0], b[1], b[2], b[3], b[4], b[5])));
        }
        return out;
    }

    /** The four vertical sides as disjoint slabs; a box two or fewer cells wide is all sides. */
    private void sides(int minX, int minY, int minZ, int maxX, int maxY, int maxZ) {
        if ((long) maxX - minX < 2 || (long) maxZ - minZ < 2) {
            solid(new Region(minX, minY, minZ, maxX, maxY, maxZ));
            return;
        }
        solid(new Region(minX, minY, minZ, minX, maxY, maxZ));
        solid(new Region(maxX, minY, minZ, maxX, maxY, maxZ));
        solid(new Region(minX + 1, minY, minZ, maxX - 1, maxY, minZ));
        solid(new Region(minX + 1, minY, maxZ, maxX - 1, maxY, maxZ));
    }

    private void solid(Region r) {
        for (int i = 0; i < regions.size(); i++) {
            Region hit = regions.get(i).intersection(r);
            if (hit != null) add(i, hit, hit.volume());
        }
    }

    private void add(int i, Region hit, long n) {
        counts[i] = counts[i] > Long.MAX_VALUE - n ? Long.MAX_VALUE : counts[i] + n;
        int[] b = bounds[i];
        if (b == null) {
            bounds[i] = new int[]{hit.minX(), hit.minY(), hit.minZ(), hit.maxX(), hit.maxY(), hit.maxZ()};
            return;
        }
        b[0] = Math.min(b[0], hit.minX());
        b[1] = Math.min(b[1], hit.minY());
        b[2] = Math.min(b[2], hit.minZ());
        b[3] = Math.max(b[3], hit.maxX());
        b[4] = Math.max(b[4], hit.maxY());
        b[5] = Math.max(b[5], hit.maxZ());
    }

    private static boolean overlaps(Region a, Region b) {
        return a.minX() <= b.maxX() && a.maxX() >= b.minX() && a.minY() <= b.maxY() && a.maxY() >= b.minY()
                && a.minZ() <= b.maxZ() && a.maxZ() >= b.minZ();
    }
}
