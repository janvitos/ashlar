// SPDX-License-Identifier: AGPL-3.0-or-later
package cc.wujm.ashlar.tool.mc;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import cc.wujm.ashlar.tool.ToolArgError;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Argument parsing tests for {@link McBuild}, mirroring mc-build.ts's zod schema and refine rule. */
class McBuildTest {

    private static JsonObject obj(String json) {
        return JsonParser.parseString(json).getAsJsonObject();
    }

    @Test
    void fillsOnlyParses() {
        McBuild.Args a = McBuild.Args.parse(obj(
                "{\"fills\":[{\"from\":[0,60,0],\"to\":[10,70,10],\"block\":\"minecraft:stone\"}]}"));
        assertEquals(1, a.fills().size());
        assertEquals(0, a.blocks().size());
        assertFalse(a.snapshot());
        assertNull(a.connect());
    }

    @Test
    void blocksOnlyParses() {
        McBuild.Args a = McBuild.Args.parse(obj(
                "{\"blocks\":[{\"pos\":[0,60,0],\"block\":\"minecraft:torch\"}]}"));
        assertEquals(1, a.blocks().size());
        assertEquals(0, a.fills().size());
    }

    @Test
    void emptyBodyThrowsRefineMessage() {
        ToolArgError e = assertThrows(ToolArgError.class, () -> McBuild.Args.parse(obj("{}")));
        assertEquals("\"fills\", \"shapes\", \"blocks\", \"text\" and/or \"entities\" must be provided, with at least one non-empty",
                e.getMessage());
    }

    @Test
    void emptyFillsAndBlocksArraysThrowRefineMessage() {
        assertThrows(ToolArgError.class, () -> McBuild.Args.parse(obj("{\"fills\":[],\"blocks\":[]}")));
    }

    @Test
    void fillWithEmptyBlockThrows() {
        assertThrows(ToolArgError.class, () -> McBuild.Args.parse(
                obj("{\"fills\":[{\"from\":[0,60,0],\"to\":[10,70,10],\"block\":\"\"}]}")));
    }

    @Test
    void fillWithInvalidModeThrows() {
        assertThrows(ToolArgError.class, () -> McBuild.Args.parse(
                obj("{\"fills\":[{\"from\":[0,60,0],\"to\":[10,70,10],\"block\":\"minecraft:stone\",\"mode\":\"invert\"}]}")));
    }

    @Test
    void fillWithValidModeAndFilterParses() {
        McBuild.Args a = McBuild.Args.parse(obj(
                "{\"fills\":[{\"from\":[0,60,0],\"to\":[10,70,10],\"block\":\"minecraft:stone\",\"mode\":\"hollow\",\"filter\":\"minecraft:air\"}]}"));
        McBuild.FillOpArg f = a.fills().get(0);
        assertEquals("hollow", f.mode());
        assertEquals("minecraft:air", f.filter());
    }

    @Test
    void blockMissingPosThrows() {
        assertThrows(ToolArgError.class, () -> McBuild.Args.parse(obj("{\"blocks\":[{\"block\":\"minecraft:torch\"}]}")));
    }

    @Test
    void snapshotAndConnectFlagsParse() {
        McBuild.Args a = McBuild.Args.parse(obj(
                "{\"blocks\":[{\"pos\":[0,60,0],\"block\":\"minecraft:torch\"}],\"snapshot\":true,\"connect\":false}"));
        assertTrue(a.snapshot());
        assertFalse(a.connect());
    }

    @Test
    void liquidsOmittedParsesAsNull() {
        McBuild.Args a = McBuild.Args.parse(obj(
                "{\"fills\":[{\"from\":[0,60,0],\"to\":[10,70,10],\"block\":\"minecraft:stone\"}]}"));
        assertNull(a.liquids());
    }

    @Test
    void liquidsStaticParses() {
        McBuild.Args a = McBuild.Args.parse(obj(
                "{\"blocks\":[{\"pos\":[0,60,0],\"block\":\"minecraft:water\"}],\"liquids\":\"static\"}"));
        assertEquals("static", a.liquids());
    }

    @Test
    void liquidsFlowParses() {
        McBuild.Args a = McBuild.Args.parse(obj(
                "{\"blocks\":[{\"pos\":[0,60,0],\"block\":\"minecraft:water\"}],\"liquids\":\"flow\"}"));
        assertEquals("flow", a.liquids());
    }

    @Test
    void liquidsInvalidValueThrows() {
        assertThrows(ToolArgError.class, () -> McBuild.Args.parse(obj(
                "{\"blocks\":[{\"pos\":[0,60,0],\"block\":\"minecraft:water\"}],\"liquids\":\"gushing\"}")));
    }

    @Test
    void signWithFrontAndBackParses() {
        McBuild.Args a = McBuild.Args.parse(obj(
                "{\"blocks\":[{\"pos\":[0,60,0],\"block\":\"minecraft:oak_sign\",\"sign\":{\"front\":[\"Hello\"],\"back\":[\"World\"],\"color\":\"red\",\"glowing\":true}}]}"));
        McBuild.SparseOpArg b = a.blocks().get(0);
        assertEquals("Hello", b.sign().front().get(0));
        assertEquals("World", b.sign().back().get(0));
        assertEquals("red", b.sign().color());
        assertTrue(b.sign().glowing());
    }

    @Test
    void signWithFiveLinesThrows() {
        assertThrows(ToolArgError.class, () -> McBuild.Args.parse(obj(
                "{\"blocks\":[{\"pos\":[0,60,0],\"block\":\"minecraft:oak_sign\","
                        + "\"sign\":{\"front\":[\"1\",\"2\",\"3\",\"4\",\"5\"]}}]}")));
    }

    @Test
    void signLineOver64CharsThrows() {
        String longLine = "x".repeat(65);
        assertThrows(ToolArgError.class, () -> McBuild.Args.parse(obj(
                "{\"blocks\":[{\"pos\":[0,60,0],\"block\":\"minecraft:oak_sign\",\"sign\":{\"front\":[\"" + longLine + "\"]}}]}")));
    }

    // --- text entries (step8k-prompt.md) ------------------------------------------------------

    @Test
    void textOnlyParses() {
        McBuild.Args a = McBuild.Args.parse(obj(
                "{\"text\":[{\"text\":\"HI\",\"pos\":[0,60,0],\"block\":\"minecraft:white_concrete\"}]}"));
        assertEquals(1, a.text().size());
        assertEquals(0, a.fills().size());
        assertEquals(0, a.blocks().size());
        McBuild.TextArg t = a.text().get(0);
        assertEquals("HI", t.text());
        assertEquals("minecraft:white_concrete", t.block());
        assertEquals("south", t.facing());
        assertEquals(1, t.scale());
        assertEquals(1, t.spacing());
        assertNull(t.background());
    }

    @Test
    void textAllOptionsParse() {
        McBuild.Args a = McBuild.Args.parse(obj(
                "{\"text\":[{\"text\":\"HI\",\"pos\":[0,60,0],\"block\":\"minecraft:white_concrete\","
                        + "\"background\":\"minecraft:black_concrete\",\"facing\":\"up\",\"scale\":3,\"spacing\":0}]}"));
        McBuild.TextArg t = a.text().get(0);
        assertEquals("minecraft:black_concrete", t.background());
        assertEquals("up", t.facing());
        assertEquals(3, t.scale());
        assertEquals(0, t.spacing());
    }

    @Test
    void textMissingBlockThrows() {
        assertThrows(ToolArgError.class, () -> McBuild.Args.parse(
                obj("{\"text\":[{\"text\":\"HI\",\"pos\":[0,60,0]}]}")));
    }

    @Test
    void textMissingPosThrows() {
        assertThrows(ToolArgError.class, () -> McBuild.Args.parse(
                obj("{\"text\":[{\"text\":\"HI\",\"block\":\"minecraft:stone\"}]}")));
    }

    @Test
    void textBadFacingThrows() {
        ToolArgError e = assertThrows(ToolArgError.class, () -> McBuild.Args.parse(obj(
                "{\"text\":[{\"text\":\"HI\",\"pos\":[0,60,0],\"block\":\"minecraft:stone\",\"facing\":\"sideways\"}]}")));
        assertTrue(e.getMessage().contains("facing"));
    }

    @Test
    void textScaleFiveThrows() {
        assertThrows(ToolArgError.class, () -> McBuild.Args.parse(obj(
                "{\"text\":[{\"text\":\"HI\",\"pos\":[0,60,0],\"block\":\"minecraft:stone\",\"scale\":5}]}")));
    }

    @Test
    void textScaleZeroThrows() {
        assertThrows(ToolArgError.class, () -> McBuild.Args.parse(obj(
                "{\"text\":[{\"text\":\"HI\",\"pos\":[0,60,0],\"block\":\"minecraft:stone\",\"scale\":0}]}")));
    }

    @Test
    void textEmptyStringThrows() {
        assertThrows(ToolArgError.class, () -> McBuild.Args.parse(obj(
                "{\"text\":[{\"text\":\"   \",\"pos\":[0,60,0],\"block\":\"minecraft:stone\"}]}")));
    }

    @Test
    void textOver64CharsThrows() {
        String longText = "A".repeat(65);
        assertThrows(ToolArgError.class, () -> McBuild.Args.parse(obj(
                "{\"text\":[{\"text\":\"" + longText + "\",\"pos\":[0,60,0],\"block\":\"minecraft:stone\"}]}")));
    }

    @Test
    void textFiveLinesThrows() {
        assertThrows(ToolArgError.class, () -> McBuild.Args.parse(obj(
                "{\"text\":[{\"text\":\"A\\nA\\nA\\nA\\nA\",\"pos\":[0,60,0],\"block\":\"minecraft:stone\"}]}")));
    }

    @Test
    void textSpacingFourThrows() {
        assertThrows(ToolArgError.class, () -> McBuild.Args.parse(obj(
                "{\"text\":[{\"text\":\"HI\",\"pos\":[0,60,0],\"block\":\"minecraft:stone\",\"spacing\":4}]}")));
    }

    // --- align (step8m-prompt.md &sect;A) ------------------------------------------------------

    @Test
    void alignDefaultsToLeft() {
        McBuild.Args a = McBuild.Args.parse(obj(
                "{\"text\":[{\"text\":\"HI\",\"pos\":[0,60,0],\"block\":\"minecraft:white_concrete\"}]}"));
        assertEquals("left", a.text().get(0).align());
    }

    @Test
    void alignCenterParses() {
        McBuild.Args a = McBuild.Args.parse(obj(
                "{\"text\":[{\"text\":\"HI\",\"pos\":[0,60,0],\"block\":\"minecraft:white_concrete\",\"align\":\"center\"}]}"));
        assertEquals("center", a.text().get(0).align());
    }

    @Test
    void alignInvalidValueThrows() {
        ToolArgError e = assertThrows(ToolArgError.class, () -> McBuild.Args.parse(obj(
                "{\"text\":[{\"text\":\"HI\",\"pos\":[0,60,0],\"block\":\"minecraft:stone\",\"align\":\"middle\"}]}")));
        assertTrue(e.getMessage().contains("align"));
    }

    @Test
    void shapesExpandAndFollowTheTransform() {
        McBuild.Args a = McBuild.Args.parse(obj(
                "{\"shapes\":[{\"shape\":\"cone\",\"from\":[0,0,0],\"to\":[8,4,4],\"axis\":\"x\",\"block\":\"minecraft:stone\"}],"
                        + "\"transform\":{\"origin\":[100,64,200],\"rotation\":90}}"));
        McBuild.ShapeArg s = a.shapes().getFirst();
        assertFalse(a.isEmpty());
        // Rotating 90 clockwise maps local +X to world +Z and local +Z to world -X.
        assertArrayEquals(new int[] {96, 64, 200}, s.expanded().min());
        assertArrayEquals(new int[] {100, 68, 208}, s.expanded().max());
        long tip = s.expanded().runs().stream().filter(r -> r.from()[2] == 208 || r.to()[2] == 208)
                .mapToLong(r -> Math.min(r.to()[2], 208) - Math.max(r.from()[2], 208) + 1).sum();
        assertEquals(1, tip);
        int[][] box = McBuild.boundingBox(a);
        assertArrayEquals(s.expanded().min(), box[0]);
        assertArrayEquals(s.expanded().max(), box[1]);
    }

    @Test
    void invalidShapesNameTheEntry() {
        ToolArgError e = assertThrows(ToolArgError.class, () -> McBuild.Args.parse(obj(
                "{\"shapes\":[{\"shape\":\"sphere\",\"from\":[0,0,0],\"to\":[4,4,4],\"thickness\":20,\"block\":\"minecraft:glass\"}]}")));
        assertEquals("shapes[0].thickness: must be between 0 and 16, got 20", e.getMessage());
        assertThrows(ToolArgError.class, () -> McBuild.Args.parse(obj(
                "{\"shapes\":[{\"shape\":\"torus\",\"from\":[0,0,0],\"to\":[4,4,4],\"block\":\"minecraft:glass\"}]}")));
    }
}
