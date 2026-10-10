// SPDX-License-Identifier: AGPL-3.0-or-later
package cc.wujm.ashlar.render;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

class VoxelRayTest {

    /** A sparse test world: listed cells hold a state, everything else is air; chunks in {@code unloaded} are unknown. */
    static final class World implements VoxelRay.Voxels {
        final Map<List<Integer>, Integer> cells = new HashMap<>();
        final List<String> states = new ArrayList<>(List.of("minecraft:air"));
        final Set<Long> unloaded = new java.util.HashSet<>();

        World set(int x, int y, int z, String state) {
            int id = states.indexOf(state);
            if (id < 0) {
                id = states.size();
                states.add(state);
            }
            cells.put(List.of(x, y, z), id);
            return this;
        }

        World unload(int cx, int cz) {
            unloaded.add(FirstPersonCamera.pack(cx, cz));
            return this;
        }

        @Override
        public int id(int x, int y, int z) {
            if (unloaded.contains(FirstPersonCamera.pack(x >> 4, z >> 4))) return UNKNOWN;
            return cells.getOrDefault(List.of(x, y, z), AIR);
        }

        @Override
        public BlockShapes.Shape shape(int id) {
            return BlockShapes.resolve(states.get(id));
        }

        @Override
        public String state(int id) {
            return states.get(id);
        }

        @Override
        public int minY() {
            return -64;
        }

        @Override
        public int maxY() {
            return 319;
        }
    }

    private static List<int[]> hits(World w, double ox, double oy, double oz, double dx, double dy, double dz, double max) {
        double n = Math.sqrt(dx * dx + dy * dy + dz * dz);
        List<int[]> out = new ArrayList<>();
        VoxelRay.trace(w, ox, oy, oz, dx / n, dy / n, dz / n, max, new VoxelRay.Visitor() {
            @Override
            public boolean surface(int x, int y, int z, int id, double t, int axis, int sign) {
                out.add(new int[]{x, y, z, (int) Math.round(t * 100), axis, sign});
                return true;
            }

            @Override
            public void unknown(int x, int y, int z, double t) {
                out.add(new int[]{x, y, z, -1});
            }
        });
        return out;
    }

    @Test
    void axisAlignedRayHitsTheNearFaceAtTheRightDistance() {
        World w = new World().set(5, 64, 0, "minecraft:stone").set(9, 64, 0, "minecraft:stone");
        List<int[]> h = hits(w, .5, 64.5, .5, 1, 0, 0, 20);
        assertEquals(2, h.size());
        assertArrayEquals(new int[]{5, 64, 0, 450, 0, -1}, h.get(0));
        assertEquals(9, h.get(1)[0]);
        // Out of range: nothing.
        assertTrue(hits(w, .5, 64.5, .5, 1, 0, 0, 4).isEmpty());
    }

    @Test
    void diagonalRaysVisitEveryCellTheyCrossAndNoCornerGhosts() {
        World w = new World();
        for (int i = 0; i < 10; i++) for (int j = 0; j < 10; j++) w.set(i, 64, j, "minecraft:stone");
        // From above a 10x10 floor, looking down diagonally: the first hit is the floor top.
        List<int[]> h = hits(w, .5, 70, .5, 1, -1, 1, 50);
        assertEquals(1, h.get(0)[4], "first face is a top face");
        assertEquals(1, h.get(0)[5]);
        // An exact diagonal through cell corners in x/z visits cells along it without stopping early.
        List<int[]> flat = hits(new World().set(3, 0, 3, "minecraft:stone"), .5, .5, .5, 1, 0, 1, 10);
        assertEquals(1, flat.size());
        assertEquals(3, flat.get(0)[0]);
        assertEquals(3, flat.get(0)[2]);
    }

    @Test
    void partialBlocksOnlyBlockWhereTheyHaveShape() {
        World w = new World().set(3, 64, 0, "minecraft:oak_slab[type=bottom]").set(6, 64, 0, "minecraft:snow[layers=2]");
        // At y=64.75 the ray passes over the bottom slab and the snow layer.
        assertTrue(hits(w, .5, 64.75, .5, 1, 0, 0, 10).isEmpty());
        // At y=64.25 it hits the slab's side.
        List<int[]> low = hits(w, .5, 64.25, .5, 1, 0, 0, 10);
        assertEquals(3, low.get(0)[0]);
        // A top slab blocks the upper half only.
        World top = new World().set(3, 64, 0, "minecraft:oak_slab[type=top]");
        assertTrue(hits(top, .5, 64.25, .5, 1, 0, 0, 10).isEmpty());
        assertEquals(1, hits(top, .5, 64.75, .5, 1, 0, 0, 10).size());
    }

    @Test
    void unloadedChunksStopTheRayAsUnknown() {
        World w = new World().unload(1, 0).set(20, 64, 0, "minecraft:stone");
        List<int[]> h = hits(w, .5, 64.5, .5, 1, 0, 0, 40);
        assertEquals(1, h.size());
        assertEquals(-1, h.get(0)[3]);
        assertEquals(16, h.get(0)[0]);
    }

    @Test
    void lookAndAnglesFollowMinecraftConventions() {
        assertArrayEquals(new double[]{0, 0, 1}, VoxelRay.look(0, 0), 1e-9);
        assertArrayEquals(new double[]{-1, 0, 0}, VoxelRay.look(90, 0), 1e-9);
        assertArrayEquals(new double[]{0, 0, -1}, VoxelRay.look(180, 0), 1e-9);
        assertArrayEquals(new double[]{1, 0, 0}, VoxelRay.look(-90, 0), 1e-9);
        assertArrayEquals(new double[]{0, -1, 0}, VoxelRay.look(0, 90), 1e-9);
        double[] a = VoxelRay.angles(1, 0, 0);
        assertEquals(-90, a[0], 1e-9);
        assertEquals(0, a[1], 1e-9);
    }
}
