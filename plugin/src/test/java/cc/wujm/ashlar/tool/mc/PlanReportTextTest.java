// SPDX-License-Identifier: AGPL-3.0-or-later
package cc.wujm.ashlar.tool.mc;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.function.UnaryOperator;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PlanReportTextTest {

    /** Stand-in for the registry-backed canon: drops the namespace and waterlogged=false. */
    private static final UnaryOperator<String> CANON = s -> s.replace("minecraft:", "").replace("[waterlogged=false]", "");

    private static JsonObject report(int collisions, int issues) {
        JsonObject r = JsonParser.parseString("""
                {"valid":true,"strictSitePass":false,"from":[0,60,0],"to":[4,64,4],"world":"world","requestedVolume":125,
                 "acceptedVisits":125,"uniquePlannedCells":125,"skippedVisits":0,"overlappingCells":0,"signEdits":0,
                 "blockStateChanges":40,"existingNonAirChanged":3,"clearedCells":0,"dimensions":[5,5,5],"elapsedMs":4,
                 "assumptions":"long text","collisionSamplesTruncated":false,"overlapSamples":[],
                 "finalPlannedMaterials":{"minecraft:stone":100,"minecraft:oak_fence[waterlogged=false]":25},
                 "protected":[{"name":"dock","mode":"deny"}],"protectedPass":false}""").getAsJsonObject();
        JsonArray samples = new JsonArray();
        for (int i = 0; i < collisions; i++) {
            JsonObject s = new JsonObject();
            s.addProperty("existing", i % 3 == 0 ? "minecraft:grass_block" : "minecraft:dirt");
            s.addProperty("target", "minecraft:air");
            s.add("pos", JsonParser.parseString("[" + i + ",60,0]"));
            samples.add(s);
        }
        r.add("collisionSamples", samples);
        JsonArray list = new JsonArray();
        for (int i = 0; i < issues; i++) {
            JsonObject is = new JsonObject();
            is.addProperty("severity", "error");
            is.addProperty("kind", "door_pair");
            is.addProperty("block", "minecraft:oak_door[half=lower]");
            list.add(is);
        }
        r.add("issues", list);
        return r;
    }

    @Test
    void decisiveFieldsStayAndBoilerplateGoes() {
        JsonObject out = JsonParser.parseString(PlanReportText.text(report(4, 1), CANON)).getAsJsonObject();
        for (String k : List.of("valid", "strictSitePass", "from", "to", "world", "uniquePlannedCells", "blockStateChanges",
                "existingNonAirChanged", "clearedCells", "protected", "protectedPass")) assertTrue(out.has(k), k);
        for (String k : List.of("requestedVolume", "acceptedVisits", "dimensions", "elapsedMs", "assumptions", "skippedVisits",
                "overlappingCells", "signEdits", "collisionSamples", "collisionSamplesTruncated", "overlapSamples",
                "finalPlannedMaterials", "issuesMore")) assertFalse(out.has(k), k);
        assertEquals(List.of("stone", "oak_fence"), List.copyOf(out.getAsJsonObject("materials").keySet()));
        JsonObject issue = out.getAsJsonArray("issues").get(0).getAsJsonObject();
        assertFalse(issue.has("severity"));
        assertEquals("oak_door[half=lower]", issue.get("block").getAsString());
    }

    @Test
    void collisionsAreGroupedByTransitionLargestFirst() {
        JsonArray groups = JsonParser.parseString(PlanReportText.text(report(6, 0), CANON)).getAsJsonObject().getAsJsonArray("collisions");
        assertEquals(2, groups.size());
        assertEquals(JsonParser.parseString("{\"change\":\"dirt -> air\",\"n\":4,\"at\":[1,60,0]}"), groups.get(0));
        assertEquals(JsonParser.parseString("{\"change\":\"grass_block -> air\",\"n\":2,\"at\":[0,60,0]}"), groups.get(1));
    }

    @Test
    void issuesAreCappedAndReportTheRest() {
        JsonObject out = JsonParser.parseString(PlanReportText.text(report(0, 25), CANON)).getAsJsonObject();
        assertEquals(PlanReportText.ISSUE_LIMIT, out.getAsJsonArray("issues").size());
        assertEquals(5, out.get("issuesMore").getAsInt());
        assertFalse(out.has("collisions"));
    }
}
