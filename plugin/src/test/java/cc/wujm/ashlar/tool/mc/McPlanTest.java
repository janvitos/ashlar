// SPDX-License-Identifier: AGPL-3.0-or-later
package cc.wujm.ashlar.tool.mc;

import cc.wujm.ashlar.engine.Region;
import cc.wujm.ashlar.rpc.RpcHandler;
import cc.wujm.ashlar.tool.ToolArgError;
import com.google.gson.JsonObject;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class McPlanTest {
    private McBuild build() {
        RpcHandler unexpected = (ctx,args) -> { fail("No writing handler should run"); return null; };
        return new McBuild(unexpected,unexpected,unexpected);
    }
    @Test void planPublishesTheCompleteAuthoritativeBuildSchema() {
        McBuild build = build(); McPlan plan = new McPlan(build,new BuildPreflight(null,null),Runnable::run);
        assertEquals(build.spec().inputSchema(),plan.spec().inputSchema().getAsJsonObject("properties").get("build"));
        assertTrue(plan.spec().annotations().get("readOnlyHint").getAsBoolean());
        assertFalse(plan.spec().annotations().get("destructiveHint").getAsBoolean());
    }
    @Test void planSchemaCannotMutateBuildSchema() {
        McBuild build = build(); McPlan plan = new McPlan(build,new BuildPreflight(null,null),Runnable::run);
        plan.spec().inputSchema().getAsJsonObject("properties").getAsJsonObject("build").addProperty("external",true);
        assertFalse(build.spec().inputSchema().has("external"));
    }
    @Test void missingBuildFailsWithoutCallingAWorldWritingHandler() {
        assertTrue(new McPlan(build(),new BuildPreflight(null,null),Runnable::run).call(null,new JsonObject()).join().isError());
    }
    @Test void previewNumbersAreStrictBeforeAnyWorldWork() {
        McPlan plan = new McPlan(build(),new BuildPreflight(null,null),Runnable::run);
        for (String preview:new String[]{"{\"scale\":1.5}","{\"grid\":1.5}","{\"view\":\"slice\",\"slice\":{\"axis\":\"y\",\"at\":4294967396}}"}) {
            var args = BlueprintCompilerTest.obj("{\"build\":{\"blocks\":[{\"pos\":[0,0,0],\"block\":\"minecraft:stone\"}]},\"preview\":"+preview+"}");
            var result = plan.call(null,args).join(); assertTrue(result.isError());
            assertTrue(result.content().getFirst().toJson().get("text").getAsString().contains("integer"));
        }
    }
    @Test void dryRunNeverFallsThroughWhenServiceIsUnavailable() {
        JsonObject args = BlueprintCompilerTest.obj("{\"dryRun\":true,\"blocks\":[{\"pos\":[0,0,0],\"block\":\"minecraft:stone\"}]}");
        assertTrue(build().call(null,args).join().isError());
    }
    @Test void siteCheckNeverFallsThroughWhenServiceIsUnavailable() {
        JsonObject args = BlueprintCompilerTest.obj("{\"preflight\":true,\"blocks\":[{\"pos\":[0,0,0],\"block\":\"minecraft:stone\"}]}");
        assertTrue(build().call(null,args).join().isError());
    }
    @Test void absoluteCoordinatesRejectFractionsStringsAndOverflowRatherThanTruncating() {
        for (String value:new String[]{"1.5","\"1\"","2147483648"}) {
            var args = BlueprintCompilerTest.obj("{\"blocks\":[{\"pos\":["+value+",0,0],\"block\":\"minecraft:stone\"}]}");
            assertThrows(ToolArgError.class,() -> McBuild.Args.parse(args));
        }
    }
    @Test void absoluteFillAndTextCoordinatesAreStrictToo() {
        assertThrows(ToolArgError.class,() -> McBuild.Args.parse(BlueprintCompilerTest.obj("{\"fills\":[{\"from\":[0.5,0,0],\"to\":[1,1,1],\"block\":\"minecraft:stone\"}]}")));
        assertThrows(ToolArgError.class,() -> McBuild.Args.parse(BlueprintCompilerTest.obj("{\"text\":[{\"text\":\"A\",\"pos\":[0.5,0,0],\"block\":\"minecraft:stone\"}]}")));
    }
    @Test void textScaleAndSpacingRejectFractionalValues() {
        for (String field:new String[]{"scale","spacing"})
            assertThrows(ToolArgError.class,() -> McBuild.Args.parse(BlueprintCompilerTest.obj("{\"text\":[{\"text\":\"A\",\"pos\":[0,0,0],\"block\":\"minecraft:stone\",\""+field+"\":1.5}]}")));
    }
    @Test void horizontalSafetyBoundsStayStrictlyInsideTheThirtyMillionEdge() {
        assertDoesNotThrow(() -> BuildPreflight.checkHorizontal(new Region(-29_999_999,0,-29_999_999,29_999_999,0,29_999_999)));
        assertThrows(ToolArgError.class,() -> BuildPreflight.checkHorizontal(new Region(-30_000_000,0,0,0,0,0)));
        assertThrows(ToolArgError.class,() -> BuildPreflight.checkHorizontal(new Region(0,0,0,30_000_000,0,0)));
        assertThrows(ToolArgError.class,() -> BuildPreflight.checkHorizontal(new Region(0,0,0,0,0,30_000_000)));
    }
}
