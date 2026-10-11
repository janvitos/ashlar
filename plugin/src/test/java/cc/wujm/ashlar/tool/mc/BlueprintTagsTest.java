// SPDX-License-Identifier: AGPL-3.0-or-later
package cc.wujm.ashlar.tool.mc;

import cc.wujm.ashlar.tool.ToolArgError;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static cc.wujm.ashlar.tool.mc.BlueprintCompilerTest.document;
import static org.junit.jupiter.api.Assertions.*;

/** Step 19 blueprint tags, tag filters, cached bounds and touches. */
class BlueprintTagsTest {
    @TempDir Path directory;

    private static JsonObject obj(String json) { return JsonParser.parseString(json).getAsJsonObject(); }

    private static JsonObject tagged(String tags) {
        JsonObject doc = document();
        doc.add("tags", obj(tags));
        return doc;
    }

    @Test void tagsAreValidated() {
        BlueprintCompiler.validate(tagged("{\"module\":\"Hull deck\",\"status\":\"draft\",\"undo\":\"hull-undo\",\"note\":\"first pass\"}"));
        assertThrows(ToolArgError.class, () -> BlueprintCompiler.validate(tagged("{\"status\":\"done\"}")));
        assertThrows(ToolArgError.class, () -> BlueprintCompiler.validate(tagged("{\"owner\":\"x\"}")));
        assertThrows(ToolArgError.class, () -> BlueprintCompiler.validate(tagged("{\"undo\":\"Not An Id\"}")));
        assertThrows(ToolArgError.class, () -> BlueprintCompiler.validate(tagged("{\"module\":\"\"}")));
        assertThrows(ToolArgError.class, () -> BlueprintCompiler.validate(tagged("{\"note\":\"" + "x".repeat(501) + "\"}")));
    }

    @Test void tagsPersistAndListFilters() {
        var store = new BlueprintStore(directory);
        store.save("hull", tagged("{\"module\":\"hull\",\"status\":\"draft\"}"), false);
        store.save("deck", tagged("{\"module\":\"deck\",\"status\":\"built\"}"), false);
        store.save("plain", document(), false);
        assertEquals(tagged("{\"module\":\"hull\",\"status\":\"draft\"}"), new BlueprintStore(directory).get("hull"));
        assertEquals(3, store.list().getAsJsonArray("blueprints").size());
        var built = store.list(obj("{\"status\":\"built\"}")).getAsJsonArray("blueprints");
        assertEquals(1, built.size());
        assertEquals("deck", built.get(0).getAsJsonObject().get("id").getAsString());
        assertEquals("built", built.get(0).getAsJsonObject().getAsJsonObject("tags").get("status").getAsString());
        assertEquals(0, store.list(obj("{\"module\":\"hull\",\"status\":\"built\"}")).getAsJsonArray("blueprints").size());
    }

    @Test void tagMergesAndRemoves() {
        var store = new BlueprintStore(directory);
        store.save("hull", tagged("{\"module\":\"hull\",\"status\":\"draft\",\"note\":\"x\"}"), false);
        store.tag("hull", obj("{\"status\":\"approved\",\"note\":null,\"undo\":\"hull-undo\"}"));
        assertEquals(obj("{\"module\":\"hull\",\"status\":\"approved\",\"undo\":\"hull-undo\"}"), store.tags("hull"));
        assertThrows(ToolArgError.class, () -> store.tag("hull", obj("{\"status\":\"finished\"}")));
        assertEquals("approved", store.tags("hull").get("status").getAsString());
        store.tag("hull", obj("{\"module\":null,\"status\":null,\"undo\":null}"));
        assertNull(store.tags("hull"));
        assertFalse(store.get("hull").has("tags"));
        assertThrows(ToolArgError.class, () -> store.tag("missing", obj("{\"status\":\"built\"}")));
    }

    @Test void boundsCoverEveryInstanceWithoutATransform() {
        int[][] b = BlueprintCompiler.bounds(document());
        assertArrayEquals(new int[] {0, 0, 0}, b[0]);
        assertTrue(b[1][0] >= 22 && b[1][1] >= 5 && b[1][2] == 1, java.util.Arrays.toString(b[1]));
    }

    @Test void touchesUsesCachedBoundsAndRecomputesWhenMissing() throws Exception {
        var store = new BlueprintStore(directory);
        store.save("windows", document(), false);
        JsonObject far = obj("{\"version\":1,\"components\":{\"c\":{\"fills\":[{\"from\":[1000,60,1000],\"to\":[1004,62,1002],\"block\":\"stone\"}]}},"
                + "\"instances\":[{\"component\":\"c\",\"pos\":[0,0,0]}]}");
        store.save("far", far, false);
        assertTrue(Files.exists(directory.resolve("far.bounds.json")));
        var hits = store.touches(new int[] {20, 0, 0}, new int[] {30, 3, 0}).getAsJsonArray("blueprints");
        assertEquals(1, hits.size());
        assertEquals("windows", hits.get(0).getAsJsonObject().get("id").getAsString());
        hits = store.touches(new int[] {1004, 62, 1002}, new int[] {1100, 100, 1100}).getAsJsonArray("blueprints");
        assertEquals(1, hits.size());
        assertEquals(obj("{\"from\":[1000,60,1000],\"to\":[1004,62,1002]}"), hits.get(0).getAsJsonObject().getAsJsonObject("bounds"));
        assertEquals(0, store.touches(new int[] {1005, 0, 0}, new int[] {1100, 100, 999}).getAsJsonArray("blueprints").size());
        Files.delete(directory.resolve("far.bounds.json"));
        assertEquals(1, new BlueprintStore(directory).touches(new int[] {1000, 60, 1000}, new int[] {1000, 60, 1000}).getAsJsonArray("blueprints").size());
        store.delete("far");
        assertFalse(Files.exists(directory.resolve("far.bounds.json")));
        assertEquals(1, store.list().getAsJsonArray("blueprints").size());
    }

    @Test void traceLineNamesBlueprintTagsAndJournal() {
        assertEquals("Blueprint: hull (module=hull, status=draft); journal jrn-20261011-120000-abcd.",
                McBuild.traceLine("hull", obj("{\"module\":\"hull\",\"status\":\"draft\",\"note\":\"skip\"}"), "Fills: ...\nJournal: jrn-20261011-120000-abcd (12 cells)."));
        assertEquals("Blueprint: plain; no journal.", McBuild.traceLine("plain", null, "Fills: ..."));
    }
}
