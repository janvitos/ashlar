// SPDX-License-Identifier: AGPL-3.0-or-later
package cc.wujm.ashlar.tool.mc;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;

import java.util.function.UnaryOperator;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AngledRenderTextTest {

    /** Stand-in for the registry-backed canon: drops the namespace and waterlogged=false. */
    private static final UnaryOperator<String> CANON = s -> {
        if (s.contains("bad")) throw new IllegalArgumentException("unknown");
        return s.replace("minecraft:", "").replace(",waterlogged=false", "").replace("[waterlogged=false]", "");
    };

    private static JsonObject render(int legendEntries) {
        JsonObject r = JsonParser.parseString("""
                {"view":"isometric","bounds":{"from":[0,60,0],"to":[9,70,9]},"width":480,"height":519,"scale":8,
                 "axes":{"right":"camera screen-right","down":"camera screen-down"},"topLeft":[0,0],"grid":0,"legendTruncated":false,
                 "geometry":{"projection":"orthographic","camera":{"azimuth":135.0,"elevation":35.26438968},
                  "screenRight":[0.7071067811865475,0.0,-0.7071067811865476],"screenUp":[-0.4,0.8,-0.4],"requestedScale":0,
                  "resolutionReduced":true,"estimatedIntersectionWork":30143520,"cubeFallbackStates":0,"cubeFallbackSamples":[],
                  "approximateStates":12,"approximateSamples":["minecraft:chest[facing=north,type=single,waterlogged=false]","a1","a2","a3","a4","a5","a6","a7","a8","a9","a10","a11"],
                  "colorFallbackSamples":[],"cubeFallbackSamplesTruncated":false,"approximateSamplesTruncated":false,
                  "colorFallbackStates":0,"colorFallbackSamplesTruncated":false,"entityPlaceholderCells":0,
                  "diff":{"changed":1,"added":0,"removed":2,"outsideSnapshot":0,"tints":"long text","snapshot":"snap-1"},
                  "transparentLayerLimit":8,"transparentRaysCapped":0,"assumptions":"long text"}}""").getAsJsonObject();
        var legend = new com.google.gson.JsonArray();
        for (int i = 0; i < legendEntries; i++) {
            JsonObject e = new JsonObject();
            e.addProperty("block", i == 0 ? "minecraft:oak_stairs[facing=north,half=bottom,shape=straight,waterlogged=false]"
                    : i == 1 ? "entity:painting" : i == 2 ? "minecraft:bad_block" : "minecraft:block_" + i);
            e.addProperty("color", "#70707" + (i % 10));
            e.addProperty("pixels", 1000 - i);
            legend.add(e);
        }
        r.add("legend", legend);
        return r;
    }

    @Test
    void legendIsAddedInPixelOrderWithCanonicalStatesAndBoilerplateIsDropped() {
        JsonObject out = JsonParser.parseString(AngledRenderText.text(render(3), CANON)).getAsJsonObject();
        JsonObject legend = out.getAsJsonObject("legend");
        assertEquals(java.util.List.of("oak_stairs[facing=north,half=bottom,shape=straight]", "entity:painting", "minecraft:bad_block"),
                java.util.List.copyOf(legend.keySet()));
        assertEquals("#707070", legend.get("oak_stairs[facing=north,half=bottom,shape=straight]").getAsString());
        assertFalse(out.has("legendMore"));
        assertFalse(out.has("axes") || out.has("topLeft") || out.has("grid"));
        JsonObject g = out.getAsJsonObject("geometry");
        assertEquals(java.util.Set.of("camera", "resolutionReduced", "approximateStates", "approximateSamples", "diff"), g.keySet());
        assertEquals(35.26, g.getAsJsonObject("camera").get("elevation").getAsDouble());
        assertEquals(AngledRenderText.SAMPLE_LIMIT, g.getAsJsonArray("approximateSamples").size());
        assertEquals("chest[facing=north,type=single]", g.getAsJsonArray("approximateSamples").get(0).getAsString());
        assertEquals(12, g.get("approximateStates").getAsInt());
        JsonObject diff = g.getAsJsonObject("diff");
        assertFalse(diff.has("tints"));
        assertEquals(0, diff.get("added").getAsInt());
        assertEquals("snap-1", diff.get("snapshot").getAsString());
    }

    @Test
    void legendIsCappedAndReportsTheRest() {
        String text = AngledRenderText.text(render(27), CANON);
        JsonObject out = JsonParser.parseString(text).getAsJsonObject();
        assertEquals(AngledRenderText.LEGEND_LIMIT, out.getAsJsonObject("legend").size());
        assertEquals(7, out.get("legendMore").getAsInt());
        assertTrue(text.length() < 1200, "reply stays short: " + text.length());
    }
}
