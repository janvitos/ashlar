// SPDX-License-Identifier: AGPL-3.0-or-later
package cc.wujm.ashlar.tool.mc;

import cc.wujm.ashlar.tool.ToolArgError;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/** {@code mc_build heightfield}: parsing and the cells each column gets from its live ground. */
class HeightfieldTest {

    private static Heightfield.Spec spec(String heightfield) {
        return Heightfield.parse(JsonParser.parseString("{\"heightfield\":" + heightfield + "}").getAsJsonObject());
    }

    private static String at(CellSet set, int x, int y, int z) {
        int i = set.find(CellSet.key(x, y, z));
        return i < 0 ? null : set.state(i);
    }

    @Test
    void raisesLowersAndClearsColumns() {
        // Column 0: grass at 64, raised to 67. Column 1: a tree on grass at 64 (top 70), lowered to 60.
        Heightfield.Spec s = spec("{\"from\":[10,20],\"width\":2,\"heights\":[67,60],\"top\":\"grass_block\",\"under\":\"dirt\",\"fill\":\"stone\"}");
        Heightfield.Ground g = new Heightfield.Ground(new int[]{64, 70}, new int[]{64, 64},
                new String[]{"minecraft:grass_block[snowy=false]", "minecraft:oak_leaves[distance=1]"});
        CellSet.Builder b = new CellSet.Builder(1000, "test");
        Heightfield.Stats st = Heightfield.cells(s, g, b, 1000);
        CellSet set = b.build();

        assertEquals("minecraft:grass_block", at(set, 10, 67, 20));
        for (int y = 64; y <= 66; y++) assertEquals("minecraft:dirt", at(set, 10, y, 20));
        assertNull(at(set, 10, 63, 20), "below the old ground and the under layers stays untouched");
        assertNull(at(set, 10, 68, 20));

        assertEquals("minecraft:grass_block", at(set, 11, 60, 20));
        for (int y = 57; y <= 59; y++) assertEquals("minecraft:dirt", at(set, 11, y, 20));
        for (int y = 61; y <= 70; y++) assertEquals("minecraft:air", at(set, 11, y, 20), "y=" + y);
        assertNull(at(set, 11, 56, 20));

        assertEquals(4 + 4 + 10, st.cells());
        assertEquals(set.size(), st.cells());
        assertEquals("Heightfield: 2 columns (0 skipped): 1 raised (up to +3), 1 lowered (up to -4), 0 level.", st.line());
    }

    @Test
    void keepsLiquidsSkipsNullsAndPaintsSteepColumns() {
        // Column 0: lake, water top at 62 over a bed at 58, lowered to 55. Column 1: null, ground at 57.
        // Column 2: grass at 64 raised to 70, 6 above column 3 (level at 64) so it is steep.
        Heightfield.Spec s = spec("{\"from\":[0,0],\"width\":4,\"heights\":[55,null,70,64],\"top\":\"grass_block\",\"under\":\"dirt\","
                + "\"underDepth\":1,\"fill\":\"stone\",\"steep\":\"andesite\",\"steepAt\":4,\"clearAbove\":true}");
        Heightfield.Ground g = new Heightfield.Ground(new int[]{62, 57, 64, 64}, new int[]{58, 57, 64, 64},
                new String[]{"minecraft:water[level=0]", "minecraft:grass_block", "minecraft:grass_block", "minecraft:grass_block"});
        CellSet.Builder b = new CellSet.Builder(1000, "test");
        Heightfield.Stats st = Heightfield.cells(s, g, b, 1000);
        CellSet set = b.build();

        assertEquals("minecraft:grass_block", at(set, 0, 55, 0));
        assertEquals("minecraft:dirt", at(set, 0, 54, 0));
        for (int y = 56; y <= 58; y++) assertEquals("minecraft:water", at(set, 0, y, 0), "a lowered bed fills with the lake's liquid");
        assertNull(at(set, 0, 59, 0), "the water above the old bed stays as it is");

        for (int y = -64; y < 100; y++) assertNull(at(set, 1, y, 0), "a null column is left alone");

        assertEquals("minecraft:andesite", at(set, 2, 70, 0));
        assertEquals("minecraft:andesite", at(set, 2, 69, 0));
        assertEquals("minecraft:stone", at(set, 2, 68, 0));
        assertEquals("minecraft:andesite", at(set, 3, 64, 0), "the low side of the step is steep too");
        assertEquals(1, st.skipped());
        assertEquals(2, st.steep());
        assertEquals("Heightfield: 4 columns (1 skipped): 1 raised (up to +6), 1 lowered (up to -3), 1 level, 2 steep.", st.line());
    }

    @Test
    void refusesBadInputAndTooManyCellsBeforeAddingAny() {
        assertThrows(ToolArgError.class, () -> spec("{\"from\":[0,0],\"width\":3,\"heights\":[1,2,3,4],\"top\":\"stone\",\"fill\":\"stone\"}"));
        assertThrows(ToolArgError.class, () -> spec("{\"from\":[0,0],\"width\":1,\"heights\":[1.5],\"top\":\"stone\",\"fill\":\"stone\"}"));
        assertThrows(ToolArgError.class, () -> spec("{\"from\":[0,0],\"width\":1,\"heights\":[1],\"top\":\"stone\",\"fill\":\"stone\",\"steepAt\":2}"));
        assertThrows(ToolArgError.class, () -> spec("{\"from\":[0,0],\"width\":1,\"heights\":[1],\"top\":\"stone\",\"fill\":\"stone\",\"shape\":1}"));
        JsonObject withCells = JsonParser.parseString("{\"heightfield\":{},\"fills\":[]}").getAsJsonObject();
        assertThrows(ToolArgError.class, () -> Heightfield.parse(withCells));

        Heightfield.Spec s = spec("{\"from\":[0,0],\"width\":2,\"heights\":[100,null],\"top\":\"stone\",\"fill\":\"stone\"}");
        Heightfield.Ground g = new Heightfield.Ground(new int[]{0, 0}, new int[]{0, 0}, new String[]{"minecraft:stone", "minecraft:stone"});
        CellSet.Builder b = new CellSet.Builder(1000, "test");
        ToolArgError e = assertThrows(ToolArgError.class, () -> Heightfield.cells(s, g, b, 50));
        assertTrue(e.getMessage().contains("101 cells, more than 50"), e.getMessage());
        assertEquals(0, b.size());
        Heightfield.Spec none = spec("{\"from\":[0,0],\"width\":1,\"heights\":[null],\"top\":\"stone\",\"fill\":\"stone\"}");
        assertThrows(ToolArgError.class, () -> Heightfield.cells(none, new Heightfield.Ground(new int[1], new int[1], new String[]{"stone"}), b, 50));
    }
}
