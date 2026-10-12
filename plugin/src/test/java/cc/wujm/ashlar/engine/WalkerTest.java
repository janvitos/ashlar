// SPDX-License-Identifier: AGPL-3.0-or-later
package cc.wujm.ashlar.engine;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class WalkerTest {

    /** A small box x 0..9, y 63..68, z 0..9 with a stone floor at y=63 (feet at y=64). */
    static final class Grid {
        final Region box = new Region(0, 63, 0, 9, 68, 9);
        final List<String> palette = new ArrayList<>(List.of("minecraft:air", "minecraft:stone"));
        final int[] ids = new int[10 * 6 * 10];

        Grid() {
            for (int x = 0; x < 10; x++) for (int z = 0; z < 10; z++) set(x, 63, z, "minecraft:stone");
        }

        Grid set(int x, int y, int z, String state) {
            int id = palette.indexOf(state);
            if (id < 0) {
                id = palette.size();
                palette.add(state);
            }
            ids[((y - 63) * 10 + z) * 10 + x] = id;
            return this;
        }

        Grid wallX(int x, int y0, int y1, String state) {
            for (int z = 0; z < 10; z++) for (int y = y0; y <= y1; y++) set(x, y, z, state);
            return this;
        }

        Walker walker(Walker.Rules rules) {
            return new Walker(box, ids, palette, rules);
        }

        Walker walker() {
            return walker(Walker.Rules.DEFAULT);
        }
    }

    private static boolean reaches(Walker w, int[] from, int[] to) {
        int s = w.nodeAt(from[0], from[1], from[2]);
        assertTrue(s >= 0, "start is a node");
        assertNull(w.problem(s));
        int[] parent = w.search(s, -1);
        return w.reached(parent, to[0], to[2], to[1] - .5, to[1] + .5);
    }

    private static Walker.Block why(Walker w, int[] from, int[] to) {
        int[] parent = w.search(w.nodeAt(from[0], from[1], from[2]), -1);
        return w.diagnose(parent, to[0], to[1], to[2]).block();
    }

    @Test
    void flatFloorAndWallsAndTheBoxEdge() {
        Grid g = new Grid();
        Walker w = g.walker();
        int[] parent = w.search(w.nodeAt(0, 64, 0), -1);
        assertEquals(100, Walker.reachedCount(parent));
        List<Integer> path = Walker.path(parent, w.nodeAt(9, 64, 9));
        assertEquals(19, path.size(), "18 moves on a 4-neighbour grid");
        // A two-high wall at x=5 splits the floor.
        g.wallX(5, 64, 65, "minecraft:stone");
        w = g.walker();
        assertFalse(reaches(w, new int[]{0, 64, 0}, new int[]{9, 64, 0}));
        Walker.Block b = why(w, new int[]{0, 64, 0}, new int[]{9, 64, 0});
        assertTrue(b.reason().startsWith("step too high"), b.reason());
        assertEquals(5, b.at()[0]);
    }

    @Test
    void stepHeightAndJump() {
        // A one-block ledge: no step without jumping, fine with it.
        Grid g = new Grid().wallX(5, 64, 64, "minecraft:stone");
        assertFalse(reaches(g.walker(), new int[]{0, 64, 0}, new int[]{7, 64, 0}));
        Walker.Rules jump = new Walker.Rules(true, .6, true, false, false, 3);
        assertTrue(reaches(g.walker(jump), new int[]{0, 64, 0}, new int[]{7, 64, 0}));
        // A bottom slab is a 0.5 step.
        Grid slab = new Grid().wallX(5, 64, 64, "minecraft:oak_slab[type=bottom,waterlogged=false]");
        assertTrue(reaches(slab.walker(), new int[]{0, 64, 0}, new int[]{7, 64, 0}));
        // Fences are 1.5 high: even a jump does not clear them.
        Grid fence = new Grid().wallX(5, 64, 64, "minecraft:oak_fence[east=false,north=true,south=true,west=false,waterlogged=false]");
        assertFalse(reaches(fence.walker(jump), new int[]{0, 64, 0}, new int[]{7, 64, 0}));
    }

    @Test
    void stairsOnlyHeadOn() {
        // Stair at (5,64,5) facing east, then a raised floor east of it at y=64 (feet 65).
        Grid g = new Grid();
        for (int x = 6; x < 10; x++) for (int z = 0; z < 10; z++) g.set(x, 64, z, "minecraft:stone");
        g.set(5, 64, 5, "minecraft:oak_stairs[facing=east,half=bottom,shape=straight,waterlogged=false]");
        // Head-on from the west: up the stair onto the raised floor.
        assertTrue(reaches(g.walker(), new int[]{4, 64, 5}, new int[]{7, 65, 5}));
        // Down again the same way works too, even with no drop allowance beyond a half step.
        Walker.Rules strictDrop = new Walker.Rules(false, .6, true, false, false, .6);
        assertTrue(reaches(g.walker(strictDrop), new int[]{7, 65, 5}, new int[]{3, 64, 5}));
        // Sideways regression: a stair facing north in a corridor entered from the west must fail.
        Grid side = new Grid();
        for (int x = 6; x < 10; x++) for (int z = 0; z < 10; z++) side.set(x, 64, z, "minecraft:stone");
        for (int z = 0; z < 10; z++) if (z != 5) side.set(5, 64, z, "minecraft:stone").set(5, 65, z, "minecraft:stone");
        side.set(5, 64, 5, "minecraft:oak_stairs[facing=north,half=bottom,shape=straight,waterlogged=false]");
        for (int x = 0; x < 5; x++) for (int z = 0; z < 10; z++) if (z != 5) side.set(x, 64, z, "minecraft:stone").set(x, 65, z, "minecraft:stone");
        Walker w = side.walker();
        assertFalse(reaches(w, new int[]{4, 64, 5}, new int[]{7, 65, 5}));
        Walker.Block b = why(w, new int[]{4, 64, 5}, new int[]{7, 65, 5});
        assertTrue(b.reason().startsWith("stair approached from the side"), b.reason());
        assertArrayEquals(new int[]{5, 64, 5}, b.at());
    }

    @Test
    void snowLayersMakeGentleSlopes() {
        // Layers 2..8 rising one layer per block eastward: 0.125 steps, all walkable.
        Grid g = new Grid();
        for (int x = 1; x <= 7; x++) for (int z = 0; z < 10; z++) g.set(x, 64, z, "minecraft:snow[layers=" + (x + 1) + "]");
        Walker w = g.walker();
        assertTrue(reaches(w, new int[]{0, 64, 0}, new int[]{7, 65, 0}));
        int top = w.nodeAt(7, 65, 0);
        assertEquals(64.875, w.h(top), 1e-9);
        // A single layer has no collision: feet stay at 64.
        assertEquals(64.0, w.h(new Grid().set(2, 64, 2, "minecraft:snow[layers=1]").walker().nodeAt(2, 64, 2)), 1e-9);
        // Two full snow blocks stacked as a 2-high step: no.
        Grid steep = new Grid().wallX(5, 64, 65, "minecraft:snow_block");
        assertFalse(reaches(steep.walker(), new int[]{0, 64, 0}, new int[]{7, 64, 0}));
        // Carpet is a 1/16 step.
        Grid carpet = new Grid().wallX(5, 64, 64, "minecraft:red_carpet");
        assertTrue(reaches(carpet.walker(), new int[]{0, 64, 0}, new int[]{7, 64, 0}));
    }

    @Test
    void doorsAndHeadroom() {
        Grid g = new Grid().wallX(5, 64, 66, "minecraft:stone");
        g.set(5, 64, 5, "minecraft:oak_door[facing=east,half=lower,hinge=left,open=false,powered=false]");
        g.set(5, 65, 5, "minecraft:oak_door[facing=east,half=upper,hinge=left,open=false,powered=false]");
        Walker w = g.walker();
        assertFalse(reaches(w, new int[]{0, 64, 5}, new int[]{9, 64, 5}));
        assertEquals("closed door", why(w, new int[]{0, 64, 5}, new int[]{9, 64, 5}).reason());
        assertTrue(reaches(g.walker(new Walker.Rules(false, .6, true, false, true, 3)), new int[]{0, 64, 5}, new int[]{9, 64, 5}));
        g.set(5, 64, 5, "minecraft:oak_door[facing=east,half=lower,hinge=left,open=true,powered=false]");
        g.set(5, 65, 5, "minecraft:oak_door[facing=east,half=upper,hinge=left,open=true,powered=false]");
        assertTrue(reaches(g.walker(), new int[]{0, 64, 5}, new int[]{9, 64, 5}));
        // A one-block-high gap is not enough headroom; a bottom slab under a 2-high gap is not either.
        Grid low = new Grid().wallX(5, 64, 66, "minecraft:stone").set(5, 64, 5, "minecraft:air");
        assertFalse(reaches(low.walker(), new int[]{0, 64, 5}, new int[]{9, 64, 5}));
        assertTrue(why(low.walker(), new int[]{0, 64, 5}, new int[]{9, 64, 5}).reason().startsWith("headroom"));
        Grid slabGap = new Grid().wallX(5, 64, 66, "minecraft:stone").set(5, 64, 5, "minecraft:stone_slab[type=bottom,waterlogged=false]")
                .set(5, 65, 5, "minecraft:air");
        assertFalse(reaches(slabGap.walker(), new int[]{0, 64, 5}, new int[]{9, 64, 5}));
    }

    @Test
    void startOnABottomSlabGivenAsItsBlockY() {
        // A slab floor at y=64: feet rest at 64.5. Giving y=64 (the slab's own block) must pick that
        // place, not the closer feet height 64 under the slab, whose only obstruction is the slab.
        Grid g = new Grid();
        for (int x = 0; x < 10; x++) for (int z = 0; z < 10; z++) g.set(x, 64, z, "minecraft:stone_slab[type=bottom,waterlogged=false]");
        Walker w = g.walker();
        int s = w.nodeAt(2, 64, 2);
        assertTrue(s >= 0);
        assertNull(w.problem(s));
        assertEquals(64.5, w.h(s), 1e-9);
        assertEquals(64.5, w.h(w.nodeAt(2, 65, 2)), 1e-9);
        assertTrue(reaches(w, new int[]{0, 64, 0}, new int[]{9, 64, 9}));
    }

    @Test
    void laddersWaterAndDrops() {
        // A 3-high ledge at x>=6 with a ladder against it at x=5.
        Grid g = new Grid();
        for (int x = 6; x < 10; x++) for (int z = 0; z < 10; z++) for (int y = 64; y <= 66; y++) g.set(x, y, z, "minecraft:stone");
        for (int y = 64; y <= 66; y++) g.set(5, y, 5, "minecraft:ladder[facing=west,waterlogged=false]");
        assertTrue(reaches(g.walker(), new int[]{0, 64, 5}, new int[]{7, 67, 5}));
        Walker.Rules noLadders = new Walker.Rules(false, .6, false, false, false, 3);
        assertFalse(reaches(g.walker(noLadders), new int[]{0, 64, 5}, new int[]{7, 67, 5}));
        // Coming down: a 3-block drop is fine with maxDrop 3 and refused with maxDrop 2.
        Grid ledge = new Grid();
        for (int x = 6; x < 10; x++) for (int z = 0; z < 10; z++) for (int y = 64; y <= 66; y++) ledge.set(x, y, z, "minecraft:stone");
        assertTrue(reaches(ledge.walker(), new int[]{7, 67, 5}, new int[]{0, 64, 5}));
        Walker.Rules drop2 = new Walker.Rules(false, .6, true, false, false, 2);
        assertFalse(reaches(ledge.walker(drop2), new int[]{7, 67, 5}, new int[]{0, 64, 5}));
        assertTrue(why(ledge.walker(drop2), new int[]{7, 67, 5}, new int[]{0, 64, 5}).reason().startsWith("gap"));
        // A water channel blocks unless water is allowed.
        Grid water = new Grid();
        for (int z = 0; z < 10; z++) water.set(5, 63, z, "minecraft:water[level=0]");
        Walker w = water.walker();
        assertFalse(reaches(w, new int[]{0, 64, 5}, new int[]{9, 64, 5}));
        assertTrue(reaches(water.walker(new Walker.Rules(false, .6, true, true, false, 3)), new int[]{0, 64, 5}, new int[]{9, 64, 5}));
    }
}
