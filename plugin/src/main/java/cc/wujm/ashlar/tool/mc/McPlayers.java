// SPDX-License-Identifier: AGPL-3.0-or-later
package cc.wujm.ashlar.tool.mc;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import cc.wujm.ashlar.player.PlayerJson;
import cc.wujm.ashlar.rpc.InvocationContext;
import cc.wujm.ashlar.rpc.RpcHandler;
import cc.wujm.ashlar.tool.ArgParse;
import cc.wujm.ashlar.tool.Tool;
import cc.wujm.ashlar.tool.ToolArgError;
import cc.wujm.ashlar.tool.ToolResult;
import cc.wujm.ashlar.tool.ToolRunner;
import cc.wujm.ashlar.tool.ToolSpec;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.CompletableFuture;

/** Pure-Java port of {@code mcp-server/src/tools/mc-players.ts}. No text differences from the TS tool. */
public final class McPlayers implements Tool {

    private final ToolSpec spec = ToolSpec.load("mc_players");
    private final RpcHandler playersHandler;

    public McPlayers(RpcHandler playersHandler) {
        this.playersHandler = playersHandler;
    }

    @Override
    public ToolSpec spec() {
        return spec;
    }

    @Override
    public CompletableFuture<ToolResult> call(InvocationContext ctx, JsonObject args) {
        return ToolRunner.runText("mc_players", () -> playersHandler.handle(ctx, params(args)).thenApply(el -> {
            JsonObject r = el.getAsJsonObject();
            int count = r.get("count").getAsInt();
            if (count == 0) {
                return "No players online.";
            }
            String name = nameFilter(args);
            List<String> lines = new ArrayList<>();
            for (JsonElement pe : r.getAsJsonArray("players")) {
                JsonObject p = pe.getAsJsonObject();
                if (name != null && !p.get("name").getAsString().equalsIgnoreCase(name)) continue;
                JsonArray pos = p.getAsJsonArray("pos");
                JsonArray front = p.getAsJsonArray("inFront");
                lines.add(p.get("name").getAsString() + "  " + p.get("world").getAsString() + "  pos x=" + pos.get(0).getAsInt()
                        + " y=" + pos.get(1).getAsInt() + " z=" + pos.get(2).getAsInt() + "  facing " + p.get("facing").getAsString()
                        + " (block in front: " + front.get(0).getAsInt() + "," + front.get(1).getAsInt() + "," + front.get(2).getAsInt() + ")  "
                        + p.get("gameMode").getAsString().toLowerCase(Locale.ROOT)
                        + eyeSuffix(p)
                        + lookingAtSuffix(p));
            }
            if (lines.isEmpty()) {
                return "No online player named " + name + " (" + count + " online).";
            }
            return String.join("\n", lines);
        }));
    }

    static JsonObject params(JsonObject args) {
        JsonObject params = new JsonObject();
        for (String k : args.keySet()) {
            if (!k.equals("lookRange") && !k.equals("name")) throw new ToolArgError("unknown parameter: " + k);
        }
        if (ArgParse.has(args, "lookRange")) {
            int r = BuildTransform.strictInt(args.get("lookRange"), "lookRange");
            if (r < PlayerJson.DEFAULT_LOOK_RANGE || r > PlayerJson.MAX_LOOK_RANGE) {
                throw new ToolArgError("lookRange: must be " + PlayerJson.DEFAULT_LOOK_RANGE + "-" + PlayerJson.MAX_LOOK_RANGE);
            }
            params.addProperty("lookRange", r);
        }
        nameFilter(args);
        return params;
    }

    /** The optional player name to report (case-insensitive), or null for everyone. */
    static String nameFilter(JsonObject args) {
        String name = ArgParse.optString(args, "name");
        if (name != null && name.isBlank()) throw new ToolArgError("name: must not be blank");
        return name;
    }

    private static String eyeSuffix(JsonObject p) {
        JsonElement eye = p.get("eye");
        if (eye == null || !eye.isJsonArray()) {
            return "";
        }
        JsonArray e = eye.getAsJsonArray();
        return String.format(Locale.ROOT, "  eye %.2f,%.2f,%.2f yaw %.1f pitch %.1f", e.get(0).getAsDouble(), e.get(1).getAsDouble(),
                e.get(2).getAsDouble(), p.get("yaw").getAsDouble(), p.get("pitch").getAsDouble());
    }

    private static String lookingAtSuffix(JsonObject p) {
        JsonElement la = p.get("lookingAt");
        if (la == null || la.isJsonNull()) {
            return "";
        }
        JsonObject o = la.getAsJsonObject();
        JsonArray pos = o.getAsJsonArray("pos");
        return "  looking at " + AngledRenderText.state(o.get("block").getAsString(), cc.wujm.ashlar.engine.BukkitStateDefaults.CANON::canon) + " at " + pos.get(0).getAsInt() + "," + pos.get(1).getAsInt()
                + "," + pos.get(2).getAsInt() + " (" + o.get("face").getAsString() + " face)";
    }
}
