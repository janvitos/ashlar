// SPDX-License-Identifier: AGPL-3.0-or-later
package cc.wujm.ashlar.engine;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CompositionScanTest {

    /** A small world: air everywhere in a 16x14x16 box at the origin unless set. */
    static final class Scene {
        final Region box = new Region(0, 0, 0, 15, 13, 15);
        final Map<String, Integer> index = new HashMap<>();
        final List<String> palette = new ArrayList<>();
        final int[] grid = new int[Math.toIntExact(box.volume())];

        Scene() {
            id("minecraft:air");
        }

        int id(String state) {
            return index.computeIfAbsent(state, s -> {
                palette.add(s);
                return palette.size() - 1;
            });
        }

        Scene set(int x, int y, int z, String state) {
            grid[(y * 16 + z) * 16 + x] = id(state.contains(":") ? state : "minecraft:" + state);
            return this;
        }

        Scene fill(int x0, int y0, int z0, int x1, int y1, int z1, String state) {
            for (int y = y0; y <= y1; y++) for (int z = z0; z <= z1; z++) for (int x = x0; x <= x1; x++) set(x, y, z, state);
            return this;
        }

        /** A stone floor at y = 0 over the whole box. */
        Scene floor() {
            return fill(0, 0, 0, 15, 0, 15, "stone_bricks");
        }

        Map<String, Long> scan() {
            CompositionScan s = new CompositionScan(Set.of(), 5);
            s.scan(box, box, palette, grid);
            return s.counts();
        }
    }

    private static final String FENCE = "oak_fence[east=false,north=false,south=false,waterlogged=false,west=false]";
    private static final String BARS = "iron_bars[east=false,north=false,south=false,waterlogged=false,west=false]";

    @Test
    void emptyFloorHasNoFindings() {
        assertTrue(new Scene().floor().scan().isEmpty());
    }

    @Test
    void diagonalFencesAreLeaky() {
        Map<String, Long> c = new Scene().floor().set(4, 1, 4, FENCE).set(5, 1, 5, FENCE).set(6, 1, 6, FENCE).scan();
        assertEquals(3L, c.get("diagonal-rail"));
        assertFalse(c.containsKey("lone-post"));
    }

    @Test
    void connectedStraightRailIsFine() {
        Scene s = new Scene().floor();
        s.set(4, 1, 4, "oak_fence[east=true,north=false,south=false,waterlogged=false,west=false]");
        s.set(5, 1, 4, "oak_fence[east=true,north=false,south=false,waterlogged=false,west=true]");
        s.set(6, 1, 4, "oak_fence[east=false,north=false,south=false,waterlogged=false,west=true]");
        assertTrue(s.scan().isEmpty());
    }

    @Test
    void railClimbingAStairAsSeparatePostsHasGaps() {
        Scene s = new Scene().floor();
        for (int i = 0; i < 3; i++) s.set(4 + i, 1 + i, 4, BARS);
        Map<String, Long> c = s.scan();
        assertEquals(2L, c.get("rail-gap"));
        assertFalse(c.containsKey("lone-post"), "stepped pieces are rail-gap's, not lone posts");
    }

    @Test
    void steppedWallsCloseTheirOwnGap() {
        Scene s = new Scene().floor();
        for (int i = 0; i < 3; i++) s.set(4 + i, 1 + i, 4, "cobblestone_wall[east=none,north=none,south=none,up=true,waterlogged=false,west=none]");
        assertFalse(s.scan().containsKey("rail-gap"));
    }

    @Test
    void unjoinedPiecesBesideAWallAreLonePosts() {
        Scene s = new Scene().floor().set(4, 1, 4, FENCE).set(9, 1, 9, BARS);
        assertTrue(s.scan().isEmpty(), "free-standing bollards and masts are a choice");
        s.set(10, 1, 9, "stone_bricks").set(4, 1, 5, FENCE);
        assertEquals(3L, s.scan().get("lone-post"), "bars beside a wall and two touching fences, all unjoined");
    }

    @Test
    void fourFencesInASquareClump() {
        assertEquals(1L, new Scene().floor().fill(4, 1, 4, 5, 1, 5, "oak_fence[east=true,north=true,south=true,waterlogged=false,west=true]")
                .scan().get("fence-clump"));
    }

    @Test
    void lanternHeightsByClearance() {
        Scene s = new Scene().floor().fill(0, 4, 0, 15, 4, 15, "stone_bricks");
        s.set(2, 2, 2, "lantern[hanging=true,waterlogged=false]"); // 1 clear above the floor
        s.set(6, 3, 6, "lantern[hanging=true,waterlogged=false]"); // 2 clear, 3-high room
        Map<String, Long> c = s.scan();
        assertEquals(2L, c.get("low-lamp"));
        Scene tall = new Scene().floor().fill(0, 5, 0, 15, 5, 15, "stone_bricks");
        tall.set(6, 4, 6, "lantern[hanging=true,waterlogged=false]"); // 3 clear under a 4-high ceiling
        assertFalse(tall.scan().containsKey("low-lamp"));
    }

    @Test
    void lanternOverATableNeedsOnlyOneClear() {
        Scene s = new Scene().floor().fill(0, 5, 0, 15, 5, 15, "stone_bricks");
        s.set(6, 1, 6, FENCE).set(6, 2, 6, "oak_pressure_plate[powered=false]");
        s.set(6, 4, 6, "lantern[hanging=true,waterlogged=false]");
        assertFalse(s.scan().containsKey("low-lamp"));
    }

    @Test
    void objectAtHeadHeightAcrossAWalkway() {
        Scene s = new Scene().floor().set(5, 2, 5, "oak_planks");
        assertEquals(1L, s.scan().get("head-height"));
    }

    @Test
    void blockedDoorButCarpetIsFine() {
        Scene s = new Scene().floor().fill(0, 1, 6, 15, 3, 6, "stone_bricks");
        s.set(5, 1, 6, "oak_door[facing=north,half=lower,hinge=left,open=false,powered=false]");
        s.set(5, 2, 6, "oak_door[facing=north,half=upper,hinge=left,open=false,powered=false]");
        s.set(5, 1, 5, "red_carpet");
        assertFalse(s.scan().containsKey("blocked-door"));
        s.set(5, 1, 7, "iron_bars[east=false,north=true,south=false,waterlogged=false,west=false]");
        assertEquals(1L, s.scan().get("blocked-door"));
    }

    @Test
    void furnishedRoomWithoutAWayInIsSealed() {
        Scene s = new Scene().floor();
        s.fill(3, 1, 3, 9, 4, 9, "stone_bricks").fill(4, 1, 4, 8, 3, 8, "air");
        s.set(5, 1, 5, "chest[facing=north,type=single,waterlogged=false]");
        assertEquals(1L, s.scan().get("sealed-room"));
        s.set(3, 1, 6, "oak_door[facing=east,half=lower,hinge=left,open=false,powered=false]");
        s.set(3, 2, 6, "oak_door[facing=east,half=upper,hinge=left,open=false,powered=false]");
        assertFalse(s.scan().containsKey("sealed-room"), "a door is a way in");
    }

    @Test
    void emptyHollowIsNotARoom() {
        Scene s = new Scene().floor();
        s.fill(3, 1, 3, 9, 4, 9, "stone_bricks").fill(4, 1, 4, 8, 3, 8, "air");
        s.set(6, 3, 6, "lantern[hanging=true,waterlogged=false]");
        assertFalse(s.scan().containsKey("sealed-room"), "a lit hollow prop has no furniture");
    }

    @Test
    void leftovers() {
        Scene s = new Scene().floor();
        s.set(3, 9, 12, "oak_leaves[distance=1,persistent=true,waterlogged=false]");
        s.set(3, 1, 3, "water[level=3]").set(3, 1, 4, "oak_planks");
        s.set(8, 7, 8, "light[level=15,waterlogged=false]").set(8, 8, 8, "oak_planks");
        Map<String, Long> c = s.scan();
        assertEquals(1L, c.get("floating-leaves"));
        assertEquals(1L, c.get("flowing-liquid"));
        assertEquals(1L, c.get("hidden-light"));
        s.set(8, 7, 11, "glowstone");
        assertFalse(s.scan().containsKey("hidden-light"), "light beside a visible lamp is fine");
    }

    @Test
    void lightOverBareTerrainIsNotHidden() {
        Scene s = new Scene().fill(0, 0, 0, 15, 2, 15, "packed_ice").set(8, 3, 8, "light[level=11,waterlogged=false]");
        assertTrue(s.scan().isEmpty(), "anti-spawn light on terrain needs no lamp");
    }

    @Test
    void skippedRulesAndUnknownCellsStaySilent() {
        Scene s = new Scene().floor().set(12, 1, 12, "light[level=15,waterlogged=false]").set(12, 3, 12, "oak_planks");
        CompositionScan skip = new CompositionScan(Set.of("hidden-light"), 5);
        skip.scan(s.box, s.box, s.palette, s.grid);
        assertTrue(skip.isEmpty());
        // Judged near the read box edge: the unknown cells beyond might hold a lamp.
        Scene edge = new Scene().floor().set(15, 1, 15, "light[level=15,waterlogged=false]").set(15, 3, 15, "oak_planks");
        CompositionScan inner = new CompositionScan(Set.of(), 5);
        inner.scan(edge.box, new Region(10, 0, 10, 15, 13, 15), edge.palette, edge.grid);
        assertTrue(inner.isEmpty());
    }
}
