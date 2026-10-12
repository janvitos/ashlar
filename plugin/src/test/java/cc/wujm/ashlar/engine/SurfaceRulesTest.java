// SPDX-License-Identifier: AGPL-3.0-or-later
package cc.wujm.ashlar.engine;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Step 18 decoration-ignoring surface and walk-height rules. */
class SurfaceRulesTest {

    @Test
    void decorationIsLookedThroughButGroundIsNot() {
        for (String s : new String[]{"minecraft:red_carpet", "minecraft:torch", "minecraft:wall_torch[facing=north]", "minecraft:lantern[hanging=false]",
                "minecraft:rail[shape=north_south]", "minecraft:oak_sign[rotation=0]", "minecraft:short_grass", "minecraft:poppy",
                "minecraft:potted_cactus", "minecraft:skeleton_skull", "minecraft:white_candle[candles=1]", "minecraft:stone_button[face=floor]"}) {
            assertTrue(SurfaceRules.decor(s, 3), s);
        }
        for (String s : new String[]{"minecraft:stone", "minecraft:grass_block", "minecraft:oak_slab[type=bottom]", "minecraft:oak_fence",
                "minecraft:water[level=0]", "minecraft:oak_leaves"}) {
            assertFalse(SurfaceRules.decor(s, 3), s);
        }
    }

    @Test
    void snowCountsAsDecorationUpToTheLayerLimit() {
        assertTrue(SurfaceRules.decor("minecraft:snow[layers=3]", 3));
        assertFalse(SurfaceRules.decor("minecraft:snow[layers=4]", 3));
        assertFalse(SurfaceRules.decor("minecraft:snow[layers=1]", 0));
        assertFalse(SurfaceRules.decor("minecraft:snow_block", 7));
    }

    @Test
    void skipAlsoPassesLeaves() {
        assertTrue(SurfaceRules.skip("minecraft:oak_leaves[persistent=false]", 3));
        assertFalse(SurfaceRules.skip("minecraft:dirt", 3));
    }

    @Test
    void walkTopIsTheCollisionHeight() {
        assertEquals(1.0, SurfaceRules.walkTop("minecraft:stone"));
        assertEquals(0.5, SurfaceRules.walkTop("minecraft:oak_slab[type=bottom]"));
        assertEquals(1 / 16.0, SurfaceRules.walkTop("minecraft:white_carpet"));
        assertEquals(1.5, SurfaceRules.walkTop("minecraft:oak_fence"));
        assertEquals(0.25, SurfaceRules.walkTop("minecraft:snow[layers=3]"));
        assertEquals(1.0, SurfaceRules.walkTop("minecraft:water[level=0]"));
        assertTrue(Double.isNaN(SurfaceRules.walkTop("minecraft:snow[layers=1]")));
        assertTrue(Double.isNaN(SurfaceRules.walkTop("minecraft:air")));
        assertTrue(Double.isNaN(SurfaceRules.walkTop("minecraft:oak_leaves")));
    }

    @Test
    void groundSkipAlsoPassesTreesAndLiquids() {
        for (String s : new String[]{"minecraft:oak_log[axis=y]", "minecraft:stripped_birch_wood[axis=x]", "minecraft:crimson_stem[axis=y]",
                "minecraft:red_mushroom_block", "minecraft:mangrove_roots[waterlogged=false]", "minecraft:water[level=0]",
                "minecraft:lava[level=0]", "minecraft:oak_leaves[persistent=false]", "minecraft:short_grass", "minecraft:air"}) {
            assertTrue(SurfaceRules.groundSkip(s, 3), s);
        }
        for (String s : new String[]{"minecraft:grass_block[snowy=false]", "minecraft:stone", "minecraft:obsidian", "minecraft:magma_block",
                "minecraft:oak_planks", "minecraft:sand"}) {
            assertFalse(SurfaceRules.groundSkip(s, 3), s);
        }
    }
}
