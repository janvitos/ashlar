// SPDX-License-Identifier: AGPL-3.0-or-later
package cc.wujm.ashlar.tool.mc;

import cc.wujm.ashlar.engine.Region;
import cc.wujm.ashlar.tool.ToolArgError;

import java.util.ArrayList;
import java.util.List;

/**
 * Splits an {@code mc_diff} box into live sub-reads of at most {@code maxRead} cells each, margin
 * included. Whole y-slabs are used when one layer fits; otherwise single layers are cut into z-bands.
 * Either way the parts come out in y,z,x order, so cells fed in part order keep the global order.
 */
final class DiffPlan {

    /** {@code inner}: cells compared; {@code outer}: cells read (inner plus margin, clamped to the world height). */
    record Part(Region inner, Region outer) {
    }

    private DiffPlan() {
    }

    static List<Part> split(Region box, long maxRead, int margin, int worldMinY, int worldMaxY) {
        long w = (long) box.maxX() - box.minX() + 1 + 2L * margin;
        long d = (long) box.maxZ() - box.minZ() + 1 + 2L * margin;
        long layer = w * d;
        List<Part> parts = new ArrayList<>();
        if (layer * (1 + 2L * margin) <= maxRead) {
            int h = (int) Math.min((long) box.maxY() - box.minY() + 1, maxRead / layer - 2L * margin);
            for (int y = box.minY(); y <= box.maxY(); y += h) {
                Region inner = new Region(box.minX(), y, box.minZ(), box.maxX(), Math.min(box.maxY(), y + h - 1), box.maxZ());
                parts.add(new Part(inner, outer(inner, margin, worldMinY, worldMaxY)));
                if (inner.maxY() == box.maxY()) break;
            }
            return parts;
        }
        long band = maxRead / (w * (1 + 2L * margin)) - 2L * margin;
        if (band < 1) {
            throw new ToolArgError("mc_diff box is too wide: one x row (" + w + " cells with margin) exceeds limits.max-read-volume "
                    + maxRead + "; narrow the x range or split the box");
        }
        for (int y = box.minY(); y <= box.maxY(); y++) {
            for (long z = box.minZ(); z <= box.maxZ(); z += band) {
                Region inner = new Region(box.minX(), y, (int) z, box.maxX(), y, (int) Math.min(box.maxZ(), z + band - 1));
                parts.add(new Part(inner, outer(inner, margin, worldMinY, worldMaxY)));
            }
            if (y == box.maxY()) break;
        }
        return parts;
    }

    private static Region outer(Region r, int m, int worldMinY, int worldMaxY) {
        return new Region(r.minX() - m, Math.max(worldMinY, r.minY() - m), r.minZ() - m,
                r.maxX() + m, Math.min(worldMaxY, r.maxY() + m), r.maxZ() + m);
    }
}
