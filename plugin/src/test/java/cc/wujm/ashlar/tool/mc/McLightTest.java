// SPDX-License-Identifier: AGPL-3.0-or-later
package cc.wujm.ashlar.tool.mc;

import cc.wujm.ashlar.engine.LightScan;
import cc.wujm.ashlar.tool.ToolArgError;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Step 18 {@code mc_light} arguments and text. */
class McLightTest {

    private static JsonObject obj(String json) {
        return JsonParser.parseString(json).getAsJsonObject();
    }

    private static final String BOX = "\"from\":[0,60,0],\"to\":[9,64,9]";

    @Test
    void defaults() {
        McLight.Args a = McLight.Args.parse(obj("{" + BOX + "}"));
        assertEquals(new LightScan.Options(0, false, true), a.options());
        assertNull(a.suggest());
        assertEquals(20, a.samples());
    }

    @Test
    void suggestDefaultsToHiddenLightBlocks() {
        McLight.Args a = McLight.Args.parse(obj("{" + BOX + ",\"suggest\":{}}"));
        assertEquals(new LightScan.Suggest("minecraft:light[level=11]", 11, 1, "hidden"), a.suggest());
        a = McLight.Args.parse(obj("{" + BOX + ",\"suggest\":{\"block\":\"lantern[hanging=true]\",\"level\":15,\"placement\":\"ceiling\",\"spacing\":6}}"));
        assertEquals(new LightScan.Suggest("minecraft:lantern[hanging=true]", 15, 6, "ceiling"), a.suggest());
        assertEquals("floor", McLight.Args.parse(obj("{" + BOX + ",\"suggest\":{\"block\":\"torch\",\"level\":14}}")).suggest().placement());
    }

    @Test
    void rejectsBadSuggestions() {
        assertThrows(ToolArgError.class, () -> McLight.Args.parse(obj("{" + BOX + ",\"suggest\":{\"block\":\"torch\",\"placement\":\"hidden\"}}")));
        assertThrows(ToolArgError.class, () -> McLight.Args.parse(obj("{" + BOX + ",\"threshold\":7,\"suggest\":{\"level\":7}}")));
        assertThrows(ToolArgError.class, () -> McLight.Args.parse(obj("{" + BOX + ",\"suggest\":{\"color\":1}}")));
        assertThrows(ToolArgError.class, () -> McLight.Args.parse(obj("{" + BOX + ",\"threshold\":15}")));
        assertThrows(ToolArgError.class, () -> McLight.Args.parse(obj("{\"from\":[0,0],\"to\":[1,1,1]}")));
    }

    @Test
    void textListsCellsForMcBuild() {
        McLight.Args a = McLight.Args.parse(obj("{" + BOX + ",\"suggest\":{}}"));
        LightScan.Result r = new LightScan.Result(4, new int[][]{{1, 61, 1}, {2, 61, 1}}, Map.of(61, 2), List.of(new int[]{1, 61, 1}), 2, 0, null);
        String t = McLight.text("world", a, r);
        assertTrue(t.contains("2 dark of 4 spawn spaces"), t);
        assertTrue(t.contains("mc_build cells: [[1,61,1,\"minecraft:light[level=11]\"]]"), t);
        assertTrue(t.contains("Dark bounds: [1,61,1]..[2,61,1]"), t);
    }

    @Test
    void textWithoutDarkSpaces() {
        McLight.Args a = McLight.Args.parse(obj("{" + BOX + ",\"suggest\":{}}"));
        String t = McLight.text("world", a, new LightScan.Result(4, new int[0][], Map.of(), List.of(), 0, 0, null));
        assertTrue(t.contains("0 dark of 4"), t);
        assertTrue(t.endsWith("Suggestions: none needed."), t);
    }
}
