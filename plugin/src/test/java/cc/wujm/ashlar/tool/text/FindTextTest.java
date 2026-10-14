// SPDX-License-Identifier: AGPL-3.0-or-later
package cc.wujm.ashlar.tool.text;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Unit tests for {@link FindText} (mc_inspect find). */
class FindTextTest {

    @Test
    void matchesTheIdWithoutPropertiesAndGroupsByState() {
        List<String> palette = List.of("minecraft:lantern[hanging=true]", "minecraft:light[level=15]", "minecraft:air", "minecraft:light_gray_concrete");
        int[][] runs = {{0, 1}, {1, 1}, {2, 1}, {3, 1}};
        BlockGrid decoded = BlockGrid.decodeRegionDataJson(new int[]{0, 5, 0}, new int[]{1, 6, 0}, palette, runs, 4);

        String expected = String.join("\n",
                "Find /lantern|^light$/ in (default) x=[0..1] y=[5..6] z=[0..0]: 2 blocks in 2 states.",
                "  lantern[hanging=true] (1): 0,5,0",
                "  light[level=15] (1): 1,5,0");
        assertEquals(expected, FindText.renderFind(decoded, Pattern.compile("lantern|^light$"), "(default)"));
    }

    @Test
    void listsAtMostTheCapButCountsEverything() {
        int n = FindText.MAX_LISTED + 3;
        BlockGrid decoded = BlockGrid.decodeRegionDataJson(new int[]{0, 0, 0}, new int[]{n - 1, 0, 0}, List.of("minecraft:torch"), new int[][]{{0, n}}, n);
        String text = FindText.renderFind(decoded, Pattern.compile("torch"), "w");
        assertTrue(text.contains(": " + n + " blocks in 1 state."));
        assertTrue(text.contains(" +3 more"));
        assertTrue(text.endsWith("narrow the box or the pattern for the rest."));
    }
}
