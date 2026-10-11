// SPDX-License-Identifier: AGPL-3.0-or-later
package cc.wujm.ashlar.tool.mc;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.function.UnaryOperator;

import static cc.wujm.ashlar.tool.mc.AngledRenderText.addLegend;
import static cc.wujm.ashlar.tool.mc.AngledRenderText.state;

/**
 * Compact reply text for {@code mc_plan} and {@code mc_build dryRun}. The full planning report stays
 * internal (preflight, verification); the agent gets the decisive counts, collisions grouped by
 * transition, canonical block states and a short preview legend, without fixed boilerplate, derived
 * fields or zero/false/empty values (the main counts, valid and strictSitePass are always shown). Fields added by other code (protection, entities) pass through.
 * Pure Java.
 */
final class PlanReportText {
    private PlanReportText() {}

    static final int GROUP_LIMIT = 10;
    static final int ISSUE_LIMIT = 20;
    static final int OVERLAP_LIMIT = 10;
    /** Internal or derivable from the rest. */
    private static final Set<String> DROPPED = Set.of("requestedVolume", "acceptedVisits", "dimensions", "elapsedMs", "assumptions",
            "collisionSamplesTruncated", "overlapSamplesTruncated", "pairIssuesTruncated");
    /** Planner fields shown only when non-zero/true/non-empty. */
    private static final Set<String> SPARSE = Set.of("skippedVisits", "overlappingCells", "signEdits", "supportWarningsTruncated",
            "neighbourChecksTruncated");

    static String text(JsonObject report, UnaryOperator<String> canon) {
        JsonObject out = new JsonObject();
        for (Map.Entry<String, JsonElement> e : report.entrySet()) {
            String k = e.getKey();
            JsonElement v = e.getValue();
            if (DROPPED.contains(k)) continue;
            if (SPARSE.contains(k) && empty(v)) continue;
            switch (k) {
                case "collisionSamples" -> {
                    JsonArray groups = collisions(v.getAsJsonArray(), canon);
                    if (!groups.isEmpty()) out.add("collisions", groups);
                }
                case "overlapSamples" -> {
                    if (!v.getAsJsonArray().isEmpty()) out.add("overlapSamples", head(v.getAsJsonArray(), OVERLAP_LIMIT));
                }
                case "issues" -> issues(out, v.getAsJsonArray(), canon);
                case "finalPlannedMaterials" -> {
                    JsonObject m = new JsonObject();
                    for (var x : v.getAsJsonObject().entrySet()) m.add(state(x.getKey(), canon), x.getValue());
                    out.add("materials", m);
                }
                case "preview" -> out.add("preview", preview(v.getAsJsonObject(), canon));
                default -> out.add(k, v);
            }
        }
        return out.toString();
    }

    /**
     * Collision samples grouped by {@code existing -> target}, largest first: {@code {change, n, at}}
     * where {@code n} counts samples (not all collisions; see existingNonAirChanged) and {@code at} is
     * the first sampled position.
     */
    static JsonArray collisions(JsonArray samples, UnaryOperator<String> canon) {
        Map<String, int[]> count = new LinkedHashMap<>();
        Map<String, JsonElement> first = new LinkedHashMap<>();
        for (JsonElement el : samples) {
            JsonObject s = el.getAsJsonObject();
            String change = state(s.get("existing").getAsString(), canon) + " -> " + state(s.get("target").getAsString(), canon);
            count.computeIfAbsent(change, c -> new int[1])[0]++;
            first.putIfAbsent(change, s.get("pos"));
        }
        JsonArray out = new JsonArray();
        count.entrySet().stream().sorted((a, b) -> b.getValue()[0] - a.getValue()[0]).limit(GROUP_LIMIT).forEach(e -> {
            JsonObject g = new JsonObject();
            g.addProperty("change", e.getKey());
            g.addProperty("n", e.getValue()[0]);
            g.add("at", first.get(e.getKey()));
            out.add(g);
        });
        return out;
    }

    private static void issues(JsonObject out, JsonArray issues, UnaryOperator<String> canon) {
        if (issues.isEmpty()) return;
        JsonArray a = new JsonArray();
        for (JsonElement el : issues) {
            if (a.size() == ISSUE_LIMIT) break;
            JsonObject i = el.getAsJsonObject().deepCopy();
            i.remove("severity");
            if (i.has("block")) i.addProperty("block", state(i.get("block").getAsString(), canon));
            a.add(i);
        }
        out.add("issues", a);
        if (issues.size() > a.size()) out.addProperty("issuesMore", issues.size() - a.size());
    }

    static JsonObject preview(JsonObject p, UnaryOperator<String> canon) {
        JsonObject out = new JsonObject();
        for (var e : p.entrySet()) {
            String k = e.getKey();
            if (k.equals("legend") || k.equals("legendTruncated")) continue;
            if (k.equals("geometry")) out.add(k, AngledRenderText.geometry(e.getValue().getAsJsonObject(), canon));
            else if (!(k.equals("grid") && e.getValue().getAsInt() == 0)) out.add(k, e.getValue());
        }
        if (p.has("legend")) addLegend(out, p.getAsJsonArray("legend"), p.has("legendTruncated") && p.get("legendTruncated").getAsBoolean(), canon);
        return out;
    }

    private static JsonArray head(JsonArray a, int n) {
        JsonArray out = new JsonArray();
        for (int i = 0; i < Math.min(n, a.size()); i++) out.add(a.get(i));
        return out;
    }

    private static boolean empty(JsonElement v) {
        if (v == null || v.isJsonNull()) return true;
        if (v.isJsonArray()) return v.getAsJsonArray().isEmpty();
        if (!v.isJsonPrimitive()) return false;
        if (v.getAsJsonPrimitive().isBoolean()) return !v.getAsBoolean();
        return v.getAsJsonPrimitive().isNumber() && v.getAsDouble() == 0;
    }
}
