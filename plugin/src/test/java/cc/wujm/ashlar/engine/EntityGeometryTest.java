// SPDX-License-Identifier: AGPL-3.0-or-later
package cc.wujm.ashlar.engine;

import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/** Step 17: painting footprints and anchors, yaw transforms and the entity record. */
class EntityGeometryTest {

    @Test void viewerRightIsCounterClockwiseOfFacing() {
        assertEquals("east", EntityGeometry.right("south"));
        assertEquals("north", EntityGeometry.right("east"));
        assertEquals("west", EntityGeometry.right("north"));
        assertEquals("south", EntityGeometry.right("west"));
        assertThrows(IllegalArgumentException.class, () -> EntityGeometry.right("up"));
    }

    @Test void anchorMatchesVanillaOffsets() {
        int[] ll = {10, 64, 20};
        // Facing south, the viewer's right is +x; even sizes put the anchor on the lower/left of the two middle cells.
        assertArrayEquals(new int[]{10, 64, 20}, EntityGeometry.anchor(ll, 1, 1, "south"));
        assertArrayEquals(new int[]{10, 64, 20}, EntityGeometry.anchor(ll, 2, 1, "south"));
        assertArrayEquals(new int[]{11, 65, 20}, EntityGeometry.anchor(ll, 3, 3, "south"));
        assertArrayEquals(new int[]{11, 65, 20}, EntityGeometry.anchor(ll, 4, 3, "south"));
        assertArrayEquals(new int[]{11, 65, 20}, EntityGeometry.anchor(ll, 4, 4, "south"));
        // Facing west, the viewer's right is +z.
        assertArrayEquals(new int[]{10, 65, 21}, EntityGeometry.anchor(ll, 4, 3, "west"));
        // Facing north, the viewer's right is -x.
        assertArrayEquals(new int[]{9, 64, 20}, EntityGeometry.anchor(ll, 4, 2, "north"));
    }

    @Test void footprintRoundTripsThroughItsRange() {
        int[] ll = {-5, 70, 8};
        for (String f : EntityGeometry.HORIZONTAL) {
            for (int[] size : new int[][]{{1, 1}, {2, 1}, {1, 2}, {2, 2}, {4, 2}, {4, 3}, {4, 4}, {3, 3}}) {
                List<int[]> cells = EntityGeometry.cells(ll, size[0], size[1], f);
                assertEquals(size[0] * size[1], cells.size());
                int[] min = cells.getFirst().clone(), max = cells.getFirst().clone();
                for (int[] c : cells) for (int i = 0; i < 3; i++) { min[i] = Math.min(min[i], c[i]); max[i] = Math.max(max[i], c[i]); }
                assertArrayEquals(ll, EntityGeometry.lowerLeftFromRange(min, max, f), f + " " + size[0] + "x" + size[1]);
                int[] lowerRight = cells.get(size[0] - 1);
                assertArrayEquals(ll, EntityGeometry.lowerLeft(lowerRight, true, size[0], f));
                assertArrayEquals(ll, EntityGeometry.lowerLeft(ll, false, size[0], f));
            }
        }
    }

    @Test void supportIsBehindTheFacing() {
        assertArrayEquals(new int[]{0, 64, -1}, EntityGeometry.support(new int[]{0, 64, 0}, "south"));
        assertArrayEquals(new int[]{0, 63, 0}, EntityGeometry.support(new int[]{0, 64, 0}, "up"));
        assertArrayEquals(new int[]{0, 65, 0}, EntityGeometry.support(new int[]{0, 64, 0}, "down"));
    }

    @Test void yawFollowsMirrorThenClockwiseRotation() {
        assertEquals(90f, EntityGeometry.transformYaw(0f, 90, "none"), 1e-4);      // south -> west
        assertEquals(-180f, EntityGeometry.transformYaw(0f, 180, "none"), 1e-4);   // south -> north
        assertEquals(-90f, EntityGeometry.transformYaw(0f, 270, "none"), 1e-4);    // south -> east
        assertEquals(-90f, EntityGeometry.transformYaw(90f, 0, "x"), 1e-4);        // west -> east
        assertEquals(-180f, EntityGeometry.transformYaw(0f, 0, "z"), 1e-4);        // south -> north
        assertEquals(45f, EntityGeometry.transformYaw(45f, 0, "none"), 1e-4);
        assertEquals(-135f, EntityGeometry.transformYaw(45f, 180, "none"), 1e-4);
        assertEquals(0.0, EntityGeometry.wrapYaw(360), 1e-9);
        assertEquals(-170.0, EntityGeometry.wrapYaw(190), 1e-9);
    }

    @Test void specJsonRoundTripAndSameAs() {
        EntitySpec stand = new EntitySpec(EntitySpec.ARMOR_STAND, new int[]{1, 64, 2}, new double[]{1.25, 64, 2.75}, null, false, null, null, 0,
                false, true, 30f, Map.of("head", "minecraft:iron_helmet", "mainHand", "minecraft:torch"),
                Map.of("rightArm", new double[]{-90, 0, 0}), true, true, true);
        EntitySpec back = EntitySpec.fromJson(JsonParser.parseString(stand.json().toString()).getAsJsonObject());
        assertTrue(stand.sameAs(back));
        assertTrue(back.placed());
        assertTrue(stand.sameAs(back.withPlaced(false)), "placed is ignored");
        assertEquals("armor_stand@1,64,2", back.key());
        EntitySpec turned = new EntitySpec(EntitySpec.ARMOR_STAND, new int[]{1, 64, 2}, new double[]{1.25, 64, 2.75}, null, false, null, null, 0,
                false, true, 31f, stand.equipment(), stand.pose(), true, true, true);
        assertFalse(stand.sameAs(turned));

        EntitySpec painting = new EntitySpec(EntitySpec.PAINTING, new int[]{5, 70, 5}, null, "west", true, "minecraft:kebab", null, 0, false,
                false, 0f, null, null, false, false, false);
        EntitySpec p2 = EntitySpec.fromJson(painting.json());
        assertTrue(painting.sameAs(p2));
        assertTrue(p2.fromRight());
        assertEquals("painting@5,70,5/west", p2.key());
        assertFalse(painting.sameAs(new EntitySpec(EntitySpec.PAINTING, new int[]{5, 70, 5}, null, "west", true, "minecraft:aztec", null, 0,
                false, false, 0f, null, null, false, false, false)));

        EntitySpec frame = new EntitySpec(EntitySpec.GLOW_ITEM_FRAME, new int[]{0, 0, 0}, null, "up", false, null, "minecraft:clock", 3, true,
                false, 0f, null, null, false, false, true);
        EntitySpec f2 = EntitySpec.fromJson(frame.json());
        assertTrue(frame.sameAs(f2));
        assertEquals(3, f2.rotation());
        assertTrue(f2.fixed());
    }
}
