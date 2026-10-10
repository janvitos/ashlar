// SPDX-License-Identifier: AGPL-3.0-or-later
package cc.wujm.ashlar.tool.mc;

import cc.wujm.ashlar.rpc.InvocationContext;
import cc.wujm.ashlar.tool.ToolArgError;
import cc.wujm.ashlar.tool.ToolSpec;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class McDiffTest {
    private static McDiff.Args parse(String against, String extra) {
        String json = "{\"from\":[0,60,0],\"to\":[4,64,4],\"against\":" + against + (extra.isEmpty() ? "" : "," + extra) + "}";
        return McDiff.Args.parse(JsonParser.parseString(json).getAsJsonObject());
    }

    @Test void defaultsAndReferenceKinds() {
        var a = parse("{\"snapshot\":\"snap-1\"}", "");
        assertEquals("snap-1", a.against().snapshot());
        assertFalse(a.declaredOnly());
        assertFalse(a.materialOnly());
        assertEquals("summary", a.format());
        assertEquals(50, a.samples());
        var e = parse("{\"expected\":[[1,60,1,\"stone\"],[2,61,2,\"oak_stairs[facing=east]\"]]}",
                "\"scope\":\"declared\",\"compare\":\"material\",\"anomalies\":[\"floating\",\"stacked\"],\"format\":\"cells\"");
        assertEquals(2, e.against().expected().size());
        assertTrue(e.declaredOnly());
        assertTrue(e.materialOnly());
        assertEquals(2, e.anomalies().size());
        assertEquals("expected file walls_v2", parse("{\"expectedFile\":\"walls_v2\"}", "").against().describe());
        assertEquals("blueprint house", parse("{\"blueprint\":{\"id\":\"house\"},\"transform\":{\"rotate\":90}}", "").against().describe());
    }

    @Test void invalidArgumentsAreRejected() {
        assertThrows(ToolArgError.class, () -> parse("{}", ""));
        assertThrows(ToolArgError.class, () -> parse("{\"snapshot\":\"a\",\"expectedFile\":\"b\"}", ""));
        assertThrows(ToolArgError.class, () -> parse("{\"snapshot\":\"a\",\"transform\":{}}", ""));
        assertThrows(ToolArgError.class, () -> parse("{\"snapshot\":\"a\"}", "\"baselineSnapshot\":\"b\""));
        assertThrows(ToolArgError.class, () -> parse("{\"expectedFile\":\"x\"}", "\"baselineSnapshot\":\"b\",\"scope\":\"declared\""));
        assertThrows(ToolArgError.class, () -> parse("{\"expectedFile\":\"../etc/x\"}", ""));
        assertThrows(ToolArgError.class, () -> parse("{\"expected\":[]}", ""));
        assertThrows(ToolArgError.class, () -> parse("{\"expected\":[[1,2,\"stone\"]]}", ""));
        assertThrows(ToolArgError.class, () -> parse("{\"expected\":[[1.5,2,3,\"stone\"]]}", ""));
        assertThrows(ToolArgError.class, () -> parse("{\"snapshot\":\"a\"}", "\"anomalies\":[\"floating\",\"floating\"]"));
        assertThrows(ToolArgError.class, () -> parse("{\"snapshot\":\"a\"}", "\"anomalies\":[\"lava\"]"));
        assertThrows(ToolArgError.class, () -> parse("{\"snapshot\":\"a\"}", "\"ignore\":[\"stone[\"]"));
        assertThrows(ToolArgError.class, () -> parse("{\"snapshot\":\"a\"}", "\"samples\":0"));
        assertThrows(ToolArgError.class, () -> parse("{\"snapshot\":\"a\"}", "\"format\":\"xml\""));
        assertThrows(ToolArgError.class, () -> parse("{\"snapshot\":\"a\",\"extra\":1}", ""));
    }

    @Test void specIsReadOnly() {
        ToolSpec spec = ToolSpec.load("mc_diff");
        assertEquals("mc_diff", spec.name());
    }

    @Test void repairRejectsDiffIdMixedWithPlanArguments() {
        var args = new JsonObject();
        args.addProperty("diffId", "diff-x");
        args.addProperty("planId", "plan-x");
        var result = new McRepair(null, null).call(InvocationContext.system("test"), args).join();
        assertTrue(result.isError());
    }
}
