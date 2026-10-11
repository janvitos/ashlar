// SPDX-License-Identifier: AGPL-3.0-or-later
package cc.wujm.ashlar.engine;

import org.junit.jupiter.api.Test;

import java.util.ArrayDeque;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ShapeExpandTest {

    /** Every cell of the result; also checks the runs neither overlap nor leave the box. */
    private static Set<List<Integer>> cells(ShapeExpand.Result r) {
        Set<List<Integer>> out = new HashSet<>();
        for (ShapeExpand.Run run : r.runs())
            for (int x = run.from()[0]; x <= run.to()[0]; x++)
                for (int y = run.from()[1]; y <= run.to()[1]; y++)
                    for (int z = run.from()[2]; z <= run.to()[2]; z++) {
                        assertTrue(out.add(List.of(x, y, z)), "overlapping runs");
                        for (int d = 0; d < 3; d++) assertTrue(List.of(x, y, z).get(d) >= r.min()[d] && List.of(x, y, z).get(d) <= r.max()[d]);
                    }
        assertEquals(r.blocks(), out.size());
        return out;
    }

    private static int layer(Set<List<Integer>> cells, int y) {
        return (int) cells.stream().filter(c -> c.get(1) == y).count();
    }

    @Test
    void discsHaveClassicPixelRows() {
        Set<List<Integer>> five = cells(ShapeExpand.expand("cylinder", new int[] {0, 0, 0}, new int[] {4, 0, 4}, "y", 0));
        assertEquals(3 + 5 + 5 + 5 + 3, five.size());
        Set<List<Integer>> seven = cells(ShapeExpand.expand("cylinder", new int[] {0, 0, 0}, new int[] {6, 0, 6}, "y", 0));
        assertEquals(3 + 5 + 7 + 7 + 7 + 5 + 3, seven.size());
        // A solid cylinder is one column per disc cell.
        ShapeExpand.Result tower = ShapeExpand.expand("cylinder", new int[] {10, 60, 10}, new int[] {16, 79, 16}, "y", 0);
        assertEquals(37, tower.runs().size());
        assertEquals(37L * 20, tower.blocks());
    }

    @Test
    void spheresAreSymmetricUnderEveryReflectionAndAxisSwap() {
        for (int size : new int[] {6, 9}) {
            Set<List<Integer>> s = cells(ShapeExpand.expand("sphere", new int[] {0, 0, 0}, new int[] {size - 1, size - 1, size - 1}, "y", 0));
            int m = size - 1;
            for (List<Integer> c : s) {
                int x = c.get(0), y = c.get(1), z = c.get(2);
                for (List<Integer> image : List.of(List.of(m - x, y, z), List.of(x, m - y, z), List.of(x, y, m - z),
                        List.of(y, x, z), List.of(z, y, x), List.of(x, z, y))) assertTrue(s.contains(image), image.toString());
            }
        }
        // Sides of 1 and unequal sides give flat discs and ellipsoids without failing.
        assertEquals(3 + 5 + 5 + 5 + 3, cells(ShapeExpand.expand("sphere", new int[] {0, 0, 0}, new int[] {4, 0, 4}, "y", 0)).size());
        assertTrue(cells(ShapeExpand.expand("sphere", new int[] {0, 0, 0}, new int[] {20, 5, 8}, "y", 0)).size() > 0);
    }

    @Test
    void hollowCylinderIsAWatertightRingWithOpenEnds() {
        Set<List<Integer>> ring = cells(ShapeExpand.expand("cylinder", new int[] {0, 0, 0}, new int[] {8, 3, 8}, "y", 1));
        assertFalse(ring.contains(List.of(4, 0, 4)));
        assertFalse(ring.contains(List.of(4, 3, 4)));
        for (int y = 1; y <= 3; y++) assertEquals(layer(ring, 0), layer(ring, y));
        // No face-connected path in a layer from the centre out of the box.
        assertFalse(escapes(ring, List.of(4, 0, 4), 0, 8, 0, 0, 0, 8));
        Set<List<Integer>> thick = cells(ShapeExpand.expand("cylinder", new int[] {0, 0, 0}, new int[] {8, 0, 8}, "y", 2));
        assertTrue(thick.size() > layer(ring, 0));
        assertFalse(thick.contains(List.of(4, 0, 4)));
    }

    @Test
    void hollowDomeHasNoFloorAndIsWatertightAbove() {
        ShapeExpand.Result solid = ShapeExpand.expand("dome", new int[] {0, 0, 0}, new int[] {10, 5, 10}, "y", 0);
        Set<List<Integer>> full = cells(solid);
        assertEquals(cells(ShapeExpand.expand("cylinder", new int[] {0, 0, 0}, new int[] {10, 0, 10}, "y", 0)).size(), layer(full, 0));
        assertTrue(layer(full, 5) > 0);
        Set<List<Integer>> shell = cells(ShapeExpand.expand("dome", new int[] {0, 0, 0}, new int[] {10, 5, 10}, "y", 1));
        assertFalse(shell.contains(List.of(5, 0, 5)));
        assertTrue(shell.contains(List.of(5, 5, 5)));
        // With a floor added under the open base, nothing leaks.
        Set<List<Integer>> closed = new HashSet<>(shell);
        for (int x = 0; x <= 10; x++) for (int z = 0; z <= 10; z++) closed.add(List.of(x, -1, z));
        assertFalse(escapes(closed, List.of(5, 1, 5), 0, 10, -1, 5, 0, 10));
        assertTrue(escapes(shell, List.of(5, 1, 5), 0, 10, -1, 5, 0, 10));
        // "-y" is the same dome upside down.
        Set<List<Integer>> bowl = cells(ShapeExpand.expand("dome", new int[] {0, 0, 0}, new int[] {10, 5, 10}, "-y", 1));
        assertEquals(shell.size(), bowl.size());
        for (List<Integer> c : shell) assertTrue(bowl.contains(List.of(c.get(0), 5 - c.get(1), c.get(2))));
    }

    @Test
    void conesKeepTheirTipAndPyramidsStepOneBlockPerLayer() {
        Set<List<Integer>> even = cells(ShapeExpand.expand("cone", new int[] {0, 0, 0}, new int[] {5, 9, 5}, "y", 0));
        for (int y = 0; y <= 9; y++) assertTrue(layer(even, y) > 0, "layer " + y);
        assertEquals(4, layer(even, 9));
        Set<List<Integer>> odd = cells(ShapeExpand.expand("cone", new int[] {0, 0, 0}, new int[] {6, 6, 6}, "y", 0));
        assertEquals(1, layer(odd, 6));
        Set<List<Integer>> pyramid = cells(ShapeExpand.expand("pyramid", new int[] {0, 0, 0}, new int[] {8, 4, 8}, "y", 0));
        int[] widths = {9, 7, 5, 3, 1};
        for (int y = 0; y < 5; y++) assertEquals(widths[y] * widths[y], layer(pyramid, y));
        // Sideways cone along -x: base at x=max, tip at x=min.
        Set<List<Integer>> side = cells(ShapeExpand.expand("cone", new int[] {0, 0, 0}, new int[] {8, 4, 4}, "-x", 0));
        assertEquals(1, side.stream().filter(c -> c.get(0) == 0).count());
        assertEquals(cells(ShapeExpand.expand("cylinder", new int[] {0, 0, 0}, new int[] {0, 4, 4}, "x", 0)).size(),
                side.stream().filter(c -> c.get(0) == 8).count());
    }

    @Test
    void invalidArgumentsAreRejected() {
        int[] o = {0, 0, 0};
        assertThrows(IllegalArgumentException.class, () -> ShapeExpand.expand("torus", o, o, "y", 0));
        assertThrows(IllegalArgumentException.class, () -> ShapeExpand.expand("sphere", o, o, "up", 0));
        assertThrows(IllegalArgumentException.class, () -> ShapeExpand.expand("sphere", o, o, "y", 17));
        assertThrows(IllegalArgumentException.class, () -> ShapeExpand.expand("sphere", o, new int[] {300, 300, 300}, "y", 0));
        assertThrows(IllegalArgumentException.class, () -> ShapeExpand.expand("sphere", new int[] {Integer.MIN_VALUE, 0, 0},
                new int[] {Integer.MAX_VALUE, 0, 0}, "y", 0));
    }

    /** Whether a face-connected walk through cells not in {@code wall} gets from {@code start} out of the given box (within the layer when y0 == y1). */
    private static boolean escapes(Set<List<Integer>> wall, List<Integer> start, int x0, int x1, int y0, int y1, int z0, int z1) {
        ArrayDeque<List<Integer>> queue = new ArrayDeque<>(List.of(start));
        Set<List<Integer>> seen = new HashSet<>(queue);
        int[][] steps = {{1, 0, 0}, {-1, 0, 0}, {0, 1, 0}, {0, -1, 0}, {0, 0, 1}, {0, 0, -1}};
        while (!queue.isEmpty()) {
            List<Integer> c = queue.poll();
            if (c.get(0) < x0 || c.get(0) > x1 || c.get(1) < y0 || c.get(1) > y1 || c.get(2) < z0 || c.get(2) > z1) return true;
            for (int[] s : steps) {
                if (s[1] != 0 && y0 == y1) continue;
                List<Integer> n = List.of(c.get(0) + s[0], c.get(1) + s[1], c.get(2) + s[2]);
                if (!wall.contains(n) && seen.add(n)) queue.add(n);
            }
        }
        return false;
    }
}
