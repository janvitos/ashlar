// SPDX-License-Identifier: AGPL-3.0-or-later
package cc.wujm.ashlar.engine;

import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Step 18 spawn-space rules and greedy light planning over a fake world. */
class LightScanTest {

    /** Stone everywhere except the cells set; light 0 unless set. */
    private static final class Fake implements LightScan.Cells {
        final Map<Long, String> states = new HashMap<>();
        final Map<Long, Integer> block = new HashMap<>();
        int sky;

        void set(int x, int y, int z, String s) {
            states.put(LightScan.key(x, y, z), s);
        }

        @Override
        public String state(int x, int y, int z) {
            return states.getOrDefault(LightScan.key(x, y, z), "minecraft:stone");
        }

        @Override
        public int blockLight(int x, int y, int z) {
            return block.getOrDefault(LightScan.key(x, y, z), 0);
        }

        @Override
        public int skyLight(int x, int y, int z) {
            return sky;
        }
    }

    /** A closed room: stone floor at y=0, air x 0..w-1, y 1..h, z 0..d-1, stone around. */
    private static Fake room(int w, int h, int d) {
        Fake f = new Fake();
        for (int x = 0; x < w; x++) for (int y = 1; y <= h; y++) for (int z = 0; z < d; z++) f.set(x, y, z, "minecraft:air");
        return f;
    }

    private static final Region BOX = Region.of(new int[]{-1, 0, -1}, new int[]{20, 5, 20});
    private static final LightScan.Options DEFAULT = new LightScan.Options(0, false, true);

    @Test
    void closedDarkRoomCountsEveryFloorCell() {
        LightScan.Result r = LightScan.scan(room(10, 3, 10), BOX, DEFAULT, null);
        assertEquals(100, r.spaces());
        assertEquals(100, r.dark().length);
        assertEquals(Map.of(1, 100), r.darkByY());
    }

    @Test
    void floorRules() {
        assertTrue(LightRules.floor("minecraft:stone"));
        assertTrue(LightRules.floor("minecraft:oak_slab[type=top]"));
        assertTrue(LightRules.floor("minecraft:oak_stairs[half=top,facing=north]"));
        assertTrue(LightRules.floor("minecraft:soul_sand"));
        assertFalse(LightRules.floor("minecraft:oak_slab[type=bottom]"));
        assertFalse(LightRules.floor("minecraft:oak_stairs[half=bottom,facing=north]"));
        assertFalse(LightRules.floor("minecraft:glass"));
        assertFalse(LightRules.floor("minecraft:oak_leaves"));
        assertFalse(LightRules.floor("minecraft:ice"));
        assertFalse(LightRules.floor("minecraft:magma_block"));
        assertFalse(LightRules.floor("minecraft:oak_fence"));
        assertFalse(LightRules.floor("minecraft:air"));
    }

    @Test
    void feetAndHeadRules() {
        assertTrue(LightRules.feet("minecraft:air"));
        assertTrue(LightRules.feet("minecraft:short_grass"));
        assertTrue(LightRules.feet("minecraft:snow[layers=1]"));
        assertFalse(LightRules.feet("minecraft:white_carpet"));
        assertFalse(LightRules.feet("minecraft:rail[shape=north_south]"));
        assertFalse(LightRules.feet("minecraft:stone_pressure_plate[powered=false]"));
        assertFalse(LightRules.feet("minecraft:redstone_wire"));
        assertFalse(LightRules.feet("minecraft:water[level=0]"));
        assertFalse(LightRules.head("minecraft:oak_sign[rotation=0,waterlogged=true]"));
        assertTrue(LightRules.head("minecraft:vine[north=true]"));
    }

    @Test
    void transmitsRules() {
        assertTrue(LightRules.transmits("minecraft:air"));
        assertTrue(LightRules.transmits("minecraft:glass"));
        assertTrue(LightRules.transmits("minecraft:oak_slab[type=bottom]"));
        assertTrue(LightRules.transmits("minecraft:oak_leaves"));
        assertFalse(LightRules.transmits("minecraft:stone"));
        assertFalse(LightRules.transmits("minecraft:oak_planks"));
    }

    @Test
    void carpetedOrLowRoomsHaveNoSpawnSpaces() {
        Fake f = room(4, 3, 4);
        for (int x = 0; x < 4; x++) for (int z = 0; z < 4; z++) f.set(x, 1, z, "minecraft:white_carpet");
        assertEquals(0, LightScan.scan(f, BOX, DEFAULT, null).spaces());
        assertEquals(0, LightScan.scan(room(4, 1, 4), BOX, DEFAULT, null).spaces());
        // spawnable:false counts open cells regardless of floor and headroom.
        assertEquals(16, LightScan.scan(room(4, 1, 4), BOX, new LightScan.Options(0, false, false), null).spaces());
    }

    @Test
    void thresholdAndSkyLight() {
        Fake f = room(3, 2, 1);
        f.block.put(LightScan.key(0, 1, 0), 5);
        f.sky = 15;
        assertEquals(2, LightScan.scan(f, BOX, DEFAULT, null).dark().length);
        assertEquals(3, LightScan.scan(f, BOX, new LightScan.Options(5, false, true), null).dark().length);
        assertEquals(0, LightScan.scan(f, BOX, new LightScan.Options(0, true, true), null).dark().length);
    }

    @Test
    void greedyPlanCoversASmallRoomWithOneLight() {
        LightScan.Result r = LightScan.scan(room(10, 3, 10), BOX, DEFAULT, new LightScan.Suggest("minecraft:light[level=11]", 11, 1, "hidden"));
        assertEquals(1, r.lights().size());
        assertEquals(100, r.covered());
        assertEquals(0, r.uncoverable());
    }

    @Test
    void greedyPlanCoversALongCorridor() {
        Fake f = room(19, 2, 1);
        LightScan.Result r = LightScan.scan(f, BOX, DEFAULT, new LightScan.Suggest("minecraft:light[level=5]", 5, 1, "hidden"));
        // Level 5 reaches 4 steps each way: 9 cells per light, 19 cells -> 3 lights.
        assertEquals(3, r.lights().size());
        assertEquals(0, r.uncoverable());
    }

    @Test
    void wallsStopLightSpread() {
        Fake f = room(9, 2, 1);
        f.set(4, 1, 0, "minecraft:stone");
        f.set(4, 2, 0, "minecraft:stone");
        LightScan.Result r = LightScan.scan(f, BOX, DEFAULT, new LightScan.Suggest("minecraft:light[level=15]", 15, 1, "hidden"));
        assertEquals(2, r.lights().size());
    }

    @Test
    void spacingKeepsLightsApartAndMayLeaveDarkSpaces() {
        Fake f = room(19, 2, 1);
        LightScan.Result r = LightScan.scan(f, BOX, DEFAULT, new LightScan.Suggest("minecraft:light[level=5]", 5, 10, "hidden"));
        for (int i = 0; i < r.lights().size(); i++) {
            for (int j = i + 1; j < r.lights().size(); j++) assertTrue(Math.abs(r.lights().get(i)[0] - r.lights().get(j)[0]) >= 10);
        }
        assertTrue(r.uncoverable() > 0);
        assertEquals(19, r.covered() + r.uncoverable());
    }

    @Test
    void ceilingPlacementHangsUnderTheRoof() {
        LightScan.Result r = LightScan.scan(room(5, 4, 5), BOX, DEFAULT, new LightScan.Suggest("minecraft:lantern[hanging=true]", 15, 1, "ceiling"));
        assertEquals(1, r.lights().size());
        assertEquals(4, r.lights().get(0)[1]);
        assertEquals(0, r.uncoverable());
    }

    @Test
    void levelAtThresholdReachesNothing() {
        LightScan.Result r = LightScan.scan(room(3, 2, 3), BOX, new LightScan.Options(5, false, true),
                new LightScan.Suggest("minecraft:light[level=5]", 5, 1, "hidden"));
        assertEquals(0, r.lights().size());
        assertEquals(9, r.uncoverable());
        assertNotNull(r.dark());
    }
}
