// SPDX-License-Identifier: AGPL-3.0-or-later
package cc.wujm.ashlar.journal;

import cc.wujm.ashlar.engine.Region;

import java.util.List;

/** Changed cells of one journal entry: packed positions plus original and written palette indices. */
public record JournalCells(List<String> palette, long[] pos, int[] oldState, int[] newState) {

    public int size() {
        return pos.length;
    }

    /** Bounding box of every cell; {@code null} when empty. */
    public Region bounds() {
        if (pos.length == 0) return null;
        int minX = Integer.MAX_VALUE, minY = Integer.MAX_VALUE, minZ = Integer.MAX_VALUE;
        int maxX = Integer.MIN_VALUE, maxY = Integer.MIN_VALUE, maxZ = Integer.MIN_VALUE;
        for (long k : pos) {
            int x = JournalRecorder.x(k), y = JournalRecorder.y(k), z = JournalRecorder.z(k);
            minX = Math.min(minX, x); minY = Math.min(minY, y); minZ = Math.min(minZ, z);
            maxX = Math.max(maxX, x); maxY = Math.max(maxY, y); maxZ = Math.max(maxZ, z);
        }
        return new Region(minX, minY, minZ, maxX, maxY, maxZ);
    }

    /** Whether any cell lies inside {@code box}. */
    public boolean touches(Region box) {
        for (long k : pos) {
            int x = JournalRecorder.x(k), y = JournalRecorder.y(k), z = JournalRecorder.z(k);
            if (x >= box.minX() && x <= box.maxX() && y >= box.minY() && y <= box.maxY() && z >= box.minZ() && z <= box.maxZ())
                return true;
        }
        return false;
    }
}
