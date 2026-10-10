// SPDX-License-Identifier: AGPL-3.0-or-later
package cc.wujm.ashlar.render;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

class SightlineTest {

    /** A 1-wide wall at x=5 from z=-3..3, y=64..66, with a one-block gap at z=0, y=65. */
    private static VoxelRayTest.World wallWithGap() {
        VoxelRayTest.World w = new VoxelRayTest.World();
        for (int z = -3; z <= 3; z++) for (int y = 64; y <= 66; y++) if (!(z == 0 && y == 65)) w.set(5, y, z, "minecraft:stone_bricks");
        return w;
    }

    @Test
    void throughTheGapIsVisibleBesideItIsBlocked() {
        var w = wallWithGap();
        var through = Sightline.target(w, .5, 65.5, .5, new int[]{10, 65, 0}, List.of());
        assertEquals(Sightline.Status.VISIBLE, through.status());
        assertEquals(10.0, through.distance(), 1e-9);
        var beside = Sightline.target(w, .5, 65.5, .5, new int[]{10, 65, 2}, List.of());
        assertEquals(Sightline.Status.BLOCKED, beside.status());
        assertArrayEquals(new int[]{5, 65, 1}, beside.at());
        assertEquals("minecraft:stone_bricks", beside.state());
        // The target's own cell never blocks: a block in the wall itself is visible.
        assertEquals(Sightline.Status.VISIBLE, Sightline.target(w, .5, 65.5, .5, new int[]{5, 65, 1}, List.of()).status());
    }

    @Test
    void aGroundBlockIsVisibleByItsTopFace() {
        var w = new VoxelRayTest.World();
        for (int x = -2; x <= 10; x++) for (int z = -2; z <= 2; z++) w.set(x, 63, z, "minecraft:grass_block[snowy=false]");
        // Its center is hidden behind the neighbouring ground, its top face is not.
        var r = Sightline.target(w, .5, 65.62, .5, new int[]{6, 63, 0}, List.of());
        assertEquals(Sightline.Status.VISIBLE, r.status());
        // Buried one block deeper, every face toward the eye is covered.
        w.set(6, 62, 0, "minecraft:stone");
        assertEquals(Sightline.Status.BLOCKED, Sightline.target(w, .5, 65.62, .5, new int[]{6, 62, 0}, List.of()).status());
    }

    @Test
    void glassDoesNotBlockLeavesDoUnlessIgnored() {
        var w = new VoxelRayTest.World().set(3, 64, 0, "minecraft:glass").set(6, 64, 0, "minecraft:oak_leaves[distance=1,persistent=true]");
        var r = Sightline.target(w, .5, 64.5, .5, new int[]{10, 64, 0}, List.of());
        assertEquals(Sightline.Status.BLOCKED, r.status());
        assertEquals(6, r.at()[0]);
        var ignored = Sightline.target(w, .5, 64.5, .5, new int[]{10, 64, 0}, List.of(Sightline.Ignore.parse("*_leaves")));
        assertEquals(Sightline.Status.VISIBLE, ignored.status());
    }

    @Test
    void ignorePatternsMatchNamesAndNumericProperties() {
        var snow = Sightline.Ignore.parse("snow[layers<=3]");
        assertTrue(snow.matches("minecraft:snow[layers=3]"));
        assertFalse(snow.matches("minecraft:snow[layers=4]"));
        assertFalse(snow.matches("minecraft:snow_block"));
        assertTrue(Sightline.Ignore.parse("minecraft:oak_fence").matches("minecraft:oak_fence[east=true,north=false]"));
        assertTrue(Sightline.Ignore.parse("oak_slab[type=top]").matches("minecraft:oak_slab[type=top,waterlogged=false]"));
        assertThrows(IllegalArgumentException.class, () -> Sightline.Ignore.parse("*"));
        assertThrows(IllegalArgumentException.class, () -> Sightline.Ignore.parse("snow[layers<=many]"));
        assertThrows(IllegalArgumentException.class, () -> Sightline.Ignore.parse("Bad Name!"));
    }

    @Test
    void unloadedChunksAnswerUnknownNotVisible() {
        var w = new VoxelRayTest.World().unload(1, 0);
        var r = Sightline.target(w, .5, 64.5, .5, new int[]{40, 64, 0}, List.of());
        assertEquals(Sightline.Status.UNKNOWN, r.status());
        assertEquals(16, r.at()[0]);
    }

    @Test
    void coneGridSeesTheWallAndTheGap() {
        var w = wallWithGap();
        // Looking east (yaw -90) at the wall from 4.5 blocks away.
        Sightline.ConeHit[][] g = Sightline.cone(w, .5, 65.5, .5, -90, 0, 20, 5, 20, List.of());
        assertNull(g[2][2], "the center ray goes through the gap");
        assertNotNull(g[2][0]);
        assertEquals(5, g[2][0].at()[0]);
    }

    @Test
    void cameraBasisAndChunkFootprint() {
        FirstPersonCamera cam = new FirstPersonCamera(.5, 65, .5, -90, 0, 70, 64, 320, 180);
        double[][] b = cam.basis();
        assertArrayEquals(new double[]{1, 0, 0}, b[0], 1e-9);
        assertArrayEquals(new double[]{0, 0, 1}, b[1], 1e-9, "right of east-facing is south");
        assertArrayEquals(new double[]{0, 1, 0}, b[2], 1e-9);
        double[] center = cam.ray(b, 159.5, 89.5);
        assertArrayEquals(new double[]{1, 0, 0}, center, 1e-9);
        Set<Long> chunks = cam.chunks();
        assertTrue(chunks.contains(FirstPersonCamera.pack(0, 0)));
        assertTrue(chunks.contains(FirstPersonCamera.pack(3, 0)), "64 blocks east");
        assertFalse(chunks.contains(FirstPersonCamera.pack(-3, 0)), "nothing far behind the eye");
        // Looking straight down wraps the whole circle around the eye.
        Set<Long> down = new FirstPersonCamera(.5, 65, .5, 0, 90, 70, 32, 320, 180).chunks();
        assertTrue(down.contains(FirstPersonCamera.pack(-2, 0)) && down.contains(FirstPersonCamera.pack(2, 0)));
    }

    @Test
    void cameraArgumentsAreValidated() {
        assertThrows(IllegalArgumentException.class, () -> new FirstPersonCamera(0, 64, 0, 0, 91, 70, 64, 320, 180));
        assertThrows(IllegalArgumentException.class, () -> new FirstPersonCamera(0, 64, 0, 0, 0, 120, 64, 320, 180));
        assertThrows(IllegalArgumentException.class, () -> new FirstPersonCamera(0, 64, 0, 0, 0, 70, 300, 320, 180));
        assertThrows(IllegalArgumentException.class, () -> new FirstPersonCamera(0, 64, 0, 0, 0, 70, 64, 1920, 1080));
        assertThrows(IllegalArgumentException.class, () -> new FirstPersonCamera(Double.NaN, 64, 0, 0, 0, 70, 64, 320, 180));
    }

    @Test
    void firstPersonRenderDrawsWallSkyAndUnknown() {
        var w = wallWithGap().unload(-1, 0);
        FirstPersonCamera cam = new FirstPersonCamera(.5, 65.5, .5, -90, 0, 70, 32, 128, 72);
        var out = FirstPersonRenderer.render(w, id -> 0xFF808080, cam);
        assertEquals(128 * 72, out.image().pixels().length);
        assertTrue(out.details().get("skyPixels").getAsLong() > 0);
        assertEquals("minecraft:stone_bricks", out.image().legend().get(0).block());
        assertEquals(0, out.details().get("unknownPixels").getAsLong(), "the unloaded chunk is behind the eye");
        var back = FirstPersonRenderer.render(w, id -> 0xFF808080, new FirstPersonCamera(.5, 65.5, .5, 90, 0, 70, 32, 128, 72));
        assertTrue(back.details().get("unknownPixels").getAsLong() > 0);
    }
}
