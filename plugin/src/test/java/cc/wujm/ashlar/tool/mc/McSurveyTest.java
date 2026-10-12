// SPDX-License-Identifier: AGPL-3.0-or-later
package cc.wujm.ashlar.tool.mc;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import cc.wujm.ashlar.tool.ToolArgError;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Argument parsing tests for {@link McSurvey}, mirroring mc-survey.ts's zod schema. */
class McSurveyTest {

    private static JsonObject obj(String json) {
        return JsonParser.parseString(json).getAsJsonObject();
    }

    @Test
    void minimalArgsParseWithNullDefaults() {
        McSurvey.Args a = McSurvey.Args.parse(obj("{\"from\":[0,0],\"to\":[10,10]}"));
        assertArrayEquals(new int[]{0, 0}, a.from());
        assertArrayEquals(new int[]{10, 10}, a.to());
        assertNull(a.type());
        assertNull(a.format());
        assertNull(a.matrix());
    }

    @Test
    void fullArgsParse() {
        McSurvey.Args a = McSurvey.Args.parse(
                obj("{\"world\":\"world\",\"from\":[0,0],\"to\":[10,10],\"type\":\"SOLID\",\"format\":\"text\",\"matrix\":true}"));
        assertEquals("world", a.world());
        assertEquals("SOLID", a.type());
        assertEquals("text", a.format());
        assertTrue(a.matrix());
    }

    @Test
    void missingFromThrows() {
        assertThrows(ToolArgError.class, () -> McSurvey.Args.parse(obj("{\"to\":[10,10]}")));
    }

    @Test
    void fromWithThreeElementsThrows() {
        assertThrows(ToolArgError.class, () -> McSurvey.Args.parse(obj("{\"from\":[0,0,0],\"to\":[10,10]}")));
    }

    @Test
    void invalidTypeThrows() {
        assertThrows(ToolArgError.class, () -> McSurvey.Args.parse(obj("{\"from\":[0,0],\"to\":[10,10],\"type\":\"LAVA\"}")));
    }

    @Test
    void invalidFormatThrows() {
        assertThrows(ToolArgError.class, () -> McSurvey.Args.parse(obj("{\"from\":[0,0],\"to\":[10,10],\"format\":\"json\"}")));
    }

    @Test
    void areaWithinLimitDoesNotThrow() {
        McSurvey.checkArea(200_000);
    }

    @Test
    void areaOverLimitThrows() {
        ToolArgError e = assertThrows(ToolArgError.class, () -> McSurvey.checkArea(200_001));
        assertEquals("mc_survey area 200001 exceeds the 200,000-cell limit. Reduce the from/to range or split it into several calls.", e.getMessage());
    }

    @Test
    void decorTypeAndWalkHeightParse() {
        McSurvey.Args a = McSurvey.Args.parse(obj("{\"from\":[0,0],\"to\":[10,10],\"type\":\"TOP_SOLID_IGNORE_DECOR\",\"snowLayers\":5,\"walkHeight\":true}"));
        assertEquals(5, a.snowLayers());
        assertTrue(a.walkHeight());
        assertNull(McSurvey.Args.parse(obj("{\"from\":[0,0],\"to\":[1,1]}")).snowLayers());
    }

    @Test
    void snowLayersNeedTheDecorTypeAndRange() {
        assertThrows(ToolArgError.class, () -> McSurvey.Args.parse(obj("{\"from\":[0,0],\"to\":[1,1],\"snowLayers\":2}")));
        assertThrows(ToolArgError.class, () -> McSurvey.Args.parse(obj("{\"from\":[0,0],\"to\":[1,1],\"type\":\"TOP_SOLID_IGNORE_DECOR\",\"snowLayers\":8}")));
    }

    @Test
    void walkNumbersAndText() {
        assertEquals("65", McSurvey.walkNumber(65.0));
        assertEquals("64.5", McSurvey.walkNumber(64.5));
        assertEquals("65.125", McSurvey.walkNumber(65.125));
        assertEquals("65.063", McSurvey.walkNumber(65.0625));
        com.google.gson.JsonArray w = JsonParser.parseString("[[65,65.5],[66.5,64.25]]").getAsJsonArray();
        String t = McSurvey.walkText(w, 10, -3);
        assertTrue(t.endsWith("\nz=-3: 65 65.5\nz=-2: 66.5 64.25"), t);
    }

    @Test
    void saveAsArguments() {
        McSurvey.Args a = McSurvey.Args.parse(obj("{\"from\":[0,0],\"to\":[10,10],\"saveAs\":\"site-1\",\"overwrite\":true}"));
        assertEquals("site-1", a.saveAs());
        assertTrue(a.overwrite());
        assertThrows(ToolArgError.class, () -> McSurvey.Args.parse(obj("{\"from\":[0,0],\"to\":[10,10],\"saveAs\":\"Bad Name\"}")));
        assertThrows(ToolArgError.class, () -> McSurvey.Args.parse(obj("{\"from\":[0,0],\"to\":[10,10],\"overwrite\":true}")));
        assertThrows(ToolArgError.class, () -> McSurvey.Args.parse(obj("{\"from\":[0,0],\"to\":[10,10],\"saveAs\":\"a\",\"matrix\":true}")));
        assertThrows(ToolArgError.class, () -> McSurvey.Args.parse(obj("{\"from\":[0,0],\"to\":[10,10],\"saveAs\":\"a\",\"format\":\"text\"}")));
    }

    @Test
    void surveyFileLayoutAndCellsFileRefusal() throws Exception {
        java.nio.file.Path dir = java.nio.file.Files.createTempDirectory("survey");
        CellFiles files = new CellFiles(dir);
        // 2x2 columns at x 10..11, z 20..21: grass under a short-grass tuft, a tree, a lake, bare stone.
        JsonObject hm = obj("{\"world\":\"w\",\"columns\":{\"palette\":[\"minecraft:short_grass\",\"minecraft:grass_block[snowy=false]\","
                + "\"minecraft:oak_leaves[distance=1,persistent=false,waterlogged=false]\",\"minecraft:water[level=0]\",\"minecraft:sand\",\"minecraft:stone\",\"minecraft:oak_log[axis=y]\"],"
                + "\"topY\":[65,72,62,64],\"top\":[0,2,3,5],\"groundY\":[64,64,58,64],\"ground\":[1,1,4,5]}}");
        String line = SurveyFile.write(files, "site", false, hm, 10, 20, 11, 21, s -> s.replace("[snowy=false]", ""));
        assertTrue(line.startsWith("Saved 4 columns (2x2) to plugins/Ashlar/exports/site.json.gz (format surface"), line);
        assertTrue(line.contains("[x0,z0] = [10,20]: index = (z - z0) * 2 + (x - x0)") && line.contains("above the ground in 3 columns"), line);
        java.nio.file.Path p = files.find("site");
        JsonObject f;
        try (var in = new java.io.InputStreamReader(new java.util.zip.GZIPInputStream(java.nio.file.Files.newInputStream(p)),
                java.nio.charset.StandardCharsets.UTF_8)) {
            f = JsonParser.parseReader(in).getAsJsonObject();
        }
        assertEquals("surface", f.get("format").getAsString());
        assertEquals("minecraft:grass_block", f.getAsJsonArray("palette").get(1).getAsString());
        assertEquals(6, f.getAsJsonArray("palette").size(), "the log only passed through by the scan is left out");
        assertEquals("[64,64,58,64]", f.getAsJsonArray("groundY").toString());
        assertEquals("[1,1,4,5]", f.getAsJsonArray("ground").toString());
        assertThrows(ToolArgError.class, () -> SurveyFile.write(files, "site", false, hm, 10, 20, 11, 21, s -> s));
        var e = assertThrows(ToolArgError.class, () -> CellFiles.read(p, "cellsFile 'site'", (x, y, z, s) -> {}));
        assertTrue(e.getMessage().contains("surface file"), e.getMessage());
    }
}
