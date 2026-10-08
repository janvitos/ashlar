// SPDX-License-Identifier: AGPL-3.0-or-later
package cc.wujm.ashlar.tool.mc;

import cc.wujm.ashlar.engine.BuildExpectation;
import cc.wujm.ashlar.rpc.InvocationContext;
import cc.wujm.ashlar.tool.ToolArgError;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;
import java.util.List;
import java.util.Set;
import static org.junit.jupiter.api.Assertions.*;

class McVerificationTest {
    private static JsonObject json(String s){return JsonParser.parseString(s).getAsJsonObject();}
    private static VerificationStore.Comparison comparison(){var cells=java.util.stream.IntStream.range(0,2).mapToObj(i -> new BuildExpectation.Observed(new BuildExpectation.Cell(new BuildExpectation.Pos(i,100,0),"minecraft:stone",null),"minecraft:air",null,false)).toList();return new VerificationStore.Comparison("id",false,cells);}
    @Test void nestedBuildSchemaIsAuthoritative(){var b=new McBuild((c,a)->null,(c,a)->null,(c,a)->null);assertEquals(b.spec().inputSchema(),new McVerify(b,null).spec().inputSchema().getAsJsonObject("properties").get("build"));}
    @Test void integerControlsRejectFractionOverflowAndRange(){for(String v:List.of("1.5","2147483648","0","10001"))assertThrows(ToolArgError.class,() -> McVerify.bounded(json("{\"maxChanges\":"+v+"}"),"maxChanges",1000,1,10000));assertEquals(1000,McVerify.bounded(new JsonObject(),"maxChanges",1000,1,10000));}
    @Test void duplicateAndFractionalPositionsReject(){assertThrows(ToolArgError.class,() -> McRepair.positions(json("{\"positions\":[[1,2,3],[1,2,3]]}")));assertThrows(ToolArgError.class,() -> McRepair.positions(json("{\"positions\":[[1.5,2,3]]}")));assertNull(McRepair.positions(new JsonObject()));}
    @Test void subsetContainsOnlyObservedMismatches(){var c=comparison();assertEquals(1,VerificationService.select(c,Set.of(new BuildExpectation.Pos(1,100,0)),1).size());assertThrows(ToolArgError.class,() -> VerificationService.select(c,Set.of(new BuildExpectation.Pos(8,100,0)),10));}
    @Test void oversizedSelectionNeverSilentlyTruncates(){assertThrows(ToolArgError.class,() -> VerificationService.select(comparison(),null,1));assertEquals(2,VerificationService.select(comparison(),null,2).size());}
    @Test void repairRequiresIdentifiersBeforeAccessingService(){var result=new McRepair(null).call(InvocationContext.system("test"),new JsonObject()).join();assertTrue(result.isError());}
}
