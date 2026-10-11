// SPDX-License-Identifier: AGPL-3.0-or-later
package cc.wujm.ashlar.tool.mc;

import cc.wujm.ashlar.tool.ToolArgError;
import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SplitBoxesTest {

    /** Every piece fits, the pieces do not overlap, and together they cover the box exactly. */
    private static void assertTiles(int[] lo, int[] hi, long max) {
        List<int[][]> boxes = SplitBoxes.split(lo, hi, max);
        Set<List<Integer>> seen = new HashSet<>();
        long total = 1;
        for (int i = 0; i < lo.length; i++) total *= hi[i] - lo[i] + 1L;
        for (int[][] b : boxes) {
            long v = 1;
            for (int i = 0; i < lo.length; i++) v *= b[1][i] - b[0][i] + 1L;
            assertTrue(v <= max, "piece over the limit");
            if (lo.length == 3) {
                for (int x = b[0][0]; x <= b[1][0]; x++) for (int y = b[0][1]; y <= b[1][1]; y++) for (int z = b[0][2]; z <= b[1][2]; z++) {
                    assertTrue(seen.add(List.of(x, y, z)), "overlap");
                }
            } else {
                for (int x = b[0][0]; x <= b[1][0]; x++) for (int z = b[0][1]; z <= b[1][1]; z++) assertTrue(seen.add(List.of(x, z)), "overlap");
            }
        }
        assertEquals(total, seen.size());
    }

    @Test
    void piecesTileTheBoxWithinTheLimit() {
        assertTiles(new int[] {0, 60, 0}, new int[] {99, 79, 149}, 200_000);   // 300,000: slabs along z
        assertTiles(new int[] {-5, 0, 3}, new int[] {700, 0, 700}, 200_000);   // flat, wide
        assertTiles(new int[] {0, 0}, new int[] {600, 400}, 200_000);          // 2D survey
        assertTiles(new int[] {0, 0, 0}, new int[] {9, 9, 9}, 7);              // cross-section over the limit: halving
    }

    @Test
    void slabsUseTheFewestCallsWhenTheCrossSectionFits() {
        assertEquals(2, SplitBoxes.split(new int[] {0, 60, 0}, new int[] {99, 79, 149}, 200_000).size());
    }

    @Test
    void errorsNameTheSplitBoxes() {
        ToolArgError e = assertThrows(ToolArgError.class, () -> McInspect.checkVolume(new int[] {0, 60, 0}, new int[] {99, 79, 149}));
        assertEquals("mc_inspect region volume 300000 exceeds the 200,000-block limit. Reduce the from/to range, or split it into 2 calls: "
                + "[0,60,0]..[99,79,99], [0,60,100]..[99,79,149].", e.getMessage());
        e = assertThrows(ToolArgError.class, () -> McSurvey.checkArea(new int[] {0, 0}, new int[] {999, 999}));
        assertTrue(e.getMessage().contains("split it into 5 calls: [0,0]..[199,999], [200,0]..[399,999], [400,0]..[599,999], [600,0]..[799,999] and 1 more"), e.getMessage());
        e = assertThrows(ToolArgError.class, () -> McSnapshot.checkVolume(new int[] {0, -64, 0}, new int[] {9999, 319, 9999}));
        assertTrue(e.getMessage().endsWith("Reduce the from/to range: it is about 192000 times the limit."), e.getMessage());
        McRender.checkVolume(new int[] {0, 0, 0}, new int[] {99, 19, 99});
    }
}
