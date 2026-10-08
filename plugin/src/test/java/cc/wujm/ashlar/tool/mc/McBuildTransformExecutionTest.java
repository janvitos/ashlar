// SPDX-License-Identifier: AGPL-3.0-or-later
package cc.wujm.ashlar.tool.mc;

import cc.wujm.ashlar.rpc.RpcHandler;
import cc.wujm.ashlar.tool.ToolSpec;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;

import static org.junit.jupiter.api.Assertions.*;

class McBuildTransformExecutionTest {
    private static JsonObject obj(String json) {
        return JsonParser.parseString(json).getAsJsonObject();
    }

    @Test void translatedSnapshotAndHandlersUseWorldCoordinatesInOriginalOrder() {
        List<String> calls = new ArrayList<>();
        List<JsonObject> captured = new ArrayList<>();
        RpcHandler snapshot = (ctx, params) -> {
            calls.add("snapshot");
            captured.add(params.deepCopy());
            return CompletableFuture.completedFuture(obj("{\"id\":\"test-snapshot\",\"volume\":153}"));
        };
        RpcHandler fills = (ctx, params) -> {
            calls.add("fill");
            captured.add(params.deepCopy());
            JsonObject response = obj("{\"totalChanged\":1,\"totalVolume\":1,\"elapsedMs\":1,\"warnings\":[]}");
            JsonArray ops = new JsonArray();
            for (int i = 0; i < params.getAsJsonArray("ops").size(); i++) {
                JsonObject op = obj("{\"changed\":1,\"volume\":1}");
                op.addProperty("index", i);
                ops.add(op);
            }
            response.add("ops", ops);
            return CompletableFuture.completedFuture(response);
        };
        RpcHandler blocks = (ctx, params) -> {
            calls.add("blocks");
            captured.add(params.deepCopy());
            return CompletableFuture.completedFuture(obj("{\"changed\":1,\"requested\":1,\"elapsedMs\":1,\"warnings\":[]}"));
        };
        var result = new McBuild(snapshot, fills, blocks).call(null, obj("""
                {"world":"world","transform":{"origin":[100,64,200]},"snapshot":true,"connect":false,
                 "fills":[{"from":[0,0,0],"to":[2,0,2],"block":"minecraft:stone"}],
                 "text":[{"text":"R","pos":[10,2,0],"block":"minecraft:white_concrete"}],
                 "blocks":[{"pos":[-2,1,0],"block":"minecraft:oak_sign","sign":{"front":["Hello"]}}]}
                """)).join();
        assertFalse(result.isError(), result.toJson().toString());
        assertEquals(List.of("snapshot", "fill", "fill", "blocks"), calls);
        assertEquals("[98,64,200]", captured.get(0).get("from").toString());
        assertEquals("[114,72,202]", captured.get(0).get("to").toString());
        assertEquals("[100,64,200]", captured.get(1).getAsJsonArray("ops").get(0).getAsJsonObject().get("from").toString());
        assertEquals("[98,65,200]", captured.get(3).getAsJsonArray("blocks").get(0).getAsJsonObject().get("pos").toString());
        assertEquals("Hello", captured.get(3).getAsJsonArray("blocks").get(0).getAsJsonObject()
                .getAsJsonObject("sign").getAsJsonArray("front").get(0).getAsString());
        for (JsonObject params : captured) assertEquals("world", params.get("world").getAsString());
        assertFalse(captured.get(1).get("connect").getAsBoolean());
    }

    @Test void overflowingTextFailsBeforeSnapshotOrAnyWrites() {
        RpcHandler unexpected = (ctx, params) -> { fail("No handler should run"); return null; };
        var result = new McBuild(unexpected, unexpected, unexpected).call(null, obj("""
                {"transform":{"origin":[2147483647,0,0]},"snapshot":true,
                 "fills":[{"from":[0,0,0],"to":[0,0,0],"block":"minecraft:stone"}],
                 "text":[{"text":"R","pos":[0,0,0],"block":"minecraft:stone"}]}
                """)).join();
        assertTrue(result.isError());
        assertTrue(result.toJson().toString().contains("coordinate exceeds"));
    }

    @Test void invalidTransformFailsBeforeAnyHandlersRun() {
        RpcHandler unexpected = (ctx, params) -> { fail("No handler should run"); return null; };
        var result = new McBuild(unexpected, unexpected, unexpected).call(null, obj("""
                {"transform":{"origin":[0,64,0],"rotation":90.5},
                 "blocks":[{"pos":[0,0,0],"block":"minecraft:stone"}]}
                """)).join();
        assertTrue(result.isError());
    }

    @Test void catalogPublishesOptionalTransformWithRequiredOrigin() {
        var schema = ToolSpec.load("mc_build").inputSchema();
        assertFalse(schema.has("required"));
        var transform = schema.getAsJsonObject("properties").getAsJsonObject("transform");
        assertEquals("[\"origin\"]", transform.get("required").toString());
        assertEquals("[0,90,180,270]", transform.getAsJsonObject("properties").getAsJsonObject("rotation").get("enum").toString());
        assertFalse(transform.get("additionalProperties").getAsBoolean());
    }
}
