// SPDX-License-Identifier: AGPL-3.0-or-later
package cc.wujm.ashlar.tool.mc;

import cc.wujm.ashlar.tool.ToolArgError;
import org.junit.jupiter.api.Test;

import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class DiffIgnoreTest {
    @Test void materialAndPropertyGlobs() {
        var ig = DiffIgnore.parse(List.of("*_leaves", "snow[layers=1]", "minecraft:oak_*[*]"));
        assertTrue(ig.test("minecraft:oak_leaves[distance=7,persistent=false,waterlogged=false]"));
        assertTrue(ig.test("minecraft:snow[layers=1]"));
        assertFalse(ig.test("minecraft:snow[layers=2]"));
        assertTrue(ig.test("minecraft:oak_stairs[facing=east,half=top,shape=straight,waterlogged=false]"));
        assertFalse(ig.test("minecraft:stone"));
        assertFalse(DiffIgnore.NONE.test("minecraft:oak_leaves"));
    }

    @Test void invalidGlobsAreRejected() {
        assertThrows(ToolArgError.class, () -> DiffIgnore.parse(List.of("stone[")));
        assertThrows(ToolArgError.class, () -> DiffIgnore.parse(List.of("../x")));
        assertThrows(ToolArgError.class, () -> DiffIgnore.parse(Collections.nCopies(33, "stone")));
    }
}
