// SPDX-License-Identifier: AGPL-3.0-or-later
package cc.wujm.ashlar.tool.mc;

import cc.wujm.ashlar.config.ConfigHolder;
import cc.wujm.ashlar.engine.RequestValidator;
import cc.wujm.ashlar.engine.ViewService;
import cc.wujm.ashlar.tool.ArgParse;
import cc.wujm.ashlar.tool.ToolArgError;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import java.util.Locale;
import java.util.concurrent.CompletableFuture;

/**
 * The eye of a first-person render or sightline: either {@code eye:[x,y,z]} (the eye itself, feet
 * + 1.62 for a standing player) or {@code player:"name"} (that player's live eye and look direction).
 * {@code yaw}/{@code pitch} override the player's direction.
 */
record EyeArgs(double[] eye, String player, String world, Double yaw, Double pitch) {

    static final double STANDING_EYE = 1.62;

    static EyeArgs parse(JsonObject o, boolean directionRequiredWithEye) {
        boolean hasEye = ArgParse.has(o, "eye"), hasPlayer = ArgParse.has(o, "player");
        if (hasEye == hasPlayer) throw new ToolArgError("give exactly one of eye:[x,y,z] or player:\"name\"");
        double[] eye = null;
        if (hasEye) {
            JsonElement e = o.get("eye");
            if (!e.isJsonArray() || e.getAsJsonArray().size() != 3) throw new ToolArgError("eye: must be [x,y,z]");
            JsonArray a = e.getAsJsonArray();
            eye = new double[3];
            for (int i = 0; i < 3; i++) eye[i] = number(a.get(i), "eye");
            if (Math.abs(eye[0]) >= 30_000_000 || Math.abs(eye[2]) >= 30_000_000) throw new ToolArgError("eye: must be inside horizontal +/-30000000");
        }
        String player = hasPlayer ? ArgParse.requireString(o, "player") : null;
        if (player != null && !player.matches("[A-Za-z0-9_]{1,16}")) throw new ToolArgError("player: must be a Minecraft player name");
        String world = ArgParse.optString(o, "world");
        if (player != null && world != null) throw new ToolArgError("world: not allowed with player (the player's own world is used)");
        Double yaw = ArgParse.has(o, "yaw") ? number(o.get("yaw"), "yaw") : null;
        Double pitch = ArgParse.has(o, "pitch") ? number(o.get("pitch"), "pitch") : null;
        if (yaw != null && (yaw < -360 || yaw > 360)) throw new ToolArgError("yaw: must be in [-360,360]");
        if (pitch != null && (pitch < -90 || pitch > 90)) throw new ToolArgError("pitch: must be in [-90,90]");
        if (hasEye && directionRequiredWithEye && (yaw == null || pitch == null)) {
            throw new ToolArgError("yaw and pitch are required with eye (Minecraft convention: yaw 0 south, 90 west, 180 north, -90 east; pitch -90 up, 90 down)");
        }
        return new EyeArgs(eye, player, world, yaw, pitch);
    }

    static double number(JsonElement e, String field) {
        if (e == null || !e.isJsonPrimitive() || !e.getAsJsonPrimitive().isNumber() || !Double.isFinite(e.getAsDouble())) {
            throw new ToolArgError(field + ": must contain finite numbers");
        }
        return e.getAsDouble();
    }

    /** Resolves to a concrete eye; a player lookup runs on the main thread. */
    CompletableFuture<ViewService.Eye> resolve(ViewService views, ConfigHolder config) {
        if (player != null) {
            return views.playerEye(player).thenApply(e -> new ViewService.Eye(e.world(), e.x(), e.y(), e.z(),
                    yaw != null ? yaw : e.yaw(), pitch != null ? pitch : e.pitch(), e.player()));
        }
        JsonObject w = new JsonObject();
        if (world != null) w.addProperty("world", world);
        var resolved = new RequestValidator(config.get()).resolveWorld(w);
        return CompletableFuture.completedFuture(new ViewService.Eye(resolved, eye[0], eye[1], eye[2],
                yaw != null ? yaw : 0, pitch != null ? pitch : 0, null));
    }

    static String describe(ViewService.Eye e) {
        return String.format(Locale.ROOT, "%s[%.2f,%.2f,%.2f] yaw %.1f pitch %.1f", e.player() != null ? e.player() + "'s eye " : "eye ",
                e.x(), e.y(), e.z(), e.yaw(), e.pitch());
    }

    static JsonArray json(ViewService.Eye e) {
        JsonArray a = new JsonArray();
        a.add(Math.round(e.x() * 100) / 100.0);
        a.add(Math.round(e.y() * 100) / 100.0);
        a.add(Math.round(e.z() * 100) / 100.0);
        return a;
    }
}
