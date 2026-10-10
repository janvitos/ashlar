// SPDX-License-Identifier: AGPL-3.0-or-later
package cc.wujm.ashlar.engine;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class StateCanonTest {
    private final StateCanon canon = new StateCanon(m -> switch (m) {
        case "light" -> Map.of("level", "15", "waterlogged", "false");
        case "oak_stairs" -> Map.of("facing", "north", "half", "bottom", "shape", "straight", "waterlogged", "false");
        case "stone", "air" -> Map.of();
        default -> null;
    });

    @Test void defaultValuedPropertiesAreDropped() {
        assertEquals(canon.canon("light[level=11]"), canon.canon("minecraft:light[level=11,waterlogged=false]"));
        assertEquals("light[level=11]", canon.canon("light[waterlogged=false,level=11]"));
        assertEquals("light", canon.canon("light[level=15]"));
    }

    @Test void propertiesAreSortedAndCaseFolded() {
        assertEquals("oak_stairs[half=top,shape=outer_left]", canon.canon("Minecraft:OAK_STAIRS[shape=outer_left,half=top,facing=north]"));
        assertEquals("stone", canon.canon("minecraft:stone[]"));
    }

    @Test void invalidInputIsRejected() {
        assertThrows(IllegalArgumentException.class, () -> canon.canon("glowstone_brick"));
        assertThrows(IllegalArgumentException.class, () -> canon.canon("light[colour=red]"));
        assertThrows(IllegalArgumentException.class, () -> canon.canon("light[level=1,level=2]"));
        assertThrows(IllegalArgumentException.class, () -> canon.canon("light[level=1"));
        assertThrows(IllegalArgumentException.class, () -> canon.canon("light[level]"));
    }

    @Test void materialAndAirHelpers() {
        assertEquals("cave_air", StateCanon.material("minecraft:cave_air"));
        assertTrue(StateCanon.isAir("void_air"));
        assertFalse(StateCanon.isAir("light"));
    }
}
