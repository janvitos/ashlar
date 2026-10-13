// SPDX-License-Identifier: AGPL-3.0-or-later
package cc.wujm.ashlar.tool.mc;

import cc.wujm.ashlar.engine.CompositionScan;
import cc.wujm.ashlar.engine.Region;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CompositionCheckTest {

    @Test
    void oneLinePerRuleWithCountAndExample() {
        Region box = new Region(0, 0, 0, 7, 3, 7);
        List<String> palette = List.of("minecraft:air", "minecraft:stone_bricks",
                "minecraft:iron_bars[east=false,north=false,south=false,waterlogged=false,west=false]");
        int[] grid = new int[Math.toIntExact(box.volume())];
        for (int i = 0; i < 64; i++) grid[i] = 1; // floor at y = 0
        grid[(8 + 2) * 8 + 2] = 2; // two lone bars at y = 1
        grid[(8 + 5) * 8 + 5] = 2;
        grid[(8 + 2) * 8 + 3] = 1; // walls beside both, so they should have joined
        grid[(8 + 5) * 8 + 6] = 1;
        CompositionScan scan = new CompositionScan(Set.of(), 1);
        scan.scan(box, box, palette, grid);
        List<String> lines = CompositionCheck.format(scan);
        assertEquals(2, lines.size(), String.join("\n", lines));
        assertTrue(lines.get(1).startsWith("  lone-post x2, e.g. 2,1,2: "), lines.get(1));
    }

    @Test
    void nothingFoundIsSilent() {
        assertTrue(CompositionCheck.format(new CompositionScan(Set.of(), 1)).isEmpty());
    }

    @Test
    void unionCoversEveryBox() {
        int[][] u = CompositionCheck.union(List.of(new int[][] {{0, 5, 0}, {3, 6, 3}}, new int[][] {{-2, 1, 4}, {1, 2, 9}}));
        assertArrayEquals(new int[] {-2, 1, 0}, u[0]);
        assertArrayEquals(new int[] {3, 6, 9}, u[1]);
    }
}
