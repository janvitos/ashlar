// SPDX-License-Identifier: AGPL-3.0-or-later
package cc.wujm.ashlar.tool.mc;

import cc.wujm.ashlar.engine.Region;
import cc.wujm.ashlar.engine.RegionData;
import cc.wujm.ashlar.tool.ToolArgError;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.UnaryOperator;

/**
 * Reference states over an {@code mc_diff} box: a palette of full block states and one dense palette
 * index per box cell ({@code index + 1}; {@code 0} = undeclared). Positions outside the box are counted
 * and skipped; a position declared twice is rejected.
 */
final class DiffReference {

    final Region box;
    final List<String> palette = new ArrayList<>();
    final int[] cells;
    private final Map<String, Integer> index = new HashMap<>();
    private final UnaryOperator<String> normalize;
    long declared;
    long outside;

    /** {@code normalize} validates a state and returns its full form (throws on invalid input). */
    DiffReference(Region box, UnaryOperator<String> normalize) {
        this.box = box;
        this.normalize = normalize;
        this.cells = new int[Math.toIntExact(box.volume())];
    }

    int boxIndex(int x, int y, int z) {
        int dx = box.maxX() - box.minX() + 1, dz = box.maxZ() - box.minZ() + 1;
        return ((y - box.minY()) * dz + (z - box.minZ())) * dx + (x - box.minX());
    }

    boolean contains(int x, int y, int z) {
        return x >= box.minX() && x <= box.maxX() && y >= box.minY() && y <= box.maxY() && z >= box.minZ() && z <= box.maxZ();
    }

    void add(int x, int y, int z, String state) {
        if (!contains(x, y, z)) {
            outside++;
            return;
        }
        int i = boxIndex(x, y, z);
        if (cells[i] != 0) throw new ToolArgError("reference declares [" + x + "," + y + "," + z + "] more than once");
        cells[i] = paletteIndex(state) + 1;
        declared++;
    }

    private int paletteIndex(String state) {
        Integer p = index.get(state);
        if (p != null) return p;
        String full = normalize.apply(state);
        Integer existing = index.get(full);
        if (existing == null) {
            existing = palette.size();
            palette.add(full);
            index.put(full, existing);
        }
        index.put(state, existing);
        return existing;
    }

    /** Every box cell from a snapshot whose region contains the box. */
    static DiffReference fromRegionData(Region box, RegionData data, String what) {
        Region r = data.region();
        if (box.minX() < r.minX() || box.maxX() > r.maxX() || box.minY() < r.minY() || box.maxY() > r.maxY()
                || box.minZ() < r.minZ() || box.maxZ() > r.maxZ()) {
            throw new ToolArgError(what + " covers [" + r.minX() + "," + r.minY() + "," + r.minZ() + "]..[" + r.maxX() + ","
                    + r.maxY() + "," + r.maxZ() + "], which does not contain the diff box; use a box inside it");
        }
        DiffReference ref = new DiffReference(box, UnaryOperator.identity());
        ref.palette.addAll(data.palette());
        int[] runIndex = data.runIndex(), runLength = data.runLength();
        int dx = r.maxX() - r.minX() + 1, dz = r.maxZ() - r.minZ() + 1;
        long cell = 0;
        for (int run = 0; run < runIndex.length; run++) {
            for (int k = 0; k < runLength[run]; k++, cell++) {
                int x = r.minX() + (int) (cell % dx);
                int z = r.minZ() + (int) ((cell / dx) % dz);
                int y = r.minY() + (int) (cell / ((long) dx * dz));
                if (ref.contains(x, y, z)) ref.cells[ref.boxIndex(x, y, z)] = runIndex[run] + 1;
            }
        }
        ref.declared = box.volume();
        return ref;
    }
}
