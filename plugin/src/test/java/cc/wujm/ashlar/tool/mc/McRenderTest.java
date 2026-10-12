// SPDX-License-Identifier: AGPL-3.0-or-later
package cc.wujm.ashlar.tool.mc;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import cc.wujm.ashlar.tool.ToolArgError;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Argument parsing tests for {@link McRender}, mirroring mc-render.ts's zod schema and body-level checks. */
class McRenderTest {

    private static JsonObject obj(String json) {
        return JsonParser.parseString(json).getAsJsonObject();
    }

    @Test
    void topViewAcceptsTwoElementCorners() {
        McRender.Args a = McRender.Args.parse(obj("{\"from\":[0,0],\"to\":[10,10]}"));
        assertArrayEquals(new int[]{0, 0}, a.from());
        assertArrayEquals(new int[]{10, 10}, a.to());
    }

    @Test
    void facadeViewAcceptsThreeElementCorners() {
        McRender.Args a = McRender.Args.parse(obj("{\"from\":[0,60,0],\"to\":[10,70,10],\"view\":\"north\"}"));
        assertArrayEquals(new int[]{0, 60, 0}, a.from());
    }

    @Test
    void missingFromThrows() {
        assertThrows(ToolArgError.class, () -> McRender.Args.parse(obj("{\"to\":[10,10]}")));
    }

    @Test
    void fromWithOneElementThrows() {
        assertThrows(ToolArgError.class, () -> McRender.Args.parse(obj("{\"from\":[0],\"to\":[10,10]}")));
    }

    @Test
    void invalidViewThrows() {
        assertThrows(ToolArgError.class, () -> McRender.Args.parse(obj("{\"from\":[0,0],\"to\":[10,10],\"view\":\"south-east\"}")));
    }

    @Test
    void sliceRequiresAxisAndAt() {
        assertThrows(ToolArgError.class, () -> McRender.Args.parse(
                obj("{\"from\":[0,60,0],\"to\":[10,70,10],\"view\":\"slice\",\"slice\":{\"axis\":\"y\"}}")));
    }

    @Test
    void scaleOutOfRangeThrows() {
        ToolArgError e = assertThrows(ToolArgError.class,
                () -> McRender.Args.parse(obj("{\"from\":[0,0],\"to\":[10,10],\"scale\":17}")));
        assertEquals("scale: must be between 0 and 16, got 17", e.getMessage());
    }

    @Test
    void gridOutOfRangeThrows() {
        assertThrows(ToolArgError.class, () -> McRender.Args.parse(obj("{\"from\":[0,0],\"to\":[10,10],\"grid\":65}")));
    }

    @Test
    void contourOutOfRangeThrows() {
        assertThrows(ToolArgError.class, () -> McRender.Args.parse(obj("{\"from\":[0,0],\"to\":[10,10],\"contour\":4097}")));
    }

    @Test
    void scaleAndGridWithinRangeParse() {
        McRender.Args a = McRender.Args.parse(obj("{\"from\":[0,0],\"to\":[10,10],\"scale\":4,\"grid\":20}"));
        assertEquals(4, a.scale());
        assertEquals(20, a.grid());
    }

    @Test
    void angledViewsParseCameraAndRequireThreeDimensions() {
        var a=McRender.Args.parse(obj("{\"from\":[0,60,0],\"to\":[10,70,10],\"view\":\"perspective\",\"camera\":{\"azimuth\":45.5,\"fov\":60}}"));
        assertEquals(45.5,a.camera().get("azimuth").getAsDouble());
        assertThrows(ToolArgError.class,() -> McRender.Args.parse(obj("{\"from\":[0,0],\"to\":[10,10],\"view\":\"isometric\"}")));
    }
    @Test
    void angledCoordinatesAndControlsRejectFractionalOverflow() {
        for(String field:new String[]{"\"from\":[0.5,60,0],\"to\":[10,70,10]","\"from\":[2147483648,60,0],\"to\":[10,70,10]","\"from\":[0,60,0],\"to\":[10,70,10],\"scale\":1.5","\"from\":[0,60,0],\"to\":[10,70,10],\"grid\":10"})
            assertThrows(ToolArgError.class,() -> McRender.Args.parse(obj("{"+field+",\"view\":\"isometric\"}")));
    }
    @Test
    void cameraOnFlatViewAndFovOnIsometricReject() {
        assertThrows(IllegalArgumentException.class,() -> McRender.Args.parse(obj("{\"from\":[0,0],\"to\":[10,10],\"view\":\"top\",\"camera\":{}}")));
        assertThrows(IllegalArgumentException.class,() -> McRender.Args.parse(obj("{\"from\":[0,60,0],\"to\":[10,70,10],\"view\":\"isometric\",\"camera\":{\"fov\":50}}")));
    }

    // Body-level checks (mc-render.ts's plain `throw new Error(...)` statements), extracted as static helpers.

    @Test
    void footprintOnlyWithNonTopHeightmapViewThrows() {
        ToolArgError e = assertThrows(ToolArgError.class, () -> McRender.checkShapeCompat(true, "north"));
        assertEquals("mc_render view \"north\" needs [x, y, z] corners; only \"top\" and \"heightmap\" accept [x, z].", e.getMessage());
    }

    @Test
    void footprintOnlyWithTopViewDoesNotThrow() {
        assertDoesNotThrow(() -> McRender.checkShapeCompat(true, "top"));
    }

    @Test
    void mismatchedFromToLengthsThrows() {
        ToolArgError e = assertThrows(ToolArgError.class, () -> McRender.checkFromToLengthsMatch(true, 2, 3));
        assertEquals("mc_render: from and to must both be [x, z] or both be [x, y, z].", e.getMessage());
    }

    @Test
    void areaOverLimitThrows() {
        assertThrows(ToolArgError.class, () -> McRender.checkArea("top", 200_001));
    }

    @Test
    void volumeOverLimitThrows() {
        assertThrows(ToolArgError.class, () -> McRender.checkVolume(200_001));
    }

    @Test
    void angledVolumeUsesItsOwnLimit() {
        assertDoesNotThrow(() -> McRender.checkVolume(new int[] {0, 0, 0}, new int[] {159, 79, 155}, 2_000_000, " for angled views"));
        ToolArgError e = assertThrows(ToolArgError.class, () -> McRender.checkVolume(new int[] {0, 0, 0}, new int[] {159, 79, 159}, 2_000_000, " for angled views"));
        assertTrue(e.getMessage().startsWith("mc_render region volume 2048000 exceeds the 2,000,000-block limit for angled views. "), e.getMessage());
        assertThrows(ToolArgError.class, () -> McRender.checkVolume(new int[] {0, 0, 0}, new int[] {99, 20, 99}));
    }

    @Test
    void sliceViewWithoutSliceParamThrows() {
        ToolArgError e = assertThrows(ToolArgError.class, () -> McRender.checkSliceRequired("slice", null));
        assertEquals("mc_render view \"slice\" requires the \"slice\" parameter: {\"axis\": \"x\"|\"y\"|\"z\", \"at\": <coordinate>}.", e.getMessage());
    }

    @Test
    void nonSliceViewWithoutSliceParamDoesNotThrow() {
        assertDoesNotThrow(() -> McRender.checkSliceRequired("top", null));
    }

    @Test
    void zoomBecomesATightIsometricCube() {
        JsonObject z = McRender.applyZoom(obj("{\"zoom\":{\"pos\":[10,64,-5],\"radius\":3}}"));
        assertEquals("isometric", z.get("view").getAsString());
        McRender.Args a = McRender.Args.parse(z);
        assertArrayEquals(new int[]{7, 61, -8}, a.from());
        assertArrayEquals(new int[]{13, 67, -2}, a.to());
        assertEquals(32, a.scale());
        assertEquals(0, McRender.Args.parse(McRender.applyZoom(obj("{\"zoom\":{\"pos\":[0,0,0],\"radius\":8}}"))).scale());
        assertThrows(ToolArgError.class, () -> McRender.Args.parse(obj("{\"from\":[0,0,0],\"to\":[1,1,1],\"view\":\"north\",\"scale\":32}")));
        assertEquals(32, McRender.Args.parse(obj("{\"from\":[0,0,0],\"to\":[1,1,1],\"view\":\"isometric\",\"scale\":32}")).scale());
        assertEquals(4, McRender.Args.parse(McRender.applyZoom(obj("{\"view\":\"perspective\",\"zoom\":{\"pos\":[0,0,0]}}"))).to()[0]);
    }

    @Test
    void zoomRejectsBadCombinations() {
        assertThrows(ToolArgError.class, () -> McRender.applyZoom(obj("{\"zoom\":{\"pos\":[0,0,0]},\"from\":[0,0,0],\"to\":[1,1,1]}")));
        assertThrows(ToolArgError.class, () -> McRender.applyZoom(obj("{\"zoom\":{\"pos\":[0,0,0]},\"view\":\"top\"}")));
        assertThrows(ToolArgError.class, () -> McRender.applyZoom(obj("{\"zoom\":{\"pos\":[0,0,0],\"radius\":17}}")));
        assertThrows(ToolArgError.class, () -> McRender.applyZoom(obj("{\"zoom\":{\"pos\":[0,0,0],\"size\":2}}")));
    }
}
