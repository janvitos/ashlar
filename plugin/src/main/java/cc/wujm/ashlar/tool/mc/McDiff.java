// SPDX-License-Identifier: AGPL-3.0-or-later
package cc.wujm.ashlar.tool.mc;

import cc.wujm.ashlar.rpc.InvocationContext;
import cc.wujm.ashlar.tool.ArgParse;
import cc.wujm.ashlar.tool.Tool;
import cc.wujm.ashlar.tool.ToolArgError;
import cc.wujm.ashlar.tool.ToolResult;
import cc.wujm.ashlar.tool.ToolRunner;
import cc.wujm.ashlar.tool.ToolSpec;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.regex.Pattern;

/** Read-only comparison of a live box against a snapshot, expected cells, an expected file or a blueprint. */
public final class McDiff implements Tool {

    static final int MAX_INLINE_CELLS = 50_000;
    static final List<String> ANOMALIES = List.of("floating", "stacked", "unsupported", "enclosedAir");
    static final Pattern FILE_NAME = Pattern.compile("[a-z0-9][a-z0-9_-]{0,63}");

    private final ToolSpec spec = ToolSpec.load("mc_diff");
    private final DiffService service;

    public McDiff(DiffService service) {
        this.service = service;
    }

    @Override
    public ToolSpec spec() {
        return spec;
    }

    record Cell(int x, int y, int z, String state) {
    }

    /** Exactly one of snapshot / expected / expectedFile / blueprint is set. */
    record Against(String snapshot, List<Cell> expected, String expectedFile, JsonObject blueprint, JsonObject transform) {
        String describe() {
            if (snapshot != null) return "snapshot " + snapshot;
            if (expected != null) return expected.size() + " expected cells";
            if (expectedFile != null) return "expected file " + expectedFile;
            return "blueprint " + blueprint.get("id").getAsString();
        }
    }

    record Args(String world, int[] from, int[] to, Against against, boolean declaredOnly, String baselineSnapshot,
            List<String> ignore, boolean materialOnly, Set<String> anomalies, int samples, String format) {

        static Args parse(JsonObject o) {
            String world = ArgParse.optString(o, "world");
            int[] from = ArgParse.requireCoords3(o, "from");
            int[] to = ArgParse.requireCoords3(o, "to");
            if (!ArgParse.has(o, "against")) throw new ToolArgError("against is required: {snapshot}, {expected}, {expectedFile} or {blueprint}");
            Against against = parseAgainst(ArgParse.requireObject(o.get("against"), "against"));
            boolean declaredOnly = ArgParse.optEnum(o, "scope", List.of("declared", "box"), "box").equals("declared");
            String baseline = ArgParse.optString(o, "baselineSnapshot");
            if (baseline != null && against.snapshot() != null)
                throw new ToolArgError("baselineSnapshot only applies to expected/expectedFile/blueprint references; a snapshot already covers the box");
            if (baseline != null && declaredOnly)
                throw new ToolArgError("baselineSnapshot covers undeclared cells, which scope \"declared\" skips; use scope \"box\"");
            List<String> ignore = new ArrayList<>();
            if (ArgParse.has(o, "ignore")) {
                JsonArray arr = ArgParse.requireArray(o, "ignore");
                for (JsonElement el : arr) {
                    if (!el.isJsonPrimitive() || !el.getAsJsonPrimitive().isString()) throw new ToolArgError("ignore entries must be strings");
                    ignore.add(el.getAsString());
                }
                DiffIgnore.parse(ignore);
            }
            boolean materialOnly = ArgParse.optEnum(o, "compare", List.of("exact", "material"), "exact").equals("material");
            Set<String> anomalies = new LinkedHashSet<>();
            if (ArgParse.has(o, "anomalies")) {
                for (JsonElement el : ArgParse.requireArray(o, "anomalies")) {
                    String a = el.isJsonPrimitive() && el.getAsJsonPrimitive().isString() ? el.getAsString() : "";
                    if (!ANOMALIES.contains(a)) throw new ToolArgError("anomalies entries must be one of " + ANOMALIES);
                    if (!anomalies.add(a)) throw new ToolArgError("anomalies entries must be unique");
                }
            }
            int samples = McVerify.bounded(o, "samples", 50, 1, 1000);
            String format = ArgParse.optEnum(o, "format", List.of("summary", "cells", "columns"), "summary");
            return new Args(world, from, to, against, declaredOnly, baseline, ignore, materialOnly, anomalies, samples, format);
        }

        private static Against parseAgainst(JsonObject a) {
            for (String k : a.keySet()) {
                if (!List.of("snapshot", "expected", "expectedFile", "blueprint", "transform").contains(k))
                    throw new ToolArgError("against." + k + " is not supported");
            }
            int kinds = 0;
            for (String k : List.of("snapshot", "expected", "expectedFile", "blueprint")) if (ArgParse.has(a, k)) kinds++;
            if (kinds != 1) throw new ToolArgError("against needs exactly one of snapshot, expected, expectedFile or blueprint");
            JsonObject transform = null;
            if (ArgParse.has(a, "transform")) {
                if (!ArgParse.has(a, "blueprint")) throw new ToolArgError("against.transform only applies to a blueprint reference");
                transform = ArgParse.requireObject(a.get("transform"), "against.transform");
            }
            if (ArgParse.has(a, "snapshot")) return new Against(ArgParse.requireString(a, "snapshot"), null, null, null, null);
            if (ArgParse.has(a, "expectedFile")) {
                String name = ArgParse.requireString(a, "expectedFile");
                if (!FILE_NAME.matcher(name).matches())
                    throw new ToolArgError("expectedFile must be a plain name matching [a-z0-9][a-z0-9_-]{0,63} (no path or extension)");
                return new Against(null, null, name, null, null);
            }
            if (ArgParse.has(a, "blueprint")) {
                JsonObject b = ArgParse.requireObject(a.get("blueprint"), "against.blueprint");
                ArgParse.requireString(b, "id");
                return new Against(null, null, null, b, transform);
            }
            JsonArray arr = ArgParse.requireArray(a, "expected");
            if (arr.isEmpty()) throw new ToolArgError("against.expected must not be empty");
            if (arr.size() > MAX_INLINE_CELLS)
                throw new ToolArgError("against.expected accepts at most " + MAX_INLINE_CELLS + " cells; use expectedFile for more");
            return new Against(null, cells(arr, "against.expected"), null, null, null);
        }
    }

    /** Parses {@code [[x,y,z,"state"],...]}. */
    static List<Cell> cells(JsonArray arr, String what) {
        List<Cell> cells = new ArrayList<>(arr.size());
        for (int i = 0; i < arr.size(); i++) {
            JsonElement el = arr.get(i);
            if (!el.isJsonArray() || el.getAsJsonArray().size() != 4)
                throw new ToolArgError(what + "[" + i + "] must be [x,y,z,\"block_state\"]");
            JsonArray c = el.getAsJsonArray();
            int[] xyz = new int[3];
            for (int k = 0; k < 3; k++) xyz[k] = BuildTransform.strictInt(c.get(k), what + "[" + i + "][" + k + "]");
            JsonElement s = c.get(3);
            if (!s.isJsonPrimitive() || !s.getAsJsonPrimitive().isString() || s.getAsString().isBlank())
                throw new ToolArgError(what + "[" + i + "][3] must be a block state string");
            cells.add(new Cell(xyz[0], xyz[1], xyz[2], s.getAsString()));
        }
        return cells;
    }

    @Override
    public CompletableFuture<ToolResult> call(InvocationContext ctx, JsonObject args) {
        return ToolRunner.runText("mc_diff", () -> service.diff(ctx, Args.parse(args)));
    }
}
