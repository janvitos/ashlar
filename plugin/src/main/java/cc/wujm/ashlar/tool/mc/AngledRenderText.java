// SPDX-License-Identifier: AGPL-3.0-or-later
package cc.wujm.ashlar.tool.mc;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;

import java.util.Map;
import java.util.Set;
import java.util.function.UnaryOperator;

/**
 * Compact reply text for an isometric/perspective {@code mc_render}: a colour legend of the most
 * visible blocks plus the render geometry without fixed boilerplate, unit vectors, internal work
 * estimates, or zero/false/empty fields. Block states go through {@code canon}. Pure Java.
 */
final class AngledRenderText {
    private AngledRenderText() {}

    static final int LEGEND_LIMIT = 20;
    static final int SAMPLE_LIMIT = 10;
    private static final Set<String> DROPPED = Set.of("projection", "screenRight", "screenUp", "requestedScale",
            "estimatedIntersectionWork", "transparentLayerLimit", "assumptions");

    /** {@code render} is the plugin's render result ({@code view, bounds, width, height, scale, legend, geometry}). */
    static String text(JsonObject render, UnaryOperator<String> canon) {
        JsonObject out = new JsonObject();
        for (String k : new String[]{"view", "bounds", "width", "height", "scale"}) out.add(k, render.get(k));
        JsonObject legend = new JsonObject();
        JsonArray entries = render.has("legend") ? render.getAsJsonArray("legend") : new JsonArray();
        int shown = 0;
        for (JsonElement el : entries) {
            if (shown == LEGEND_LIMIT) break;
            JsonObject e = el.getAsJsonObject();
            String block = state(e.get("block").getAsString(), canon);
            if (!legend.has(block)) legend.addProperty(block, e.get("color").getAsString());
            shown++;
        }
        out.add("legend", legend);
        int more = entries.size() - shown;
        if (render.has("legendTruncated") && render.get("legendTruncated").getAsBoolean()) more = Math.max(more, 1);
        if (more > 0) out.addProperty("legendMore", more);
        if (render.has("geometry")) out.add("geometry", geometry(render.getAsJsonObject("geometry"), canon));
        return out.toString();
    }

    static JsonObject geometry(JsonObject g, UnaryOperator<String> canon) {
        JsonObject out = new JsonObject();
        for (Map.Entry<String, JsonElement> e : g.entrySet()) {
            String k = e.getKey();
            JsonElement v = e.getValue();
            if (DROPPED.contains(k) || k.endsWith("Truncated") || empty(v)) continue;
            if (k.equals("camera")) {
                JsonObject cam = new JsonObject();
                for (var c : v.getAsJsonObject().entrySet()) cam.addProperty(c.getKey(), Math.round(c.getValue().getAsDouble() * 100) / 100.0);
                out.add(k, cam);
            } else if (k.equals("diff")) {
                JsonObject d = v.getAsJsonObject().deepCopy();
                d.remove("tints");
                out.add(k, d);
            } else if (k.endsWith("Samples") && v.isJsonArray()) {
                JsonArray a = new JsonArray();
                for (JsonElement s : v.getAsJsonArray()) {
                    if (a.size() == SAMPLE_LIMIT) break;
                    a.add(s.isJsonPrimitive() ? new JsonPrimitive(state(s.getAsString(), canon)) : s);
                }
                out.add(k, a);
            } else {
                out.add(k, v);
            }
        }
        return out;
    }

    /** Legend placeholders ({@code entity:painting}, {@code diff:removed}) are not block states. */
    private static String state(String s, UnaryOperator<String> canon) {
        if (s.startsWith("entity:") || s.startsWith("diff:")) return s;
        try {
            return canon.apply(s);
        } catch (IllegalArgumentException e) {
            return s;
        }
    }

    private static boolean empty(JsonElement v) {
        if (v == null || v.isJsonNull()) return true;
        if (v.isJsonArray()) return v.getAsJsonArray().isEmpty();
        if (!v.isJsonPrimitive()) return false;
        JsonPrimitive p = v.getAsJsonPrimitive();
        if (p.isBoolean()) return !p.getAsBoolean();
        return p.isNumber() && p.getAsDouble() == 0;
    }
}
