// SPDX-License-Identifier: AGPL-3.0-or-later
package cc.wujm.ashlar.tool.mc;

import cc.wujm.ashlar.tool.ToolArgError;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class BlueprintCompilerTest {
    static JsonObject obj(String json) { return JsonParser.parseString(json).getAsJsonObject(); }
    static JsonObject document() { return obj("""
            {"version":1,"description":"Repeated windows","dimensions":[30,10,10],"constraints":{"style":"gothic"},
             "palette":{"wall":"minecraft:stone_bricks","trim":"minecraft:oak_stairs[half=top]"},
             "components":{"window":{"palette":{"wall":"minecraft:dirt"},
               "fills":[{"from":[0,0,0],"to":[2,2,0],"block":"$wall","mode":"outline","filter":"$wall"}],
               "blocks":[{"pos":[1,3,0],"block":"$trim[facing=north,half=bottom]"},
                         {"pos":[0,1,1],"block":"minecraft:oak_wall_sign[facing=south]","sign":{"front":["$wall"],"back":["Text stays"]}}],
               "text":[{"text":"R","pos":[0,5,0],"block":"$wall","background":"$wall"}]}},
             "instances":[{"component":"window","pos":[0,0,0],"repeat":{"count":3,"step":[10,0,0]}}]}
            """); }
    static JsonObject request() { return obj("{\"blueprint\":{\"id\":\"demo\"},\"transform\":{\"origin\":[100,64,200]}}"); }
    static List<McBuild.Part> compile(JsonObject doc, JsonObject request) {
        return BlueprintCompiler.compile(doc, request, BlueprintCompiler.Limits.DEFAULT);
    }

    @Test void repeatsAreExpandedAndMetadataIsNotMutated() {
        JsonObject doc = document(), before = doc.deepCopy();
        var parts = compile(doc, request());
        assertEquals(3, parts.size());
        for (int i = 0; i < 3; i++) {
            var args = parts.get(i).args();
            assertArrayEquals(new int[] {100 + i * 10, 64, 200}, args.fills().getFirst().from());
            assertEquals("minecraft:stone_bricks", args.fills().getFirst().block());
            assertEquals("minecraft:stone_bricks", args.fills().getFirst().filter());
            assertEquals("minecraft:oak_stairs[half=bottom,facing=north]", args.blocks().getFirst().block());
            assertEquals("$wall", args.blocks().get(1).sign().front().getFirst());
            assertEquals("R", args.text().getFirst().text());
        }
        assertEquals(before, doc);
    }

    @Test void palettePrecedenceAndInlinePropertiesAreExplicit() {
        var doc = document(); var request = request();
        request.getAsJsonObject("blueprint").add("palette", obj("{\"wall\":\"minecraft:gold_block\"}"));
        doc.getAsJsonArray("instances").get(0).getAsJsonObject().add("palette", obj("{\"wall\":\"minecraft:diamond_block\"}"));
        assertEquals("minecraft:diamond_block", compile(doc, request).getFirst().args().fills().getFirst().block());
        doc.getAsJsonArray("instances").get(0).getAsJsonObject().remove("palette");
        assertEquals("minecraft:gold_block", compile(doc, request).getFirst().args().fills().getFirst().block());
        request.getAsJsonObject("blueprint").remove("palette");
        assertEquals("minecraft:stone_bricks", compile(doc, request).getFirst().args().fills().getFirst().block());
        doc.getAsJsonObject("palette").remove("wall");
        assertEquals("minecraft:dirt", compile(doc, request).getFirst().args().fills().getFirst().block());
    }

    @Test void compositionMatchesSequentialGeometryForAllFramePairs() {
        for (int outerRotation : List.of(0,90,180,270)) for (String outerMirror : List.of("none","x","z"))
            for (int innerRotation : List.of(0,90,180,270)) for (String innerMirror : List.of("none","x","z")) {
                var outer = new BuildTransform(100,64,200,outerRotation,outerMirror);
                var inner = new BuildTransform(10,3,-5,innerRotation,innerMirror);
                var combined = outer.compose(inner);
                for (int[] p : List.of(new int[] {0,0,0}, new int[] {3,1,-2}, new int[] {-7,2,8}))
                    assertArrayEquals(outer.position(inner.position(p)), combined.position(p));
                for (String d : List.of("north","south","east","west","up"))
                    assertEquals(outer.direction(inner.direction(d)), combined.direction(d));
                assertEquals(!outerMirror.equals("none") ^ !innerMirror.equals("none"), !combined.mirror().equals("none"));
            }
    }

    @Test void repetitionStepIsInProjectFrameNotComponentFrame() {
        JsonObject doc = document(), req = request();
        doc.getAsJsonArray("instances").get(0).getAsJsonObject().addProperty("rotation", 90);
        req.getAsJsonObject("transform").addProperty("rotation", 90);
        var parts = compile(doc, req);
        assertArrayEquals(new int[] {100,66,200}, parts.getFirst().args().fills().getFirst().to());
        assertArrayEquals(new int[] {100,66,210}, parts.get(1).args().fills().getFirst().to());
        assertEquals("south", parts.getFirst().transform().direction("north"));
    }

    @Test void negativeAndVerticalRepetitionAreSupported() {
        var doc = document();
        doc.getAsJsonArray("instances").get(0).getAsJsonObject().add("repeat", obj("{\"count\":2,\"step\":[-10,12,0]}"));
        var second = compile(doc, request()).get(1).args();
        assertArrayEquals(new int[] {90,76,200}, second.fills().getFirst().from());
    }

    @Test void oneCombinedBuildKeepsPassAndInstanceOrder() {
        var combined = McBuild.combine(compile(document(), request()).stream().map(McBuild.Part::args).toList());
        assertEquals(3, combined.fills().size()); assertEquals(6, combined.blocks().size()); assertEquals(3, combined.text().size());
        assertArrayEquals(new int[] {121,67,200}, combined.blocks().get(4).pos());
    }

    @Test void missingBindingIsAllowedInStorageButNotCompilation() {
        var doc = document(); doc.getAsJsonObject("palette").remove("trim");
        assertDoesNotThrow(() -> BlueprintCompiler.validate(doc));
        assertThrows(ToolArgError.class, () -> compile(doc, request()));
    }

    @Test void unknownComponentAndNestedDefinitionsAreRejected() {
        var doc = document(); doc.getAsJsonArray("instances").get(0).getAsJsonObject().addProperty("component", "missing");
        assertThrows(ToolArgError.class, () -> BlueprintCompiler.validate(doc));
        var nested = document(); nested.getAsJsonObject("components").getAsJsonObject("window").add("instances", new com.google.gson.JsonArray()); assertThrows(ToolArgError.class, () -> BlueprintCompiler.validate(nested));
    }

    @Test void invalidMetadataUnknownFieldsAndMalformedRolesFail() {
        for (String change : List.of("{\"version\":2}", "{\"dimensions\":[0,2,3]}", "{\"dimensions\":[1.2,2,3]}",
                "{\"constraints\":[]}", "{\"unexpected\":true}", "{\"palette\":{\"bad\":\"$other\"}}")) {
            var doc = document(); obj(change).entrySet().forEach(e -> doc.add(e.getKey(), e.getValue()));
            assertThrows(ToolArgError.class, () -> BlueprintCompiler.validate(doc));
        }
        var doc = document();
        doc.getAsJsonObject("components").getAsJsonObject("window").getAsJsonArray("blocks").get(0).getAsJsonObject().addProperty("block", "$bad[broken");
        assertThrows(ToolArgError.class, () -> BlueprintCompiler.validate(doc));
    }

    @Test void fractionalCountsStepsAndScalesAreRejected() {
        for (String repeat : List.of("{\"count\":0,\"step\":[1,0,0]}", "{\"count\":4097,\"step\":[1,0,0]}",
                "{\"count\":1.5,\"step\":[1,0,0]}", "{\"count\":2,\"step\":[1.2,0,0]}")) {
            var doc = document(); doc.getAsJsonArray("instances").get(0).getAsJsonObject().add("repeat", obj(repeat));
            assertThrows(ToolArgError.class, () -> BlueprintCompiler.validate(doc));
        }
        var doc = document(); doc.getAsJsonObject("components").getAsJsonObject("window").getAsJsonArray("text").get(0).getAsJsonObject().addProperty("scale", 1.5);
        assertThrows(ToolArgError.class, () -> BlueprintCompiler.validate(doc));
    }

    @Test void materialPropertiesMergeAndDuplicateSuffixPropertiesFail() {
        assertEquals("minecraft:oak_stairs[half=top,facing=south]", BlueprintCompiler.mergeProperties("minecraft:oak_stairs[half=bottom,facing=south]", "[half=top]"));
        assertEquals("minecraft:stone", BlueprintCompiler.mergeProperties("minecraft:stone", "[]"));
        for (String suffix : List.of("[half=top,half=bottom]", "[half=]", "[broken]", "[half=top,]"))
            assertThrows(ToolArgError.class, () -> BlueprintCompiler.mergeProperties("minecraft:stone", suffix));
    }

    @Test void aggregateBlockFlowAndChunkLimitsAreCheckedBeforeExecution() {
        var doc = document(); var request = request();
        assertThrows(ToolArgError.class, () -> BlueprintCompiler.compile(doc, request, new BlueprintCompiler.Limits(1,1024,2000)));
        assertThrows(ToolArgError.class, () -> BlueprintCompiler.compile(doc, request, new BlueprintCompiler.Limits(500000,1,2000)));
        var water = obj("""
                {"version":1,"components":{"pool":{"fills":[{"from":[0,0,0],"to":[1,0,0],"block":"minecraft:water"}]}},
                 "instances":[{"component":"pool","pos":[0,0,0],"repeat":{"count":3,"step":[3,0,0]}}]}
                """);
        request.addProperty("liquids", "flow");
        assertThrows(ToolArgError.class, () -> BlueprintCompiler.compile(water, request, new BlueprintCompiler.Limits(500000,1024,5)));
        request.addProperty("liquids", "static");
        assertEquals(3, BlueprintCompiler.compile(water, request, new BlueprintCompiler.Limits(500000,1024,5)).size());
    }

    @Test void repeatedAndWorldCoordinateOverflowAreRejected() {
        var doc = document(); doc.getAsJsonArray("instances").get(0).getAsJsonObject().add("repeat", obj("{\"count\":3,\"step\":[2147483647,0,0]}"));
        assertThrows(ToolArgError.class, () -> compile(doc, request()));
        var req = request(); req.getAsJsonObject("transform").add("origin", JsonParser.parseString("[2147483647,0,0]"));
        assertThrows(ToolArgError.class, () -> compile(document(), req));
    }

    @Test void operationExpansionCannotBypassItsOwnCapWithOverlappingPlacements() {
        var doc = obj("""
                {"version":1,"components":{"part":{"blocks":[{"pos":[0,0,0],"block":"minecraft:stone"}]}},
                 "instances":[{"component":"part","pos":[0,0,0],"repeat":{"count":400,"step":[0,0,0]}}]}
                """);
        var blocks = doc.getAsJsonObject("components").getAsJsonObject("part").getAsJsonArray("blocks");
        for (int i = 1; i < 300; i++) blocks.add(blocks.get(0).deepCopy());
        var error = assertThrows(ToolArgError.class, () -> compile(doc, request()));
        assertTrue(error.getMessage().contains("expanded operations"));
    }

    @Test void instanceCountAndWholeDocumentOperationLimitsAreBounded() {
        var doc = document(); var instances = doc.getAsJsonArray("instances");
        instances.get(0).getAsJsonObject().add("repeat", obj("{\"count\":4096,\"step\":[0,0,0]}"));
        instances.add(instances.get(0).deepCopy());
        assertThrows(ToolArgError.class, () -> BlueprintCompiler.validate(doc));
        var huge = document(); var fills = huge.getAsJsonObject("components").getAsJsonObject("window").getAsJsonArray("fills");
        for (int i = 0; i < BlueprintCompiler.MAX_RAW_OPS; i++) fills.add(fills.get(0).deepCopy());
        assertThrows(ToolArgError.class, () -> BlueprintCompiler.validate(huge));
    }
}
