// SPDX-License-Identifier: AGPL-3.0-or-later
package cc.wujm.ashlar.tool.mc;

import cc.wujm.ashlar.engine.text.TextExpand;
import cc.wujm.ashlar.tool.ToolArgError;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

class BuildTransformTest {
    private static JsonObject obj(String json) {
        return JsonParser.parseString(json).getAsJsonObject();
    }

    @Test void clockwiseQuarterTurnsAndTranslation() {
        int[][] expected = {{102, 73, 205}, {95, 73, 202}, {98, 73, 195}, {105, 73, 198}};
        for (int i = 0; i < 4; i++) {
            assertArrayEquals(expected[i], new BuildTransform(100, 70, 200, i * 90, "none")
                    .position(new int[] {2, 3, 5}));
        }
    }

    @Test void mirrorPrecedesRotationAndTranslation() {
        assertArrayEquals(new int[] {95, 73, 198}, new BuildTransform(100, 70, 200, 90, "x")
                .position(new int[] {2, 3, 5}));
        assertArrayEquals(new int[] {105, 73, 202}, new BuildTransform(100, 70, 200, 90, "z")
                .position(new int[] {2, 3, 5}));
    }

    @Test void allTransformsPreservePairOffsetsAndVerticalHalves() {
        int[][] vectors = {{1, 0, 0}, {0, 0, 1}, {-1, 0, 0}, {0, 0, -1}};
        String[] names = {"east", "south", "west", "north"};
        for (int rotation : List.of(0, 90, 180, 270)) {
            for (String mirror : List.of("none", "x", "z")) {
                BuildTransform t = new BuildTransform(100, 70, -100, rotation, mirror);
                int[] foot = t.position(new int[] {0, 0, 0});
                for (int j = 0; j < vectors.length; j++) {
                    int[] head = t.position(vectors[j]);
                    int[] delta = {head[0] - foot[0], 0, head[2] - foot[2]};
                    int index = List.of(names).indexOf(t.direction(names[j]));
                    assertArrayEquals(vectors[index], delta);
                }
                assertArrayEquals(new int[] {foot[0], foot[1] + 1, foot[2]}, t.position(new int[] {0, 1, 0}));
                assertEquals("up", t.direction("up"));
                assertEquals("down", t.direction("down"));
            }
        }
    }

    @Test void everyReflectionReversesStairHandednessAndEveryRotationPreservesIt() {
        String[] shapes = {"STRAIGHT", "INNER_LEFT", "INNER_RIGHT", "OUTER_LEFT", "OUTER_RIGHT"};
        String[] reflected = {"STRAIGHT", "INNER_RIGHT", "INNER_LEFT", "OUTER_RIGHT", "OUTER_LEFT"};
        for (int rotation : List.of(0, 90, 180, 270)) {
            for (int i = 0; i < shapes.length; i++) {
                assertEquals(shapes[i], new BuildTransform(0, 0, 0, rotation, "none").stairShape(shapes[i]));
                for (String mirror : List.of("x", "z")) {
                    var t = new BuildTransform(0, 0, 0, rotation, mirror);
                    assertEquals(reflected[i], t.stairShape(shapes[i]));
                    assertEquals(shapes[i], t.stairShape(t.stairShape(shapes[i])));
                }
            }
        }
    }

    @Test void cuboidBoundsMatchAllTransformedCells() {
        for (int rotation : List.of(0, 90, 180, 270)) {
            for (String mirror : List.of("none", "x", "z")) {
                BuildTransform t = new BuildTransform(-100, 64, 200, rotation, mirror);
                int[][] bounds = t.bounds(new int[] {2, 3, 4}, new int[] {-1, 1, -2});
                Set<String> cells = new HashSet<>();
                for (int x = -1; x <= 2; x++) for (int y = 1; y <= 3; y++) for (int z = -2; z <= 4; z++) {
                    int[] p = t.position(new int[] {x, y, z});
                    cells.add(java.util.Arrays.toString(p));
                    for (int a = 0; a < 3; a++) assertTrue(p[a] >= bounds[0][a] && p[a] <= bounds[1][a]);
                }
                long volume = 1;
                for (int a = 0; a < 3; a++) volume *= bounds[1][a] - bounds[0][a] + 1;
                assertEquals(cells.size(), volume);
            }
        }
    }

    @Test void textRunsExactlyMatchTransformedGlyphPixels() {
        for (String facing : TextExpand.FACINGS) {
            TextExpand.Result original = TextExpand.expand("R\nL", new int[] {0, 0, 0}, facing, 2, 1, "center");
            for (int rotation : List.of(0, 90, 180, 270)) for (String mirror : List.of("none", "x", "z")) {
                BuildTransform t = new BuildTransform(100, 70, 200, rotation, mirror);
                TextExpand.Result transformed = t.text(original);
                Set<String> expected = new HashSet<>();
                for (TextExpand.Run run : original.inkRuns()) {
                    for (int[] p : cells(run)) expected.add(java.util.Arrays.toString(t.position(p)));
                }
                Set<String> actual = new HashSet<>();
                for (TextExpand.Run run : transformed.inkRuns()) {
                    for (int[] p : cells(run)) actual.add(java.util.Arrays.toString(p));
                }
                assertEquals(expected, actual);
                assertEquals(original.inkBlockCount(), transformed.inkBlockCount());
                assertArrayEquals(t.bounds(original.bboxMin(), original.bboxMax())[0], transformed.bboxMin());
                assertArrayEquals(t.bounds(original.bboxMin(), original.bboxMax())[1], transformed.bboxMax());
            }
        }
    }

    private static List<int[]> cells(TextExpand.Run run) {
        var result = new java.util.ArrayList<int[]>();
        int[] a = run.from(), b = run.to();
        for (int x = Math.min(a[0], b[0]); x <= Math.max(a[0], b[0]); x++)
            for (int y = Math.min(a[1], b[1]); y <= Math.max(a[1], b[1]); y++)
                for (int z = Math.min(a[2], b[2]); z <= Math.max(a[2], b[2]); z++) result.add(new int[] {x, y, z});
        return result;
    }

    @Test void argsTransformFillsBlocksTextAndPreserveMetadata() {
        McBuild.Args a = McBuild.Args.parse(obj("""
                {"world":"world","transform":{"origin":[100,70,200],"rotation":90,"mirror":"x"},
                 "snapshot":true,"connect":false,"liquids":"static",
                 "fills":[{"from":[0,0,0],"to":[2,3,4],"block":"minecraft:stone","mode":"hollow","filter":"minecraft:air"}],
                 "blocks":[{"pos":[1,2,3],"block":"minecraft:oak_wall_sign[facing=north]","sign":{"front":["Hello"],"back":["World"]}}],
                 "text":[{"text":"R","pos":[0,5,0],"block":"minecraft:white_concrete","background":"minecraft:black_concrete"}]}
                """));
        assertArrayEquals(new int[] {96, 70, 198}, a.fills().getFirst().from());
        assertArrayEquals(new int[] {100, 73, 200}, a.fills().getFirst().to());
        assertArrayEquals(new int[] {97, 72, 199}, a.blocks().getFirst().pos());
        assertEquals("Hello", a.blocks().getFirst().sign().front().getFirst());
        assertEquals("World", a.blocks().getFirst().sign().back().getFirst());
        assertEquals("west", a.text().getFirst().facing());
        assertEquals("world", a.world());
        assertTrue(a.snapshot());
        assertFalse(a.connect());
        assertEquals("static", a.liquids());
    }

    @Test void omittedTransformPreservesExistingArgumentsExactly() {
        var a = McBuild.Args.parse(obj("""
                {"fills":[{"from":[5,70,8],"to":[0,60,0],"block":"minecraft:oak_stairs"}],
                 "blocks":[{"pos":[-10,64,8],"block":"minecraft:oak_sign"}]}
                """));
        assertArrayEquals(new int[] {5, 70, 8}, a.fills().getFirst().from());
        assertEquals("minecraft:oak_stairs", a.fills().getFirst().block());
        assertEquals("minecraft:oak_sign", a.blocks().getFirst().block());
        assertArrayEquals(new int[] {-10, 64, 8}, a.blocks().getFirst().pos());
    }

    @Test void invalidTransformsAndCoordinatesFailBeforeExecution() {
        for (String transform : List.of("null", "[]", "{}", "{\"origin\":[0,0]}",
                "{\"origin\":[0,0,0],\"rotation\":45}", "{\"origin\":[0,0,0],\"rotation\":90.1}",
                "{\"origin\":[0,0,0],\"rotation\":\"90\"}", "{\"origin\":[0,0,0],\"mirror\":\"y\"}",
                "{\"origin\":[0.5,0,0]}", "{\"origin\":[2147483648,0,0]}",
                "{\"origin\":[0,0,0],\"rotaton\":90}")) {
            // Null retains the project's optional-field semantics: treat it as omitted.
            if (transform.equals("null")) continue;
            assertThrows(ToolArgError.class, () -> BuildTransform.parse(obj("{\"transform\":" + transform + "}")));
        }
        for (String pos : List.of("[0.5,0,0]", "[2147483648,0,0]", "[\"1\",0,0]")) {
            assertThrows(ToolArgError.class, () -> McBuild.Args.parse(obj("{\"transform\":{\"origin\":[0,0,0]},"
                    + "\"blocks\":[{\"pos\":" + pos + ",\"block\":\"minecraft:stone\"}]}")));
        }
        assertThrows(ToolArgError.class, () -> new BuildTransform(Integer.MAX_VALUE, 0, 0, 0, "none")
                .position(new int[] {1, 0, 0}));
        assertThrows(ToolArgError.class, () -> new BuildTransform(0, 0, 0, 180, "none")
                .position(new int[] {Integer.MIN_VALUE, 0, 0}));
        assertArrayEquals(new int[] {Integer.MAX_VALUE, 0, 0}, new BuildTransform(-1, 0, 0, 180, "none")
                .position(new int[] {Integer.MIN_VALUE, 0, 0}));
    }

    @Test void localTextOverflowIsCheckedAfterWorldTranslation() {
        assertThrows(ToolArgError.class, () -> McBuild.Args.parse(obj("""
                {"transform":{"origin":[0,0,0]},"text":[
                 {"text":"R","pos":[2147483647,0,0],"block":"minecraft:stone"}]}
                """)));
        var a = McBuild.Args.parse(obj("""
                {"transform":{"origin":[-2147483648,0,0]},"text":[
                 {"text":"R","pos":[2147483647,0,0],"block":"minecraft:stone"}]}
                """));
        assertArrayEquals(new int[] {-1, 0, 0}, a.text().getFirst().expanded().bboxMin());
        assertArrayEquals(new int[] {3, 6, 0}, a.text().getFirst().expanded().bboxMax());
    }

    @Test void translationAloneDoesNotNeedNativeStateConversion() {
        assertFalse(new BuildTransform(100, 70, 200, 0, "none").changesOrientation());
        assertTrue(new BuildTransform(0, 0, 0, 90, "none").changesOrientation());
        assertTrue(new BuildTransform(0, 0, 0, 0, "x").changesOrientation());
    }

    @Test void transformedFiltersKeepUnspecifiedPropertiesAsWildcards() {
        BuildTransform t = new BuildTransform(0, 0, 0, 90, "none");
        assertEquals("minecraft:oak_stairs[facing=east]", BuildStateTransform.partialFilter(
                "minecraft:oak_stairs[facing=north]",
                "minecraft:oak_stairs[facing=east,half=bottom,shape=straight,waterlogged=false]", t));
        assertEquals("minecraft:oak_stairs", BuildStateTransform.partialFilter("minecraft:oak_stairs",
                "minecraft:oak_stairs[facing=east,half=bottom,shape=straight,waterlogged=false]", t));
        assertEquals("minecraft:glass_pane[east=true]", BuildStateTransform.partialFilter(
                "minecraft:glass_pane[north=true]",
                "minecraft:glass_pane[east=true,north=false,south=false,west=false,waterlogged=false]", t));
        assertEquals("minecraft:oak_door[hinge=right]", BuildStateTransform.partialFilter(
                "minecraft:oak_door[hinge=left]",
                "minecraft:oak_door[facing=north,half=lower,hinge=right,open=false,powered=false]",
                new BuildTransform(0, 0, 0, 0, "x")));
    }
}
