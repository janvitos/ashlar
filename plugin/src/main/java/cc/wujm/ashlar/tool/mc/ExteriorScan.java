// SPDX-License-Identifier: AGPL-3.0-or-later
package cc.wujm.ashlar.tool.mc;

import cc.wujm.ashlar.tool.text.BlockGrid;

import java.util.ArrayList;
import java.util.BitSet;
import java.util.Comparator;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * {@code mc_inspect format:"exterior"}: what a finished build shows to the outside. Air is flooded
 * in from the box's four sides and top (the bottom layer counts as ground); a non-air block next
 * to that air, or on a side or the top of the box, is exterior. Also reports air sealed off from
 * outside and detached pieces: groups of non-air blocks (face-connected) that do not reach the
 * bottom layer, such as a stray block or leftover scaffolding. Glass and other see-through blocks
 * stop the flood like any other block.
 */
final class ExteriorScan {

    static final int LISTED_MATERIALS = 12;
    static final int LISTED_PIECES = 10;

    private ExteriorScan() {
    }

    record Piece(int cells, String block, int[] min, int[] max) {
    }

    record Result(long nonAir, long exterior, Map<String, Long> exteriorMaterials, long enclosedAir, int pieceCount, List<Piece> pieces) {
    }

    static boolean isAir(String state) {
        String id = state.startsWith("minecraft:") ? state.substring(10) : state;
        return id.equals("air") || id.equals("cave_air") || id.equals("void_air");
    }

    /** Block id without the namespace or properties: "stone_brick_stairs". */
    static String material(String state) {
        String id = state.startsWith("minecraft:") ? state.substring(10) : state;
        int b = id.indexOf('[');
        return b < 0 ? id : id.substring(0, b);
    }

    static Result scan(BlockGrid grid) {
        int dx = grid.size().dx(), dy = grid.size().dy(), dz = grid.size().dz();
        int volume = grid.volume();
        int layer = dx * dz;
        String[] cells = new String[volume];
        Iterator<String> it = grid.iterator();
        for (int i = 0; i < volume; i++) cells[i] = it.next();
        BitSet air = new BitSet(volume);
        for (int i = 0; i < volume; i++) if (isAir(cells[i])) air.set(i);

        // Open air: reachable from the sides or the top.
        BitSet open = new BitSet(volume);
        int[] queue = new int[volume];
        int head = 0, tail = 0;
        for (int i = air.nextSetBit(0); i >= 0; i = air.nextSetBit(i + 1)) {
            int x = i % dx, z = (i / dx) % dz, y = i / layer;
            if (x == 0 || x == dx - 1 || z == 0 || z == dz - 1 || y == dy - 1) {
                open.set(i);
                queue[tail++] = i;
            }
        }
        while (head < tail) {
            int i = queue[head++];
            int x = i % dx, z = (i / dx) % dz, y = i / layer;
            for (int j : neighbours(i, x, y, z, dx, dy, dz, layer)) {
                if (j >= 0 && air.get(j) && !open.get(j)) {
                    open.set(j);
                    queue[tail++] = j;
                }
            }
        }

        long nonAir = volume - air.cardinality();
        long exterior = 0;
        Map<String, Long> materials = new HashMap<>();
        for (int i = air.nextClearBit(0); i < volume; i = air.nextClearBit(i + 1)) {
            int x = i % dx, z = (i / dx) % dz, y = i / layer;
            boolean seen = x == 0 || x == dx - 1 || z == 0 || z == dz - 1 || y == dy - 1;
            if (!seen) {
                for (int j : neighbours(i, x, y, z, dx, dy, dz, layer)) {
                    if (j >= 0 && open.get(j)) {
                        seen = true;
                        break;
                    }
                }
            }
            if (seen) {
                exterior++;
                materials.merge(material(cells[i]), 1L, Long::sum);
            }
        }
        BitSet enclosed = (BitSet) air.clone();
        enclosed.andNot(open);

        // Detached pieces: face-connected non-air groups that never reach the bottom layer.
        BitSet solid = new BitSet(volume);
        solid.set(0, volume);
        solid.andNot(air);
        BitSet grounded = new BitSet(volume);
        head = 0;
        tail = 0;
        for (int i = solid.nextSetBit(0); i >= 0 && i < layer; i = solid.nextSetBit(i + 1)) {
            grounded.set(i);
            queue[tail++] = i;
        }
        while (head < tail) {
            int i = queue[head++];
            for (int j : neighbours(i, i % dx, i / layer, (i / dx) % dz, dx, dy, dz, layer)) {
                if (j >= 0 && solid.get(j) && !grounded.get(j)) {
                    grounded.set(j);
                    queue[tail++] = j;
                }
            }
        }
        BitSet loose = (BitSet) solid.clone();
        loose.andNot(grounded);
        List<Piece> pieces = new ArrayList<>();
        int[] o = grid.bounds().from();
        for (int s = loose.nextSetBit(0); s >= 0; s = loose.nextSetBit(s + 1)) {
            loose.clear(s);
            head = 0;
            tail = 0;
            queue[tail++] = s;
            int[] b = {Integer.MAX_VALUE, Integer.MAX_VALUE, Integer.MAX_VALUE, Integer.MIN_VALUE, Integer.MIN_VALUE, Integer.MIN_VALUE};
            Map<String, Integer> blocks = new HashMap<>();
            while (head < tail) {
                int i = queue[head++];
                int x = i % dx, z = (i / dx) % dz, y = i / layer;
                b[0] = Math.min(b[0], x); b[1] = Math.min(b[1], y); b[2] = Math.min(b[2], z);
                b[3] = Math.max(b[3], x); b[4] = Math.max(b[4], y); b[5] = Math.max(b[5], z);
                blocks.merge(material(cells[i]), 1, Integer::sum);
                for (int j : neighbours(i, x, y, z, dx, dy, dz, layer)) {
                    if (j >= 0 && loose.get(j)) {
                        loose.clear(j);
                        queue[tail++] = j;
                    }
                }
            }
            String main = blocks.entrySet().stream().max(Map.Entry.<String, Integer>comparingByValue()
                    .thenComparing(Map.Entry.comparingByKey(Comparator.reverseOrder()))).orElseThrow().getKey();
            pieces.add(new Piece(tail, blocks.size() == 1 ? main : main + " and " + (blocks.size() - 1) + " other block(s)",
                    new int[] {o[0] + b[0], o[1] + b[1], o[2] + b[2]}, new int[] {o[0] + b[3], o[1] + b[4], o[2] + b[5]}));
        }
        // Smallest first: a stray block matters more than a deliberately floating part.
        pieces.sort(Comparator.comparingInt(Piece::cells));
        int pieceCount = pieces.size();
        List<Piece> listed = pieces.subList(0, Math.min(LISTED_PIECES, pieceCount));
        return new Result(nonAir, exterior, materials, enclosed.cardinality(), pieceCount, List.copyOf(listed));
    }

    /** The six face neighbours as flat indices, or -1 outside the box. */
    private static int[] neighbours(int i, int x, int y, int z, int dx, int dy, int dz, int layer) {
        return new int[] {
                x > 0 ? i - 1 : -1, x < dx - 1 ? i + 1 : -1,
                z > 0 ? i - dx : -1, z < dz - 1 ? i + dx : -1,
                y > 0 ? i - layer : -1, y < dy - 1 ? i + layer : -1};
    }

    static String text(Result r, BlockGrid grid, String worldName) {
        int[] f = grid.bounds().from(), t = grid.bounds().to();
        List<String> out = new ArrayList<>();
        out.add(String.format(Locale.ROOT, "Exterior of %s [%d,%d,%d]..[%d,%d,%d] (air reached from the box's sides and top; the bottom layer counts as ground):",
                worldName, f[0], f[1], f[2], t[0], t[1], t[2]));
        out.add(String.format(Locale.ROOT, "%d of %d non-air blocks are seen from outside.", r.exterior(), r.nonAir()));
        if (r.exterior() > 0) {
            List<Map.Entry<String, Long>> sorted = new ArrayList<>(r.exteriorMaterials().entrySet());
            sorted.sort(Map.Entry.<String, Long>comparingByValue().reversed().thenComparing(Map.Entry.comparingByKey()));
            StringBuilder sb = new StringBuilder("Exterior materials: ");
            long rest = 0;
            for (int i = 0; i < sorted.size(); i++) {
                Map.Entry<String, Long> e = sorted.get(i);
                if (i < LISTED_MATERIALS) {
                    if (i > 0) sb.append(", ");
                    sb.append(e.getKey()).append(' ').append(e.getValue())
                            .append(String.format(Locale.ROOT, " (%.1f%%)", 100.0 * e.getValue() / r.exterior()));
                } else {
                    rest += e.getValue();
                }
            }
            if (sorted.size() > LISTED_MATERIALS) sb.append(", ").append(sorted.size() - LISTED_MATERIALS).append(" other(s) ").append(rest);
            out.add(sb.append('.').toString());
        }
        out.add(r.enclosedAir() == 0 ? "Enclosed air: none." : "Enclosed air: " + r.enclosedAir() + " cells sealed from outside (rooms, or gaps inside walls).");
        if (r.pieceCount() == 0) {
            out.add("Detached pieces: none (every block connects to the bottom layer through other blocks).");
        } else {
            out.add("Detached pieces (not connected to the bottom layer through other blocks; stray blocks, scaffolding or deliberate overhangs): "
                    + r.pieceCount() + ", smallest first:");
            for (Piece p : r.pieces()) {
                out.add(String.format(Locale.ROOT, "  %dx %s [%d,%d,%d]..[%d,%d,%d]", p.cells(), p.block(),
                        p.min()[0], p.min()[1], p.min()[2], p.max()[0], p.max()[1], p.max()[2]));
            }
            if (r.pieceCount() > r.pieces().size()) out.add("  ... and " + (r.pieceCount() - r.pieces().size()) + " more.");
        }
        return String.join("\n", out);
    }
}
