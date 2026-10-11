// SPDX-License-Identifier: AGPL-3.0-or-later
package cc.wujm.ashlar.tool.mc;

import cc.wujm.ashlar.tool.text.BlockGrid;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ExteriorScanTest {

    /**
     * 7x6x7 box at the origin: a stone ground layer (y=0), a hollow 5x4x5 stone cube on it
     * (x,z 1..5, y 1..4, air room x,z 2..4 y 2..3), and one floating dirt block at [0,4,0].
     */
    private static BlockGrid scene() {
        List<int[]> runs = new ArrayList<>();
        for (int y = 0; y <= 5; y++) for (int z = 0; z <= 6; z++) for (int x = 0; x <= 6; x++) {
            boolean cube = x >= 1 && x <= 5 && z >= 1 && z <= 5 && y >= 1 && y <= 4;
            boolean room = x >= 2 && x <= 4 && z >= 2 && z <= 4 && y >= 2 && y <= 3;
            int p = y == 0 || (cube && !room) ? 1 : x == 0 && y == 4 && z == 0 ? 2 : 0;
            runs.add(new int[] {p, 1});
        }
        return BlockGrid.decodeRegionDataJson(new int[] {0, 0, 0}, new int[] {6, 5, 6},
                List.of("minecraft:air", "minecraft:stone", "minecraft:dirt"), runs.toArray(new int[0][]), 7 * 6 * 7);
    }

    @Test
    void countsTheOutsideSurfaceTheSealedRoomAndTheStrayBlock() {
        ExteriorScan.Result r = ExteriorScan.scan(scene());
        assertEquals(49 + 82 + 1, r.nonAir());
        // Cube shell 82 minus the 9 floor cells under the sealed room, ground not under the cube 24, the dirt block 1.
        assertEquals(98, r.exterior());
        assertEquals(97L, r.exteriorMaterials().get("stone"));
        assertEquals(18, r.enclosedAir());
        assertEquals(1, r.pieceCount());
        ExteriorScan.Piece p = r.pieces().getFirst();
        assertEquals("dirt", p.block());
        assertEquals(List.of(0, 4, 0), List.of(p.min()[0], p.min()[1], p.min()[2]));
    }

    @Test
    void textReportsEachPart() {
        BlockGrid g = scene();
        String text = ExteriorScan.text(ExteriorScan.scan(g), g, "world");
        assertTrue(text.contains("98 of 132 non-air blocks are seen from outside."), text);
        assertTrue(text.contains("Exterior materials: stone 97 (99.0%), dirt 1 (1.0%)."), text);
        assertTrue(text.contains("Enclosed air: 18 cells sealed from outside"), text);
        assertTrue(text.contains("  1x dirt [0,4,0]..[0,4,0]"), text);
    }

    @Test
    void airIdsAndMaterialNames() {
        assertTrue(ExteriorScan.isAir("minecraft:cave_air"));
        assertEquals("oak_stairs", ExteriorScan.material("minecraft:oak_stairs[facing=east]"));
    }
}
