// SPDX-License-Identifier: AGPL-3.0-or-later
package cc.wujm.ashlar.handler;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import cc.wujm.ashlar.player.PlayerJson;
import cc.wujm.ashlar.rpc.InvocationContext;
import cc.wujm.ashlar.rpc.MainThread;
import cc.wujm.ashlar.rpc.RpcHandler;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;

import java.util.concurrent.CompletableFuture;

/**
 * {@code players}: read-only roster of online players, giving spatial
 * context for requests like "build here" or "put it in front of me". Params:
 * {@code {}}. All Bukkit access happens on the main thread via
 * {@link MainThread#call}; not recorded in {@link cc.wujm.ashlar.log.OperationLog}
 * since it does not touch the world. Per-player JSON is built by
 * {@link PlayerJson#describe}, shared with the {@code chat} event
 * (step6a-prompt.md).
 *
 * <p>Fully stateless, so {@link #list} is a public static method (plan.md
 * step7) rather than a separate service class, reusable by the in-process
 * tool layer.
 */
public final class PlayersHandler implements RpcHandler {

    @Override
    public CompletableFuture<JsonElement> handle(InvocationContext ctx, JsonObject params) {
        int range = PlayerJson.DEFAULT_LOOK_RANGE;
        if (params != null && params.has("lookRange")) {
            var v = params.get("lookRange");
            if (!v.isJsonPrimitive() || !v.getAsJsonPrimitive().isNumber() || v.getAsDouble() != Math.rint(v.getAsDouble())
                    || v.getAsInt() < PlayerJson.DEFAULT_LOOK_RANGE || v.getAsInt() > PlayerJson.MAX_LOOK_RANGE) {
                throw new cc.wujm.ashlar.rpc.RpcError(cc.wujm.ashlar.rpc.ErrorCode.BAD_REQUEST,
                        "lookRange must be an integer " + PlayerJson.DEFAULT_LOOK_RANGE + "-" + PlayerJson.MAX_LOOK_RANGE);
            }
            range = v.getAsInt();
        }
        return list(ctx, range);
    }

    public static CompletableFuture<JsonElement> list(InvocationContext ctx) {
        return list(ctx, PlayerJson.DEFAULT_LOOK_RANGE);
    }

    public static CompletableFuture<JsonElement> list(InvocationContext ctx, int lookRange) {
        return MainThread.call(() -> {
            JsonArray players = new JsonArray();
            for (Player player : Bukkit.getOnlinePlayers()) {
                players.add(PlayerJson.describe(player, lookRange));
            }
            JsonObject result = new JsonObject();
            result.addProperty("count", players.size());
            result.add("players", players);
            return (JsonElement) result;
        });
    }
}
