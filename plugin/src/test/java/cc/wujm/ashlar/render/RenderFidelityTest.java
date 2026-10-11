// SPDX-License-Identifier: AGPL-3.0-or-later
package cc.wujm.ashlar.render;

import cc.wujm.ashlar.engine.Region;
import cc.wujm.ashlar.engine.RegionData;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/** Step 20: new shape families, per-face colours, snapshot diff overlay and entity placeholders. */
class RenderFidelityTest {
    private static final double E = 1e-9;

    private static BlockShapes.Shape s(String state) {
        return BlockShapes.resolve(state);
    }

    private static double[] bounds(BlockShapes.Shape sh) {
        double[] b = {1, 1, 1, 0, 0, 0};
        for (var q : sh.boxes()) {
            b[0] = Math.min(b[0], q.x0()); b[1] = Math.min(b[1], q.y0()); b[2] = Math.min(b[2], q.z0());
            b[3] = Math.max(b[3], q.x1()); b[4] = Math.max(b[4], q.y1()); b[5] = Math.max(b[5], q.z1());
        }
        return b;
    }

    private static void notFallback(String state) {
        assertNotEquals("cube_fallback", s(state).fidelity(), state);
        assertFalse(s(state).boxes().isEmpty(), state);
    }

    @Test
    void lanternsHangOrStand() {
        double[] stand = bounds(s("minecraft:lantern[hanging=false]")), hang = bounds(s("minecraft:soul_lantern[hanging=true]"));
        assertEquals(0, stand[1], E);
        assertTrue(stand[4] < .6);
        assertEquals(1, hang[4], E);
        assertTrue(hang[1] > 0);
        notFallback("minecraft:copper_lantern[hanging=false]");
        assertEquals(List.of(BlockShapes.CUBE), s("minecraft:sea_lantern").boxes());
        assertEquals(List.of(BlockShapes.CUBE), s("minecraft:jack_o_lantern[facing=north]").boxes());
    }

    @Test
    void chainsFollowTheirAxis() {
        double[] y = bounds(s("minecraft:iron_chain[axis=y]")), x = bounds(s("minecraft:iron_chain[axis=x]")), z = bounds(s("minecraft:copper_chain[axis=z]"));
        assertEquals(1, y[4] - y[1], E);
        assertEquals(1, x[3] - x[0], E);
        assertTrue(x[4] - x[1] < .25);
        assertEquals(1, z[5] - z[2], E);
    }

    @Test
    void panesAndBarsStayThin() {
        double[] pane = bounds(s("minecraft:glass_pane[east=true,west=true]"));
        assertEquals(1, pane[3] - pane[0], E);
        assertEquals(2.0 / 16, pane[5] - pane[2], E);
        var bars = s("minecraft:copper_bars[north=true,south=true]");
        assertEquals(1, bars.opacity(), E);
        assertEquals(2.0 / 16, bounds(bars)[3] - bounds(bars)[0], E);
    }

    @Test
    void rodsPointAlongFacing() {
        double[] up = bounds(s("minecraft:end_rod[facing=up]")), north = bounds(s("minecraft:end_rod[facing=north]")), east = bounds(s("minecraft:lightning_rod[facing=east]"));
        assertEquals(1, up[4] - up[1], E);
        assertEquals(1, north[5] - north[2], E);
        assertTrue(north[4] - north[1] < .3);
        assertEquals(1, east[3] - east[0], E);
        // The end rod's base plate sits on the attached side: facing north attaches to the south face.
        var base = BlockShapes.toward("north", new BlockShapes.Box(6 / 16.0, 0, 6 / 16.0, 10 / 16.0, 1 / 16.0, 10 / 16.0));
        assertEquals(1, base.z1(), E);
    }

    @Test
    void candlesCountUp() {
        for (int n = 1; n <= 4; n++) assertEquals(n, s("minecraft:red_candle[candles=" + n + "]").boxes().size());
        assertTrue(bounds(s("minecraft:candle[candles=1]"))[4] < .5);
    }

    @Test
    void bellsDifferByAttachment() {
        var floor = s("minecraft:bell[attachment=floor,facing=north]");
        var ceiling = s("minecraft:bell[attachment=ceiling,facing=north]");
        assertEquals(0, bounds(floor)[1], E);
        assertTrue(bounds(ceiling)[1] > .2);
        assertNotEquals(floor.boxes(), s("minecraft:bell[attachment=floor,facing=east]").boxes());
        notFallback("minecraft:bell[attachment=double_wall,facing=west]");
    }

    @Test
    void campfiresAreLowAndTurn() {
        var n = s("minecraft:campfire[facing=north]");
        assertTrue(bounds(n)[4] < .5);
        assertNotEquals(n.boxes(), s("minecraft:soul_campfire[facing=east]").boxes());
    }

    @Test
    void dripstoneThicknessAndDirection() {
        double[] tipUp = bounds(s("minecraft:pointed_dripstone[thickness=tip,vertical_direction=up]"));
        double[] tipDown = bounds(s("minecraft:pointed_dripstone[thickness=tip,vertical_direction=down]"));
        double[] base = bounds(s("minecraft:pointed_dripstone[thickness=base,vertical_direction=up]"));
        assertEquals(0, tipUp[1], E);
        assertEquals(1, tipDown[4], E);
        assertTrue(tipDown[1] > 0);
        assertTrue(base[3] - base[0] > tipUp[3] - tipUp[0]);
    }

    @Test
    void pillarsAreAxisAware() {
        assertEquals(1, BlockShapes.axis("minecraft:oak_log[axis=y]"));
        assertEquals(0, BlockShapes.axis("minecraft:basalt[axis=x]"));
        assertEquals(2, BlockShapes.axis("minecraft:quartz_pillar[axis=z]"));
        assertEquals(-1, BlockShapes.axis("minecraft:oak_wood[axis=x]"));
        assertEquals(-1, BlockShapes.axis("minecraft:nether_portal[axis=x]"));
        assertEquals("modeled", s("minecraft:polished_basalt[axis=z]").fidelity());
    }

    @Test
    void wallsKeepPostAndSideHeights() {
        var low = s("minecraft:cobblestone_wall[up=false,north=low,south=low,east=none,west=none]");
        var tall = s("minecraft:cobblestone_wall[up=true,north=tall,south=none,east=none,west=none]");
        assertEquals(14.0 / 16, bounds(low)[4], E);
        assertEquals(2, low.boxes().size());
        assertEquals(1, bounds(tall)[4], E);
        assertEquals(2, tall.boxes().size());
    }

    @Test
    void buttonsAndLeversSitOnTheirFace() {
        double[] wall = bounds(s("minecraft:stone_button[face=wall,facing=north,powered=false]"));
        double[] pressed = bounds(s("minecraft:stone_button[face=wall,facing=north,powered=true]"));
        double[] floor = bounds(s("minecraft:oak_button[face=floor,facing=east,powered=false]"));
        assertEquals(1, wall[5], E);
        assertTrue(pressed[5] - pressed[2] < wall[5] - wall[2]);
        assertEquals(0, floor[1], E);
        assertEquals(0, bounds(s("minecraft:lever[face=wall,facing=south,powered=false]"))[2], E);
        assertEquals(1, bounds(s("minecraft:lever[face=ceiling,facing=north,powered=false]"))[4], E);
    }

    @Test
    void potsAndHeads() {
        assertEquals(1, s("minecraft:flower_pot").boxes().size());
        assertEquals(2, s("minecraft:potted_poppy").boxes().size());
        assertEquals(.5, bounds(s("minecraft:skeleton_skull[rotation=0]"))[4], E);
        assertEquals(1, bounds(s("minecraft:zombie_wall_head[facing=north]"))[5], E);
        assertEquals("cube_fallback", s("minecraft:piston_head[facing=up]").fidelity());
    }

    @Test
    void rotationMatchesWallSigns() {
        var q = new BlockShapes.Box(0, .25, 15 / 16.0, 1, .75, 1);
        assertEquals(0, BlockShapes.rot("east", q).x0(), E);
        assertEquals(0, BlockShapes.rot("south", q).z0(), E);
        assertEquals(1, BlockShapes.rot("west", q).x1(), E);
    }

    @Test
    void entityPanelsHangOnTheBackFace() {
        assertEquals(1, BlockShapes.entityPanel("painting", "north").z1(), E);
        assertEquals(1, BlockShapes.entityPanel("painting", "north").x1() - BlockShapes.entityPanel("painting", "north").x0(), E);
        assertEquals(0, BlockShapes.entityPanel("item_frame", "east").x0(), E);
        assertEquals(0, BlockShapes.entityPanel("item_frame", "up").y0(), E);
        assertEquals(1, BlockShapes.entityPanel("item_frame", "down").y1(), E);
    }

    @Test
    void faceColorsForPillarsAndGrass() {
        java.util.function.ToIntFunction<String> map = st -> switch (st) {
            case "minecraft:oak_log[axis=y]" -> 0xFF8F7748;
            case "minecraft:oak_log[axis=x]" -> 0xFF664C33;
            case "minecraft:dirt" -> 0xFF976D4D;
            default -> 0xFF191919;
        };
        int[] log = BlockShapes.faceColors("minecraft:oak_log[axis=x]", 0xFF664C33, map);
        assertArrayEquals(new int[]{0xFF8F7748, 0xFF664C33, 0xFF664C33}, log);
        int[] basalt = BlockShapes.faceColors("minecraft:basalt[axis=z]", 0xFF191919, map);
        assertEquals(0xFF191919, basalt[0]);
        assertNotEquals(basalt[0], basalt[2]);
        assertEquals(BlockShapes.lighten(0xFF191919, .22), basalt[2]);
        assertArrayEquals(new int[]{0xFF976D4D, 0xFF7FB238, 0xFF976D4D}, BlockShapes.faceColors("minecraft:grass_block[snowy=false]", 0xFF7FB238, map));
        assertNull(BlockShapes.faceColors("minecraft:stone", 0xFF707070, map));
    }

    @Test
    void sidewaysBasaltShowsLighterEnds() {
        var d = new RegionData(new Region(0, 0, 0, 0, 0, 0), List.of("minecraft:basalt[axis=x]"), new int[]{0}, new int[]{1});
        int[] c = {0xFF191919};
        int[][] faces = {BlockShapes.faceColors("minecraft:basalt[axis=x]", c[0], st -> 0xFF191919)};
        var plain = ShapeRenderer.render(d, c, "isometric", RenderCamera.defaults("isometric"), 16);
        var faced = ShapeRenderer.render(d, c, "isometric", RenderCamera.defaults("isometric"), 16, new ShapeRenderer.Extras(faces, null, null));
        assertFalse(Arrays.equals(plain.image().pixels(), faced.image().pixels()));
    }

    @Test
    void snapshotDiffMarksCells() {
        // Region 3x1x1 at x=0..2; snapshot covers x=1..3.
        var now = new RegionData(new Region(0, 0, 0, 2, 0, 0), List.of("minecraft:stone", "minecraft:air", "minecraft:oak_planks"), new int[]{0, 1, 2}, new int[]{1, 1, 1});
        var before = new RegionData(new Region(1, 0, 0, 3, 0, 0), List.of("minecraft:dirt", "minecraft:air"), new int[]{0, 1}, new int[]{2, 1});
        assertArrayEquals(new byte[]{SnapshotDiff.OUTSIDE, SnapshotDiff.REMOVED, SnapshotDiff.CHANGED}, SnapshotDiff.compute(now, before));
        assertEquals(SnapshotDiff.ADDED, SnapshotDiff.mark("minecraft:cave_air", "minecraft:stone"));
        assertEquals(SnapshotDiff.SAME, SnapshotDiff.mark("minecraft:air", "minecraft:cave_air"));
    }

    @Test
    void snapshotDiffHandlesThreeDimensions() {
        var now = new RegionData(new Region(5, 10, 5, 6, 11, 6), List.of("minecraft:stone"), new int[]{0}, new int[]{8});
        var before = new RegionData(new Region(4, 10, 4, 6, 11, 6), List.of("minecraft:stone", "minecraft:air"), new int[]{0, 1, 0}, new int[]{9, 1, 8});
        byte[] marks = SnapshotDiff.compute(now, before);
        // Snapshot cell 9 is (x=4, y=11, z=4) -> outside the render region; only stone-vs-stone overlaps.
        for (byte m : marks) assertEquals(SnapshotDiff.SAME, m);
        var before2 = new RegionData(new Region(5, 10, 5, 6, 11, 6), List.of("minecraft:stone", "minecraft:air"), new int[]{0, 1, 0}, new int[]{6, 1, 1});
        byte[] m2 = SnapshotDiff.compute(now, before2);
        assertEquals(SnapshotDiff.ADDED, m2[6]);
        assertEquals(1, java.util.stream.IntStream.range(0, 8).filter(i -> m2[i] != SnapshotDiff.SAME).count());
    }

    @Test
    void diffOverlayTintsAndGhostsAndCounts() {
        var d = new RegionData(new Region(0, 0, 0, 1, 0, 0), List.of("minecraft:stone", "minecraft:air"), new int[]{0, 1}, new int[]{1, 1});
        int[] c = {0xFF707070, 0};
        var plain = ShapeRenderer.render(d, c, "isometric", RenderCamera.defaults("isometric"), 16);
        var diffed = ShapeRenderer.render(d, c, "isometric", RenderCamera.defaults("isometric"), 16,
                new ShapeRenderer.Extras(null, new byte[]{SnapshotDiff.CHANGED, SnapshotDiff.REMOVED}, null));
        assertFalse(Arrays.equals(plain.image().pixels(), diffed.image().pixels()));
        var info = diffed.details().getAsJsonObject("diff");
        assertEquals(1, info.get("changed").getAsLong());
        assertEquals(1, info.get("removed").getAsLong());
        assertTrue(diffed.image().legend().stream().anyMatch(l -> l.block().equals("diff:removed")));
        assertThrows(IllegalArgumentException.class, () -> ShapeRenderer.render(d, c, "isometric", RenderCamera.defaults("isometric"), 16,
                new ShapeRenderer.Extras(null, new byte[1], null)));
    }

    @Test
    void placeholdersDrawInAirCells() {
        var d = new RegionData(new Region(0, 0, 0, 0, 0, 0), List.of("minecraft:air"), new int[]{0}, new int[]{1});
        var o = ShapeRenderer.render(d, new int[]{0}, "isometric", RenderCamera.defaults("isometric"), 16,
                new ShapeRenderer.Extras(null, null, List.of(new ShapeRenderer.Placeholder(0, 0, 0, "painting", "west"), new ShapeRenderer.Placeholder(5, 0, 0, "item_frame", "up"))));
        assertEquals("entity:painting", o.image().legend().getFirst().block());
        assertEquals(2, o.details().get("entityPlaceholderCells").getAsInt());
    }
}
