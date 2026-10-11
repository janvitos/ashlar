// SPDX-License-Identifier: AGPL-3.0-or-later
package cc.wujm.ashlar.tool.mc;

import com.google.gson.JsonObject;
import cc.wujm.ashlar.rpc.InvocationContext;
import cc.wujm.ashlar.rpc.RpcHandler;
import cc.wujm.ashlar.tool.ArgParse;
import cc.wujm.ashlar.tool.ContentBlock;
import cc.wujm.ashlar.tool.Tool;
import cc.wujm.ashlar.tool.ToolArgError;
import cc.wujm.ashlar.tool.ToolResult;
import cc.wujm.ashlar.tool.ToolRunner;
import cc.wujm.ashlar.tool.ToolSpec;
import cc.wujm.ashlar.tool.text.HeightmapText;
import cc.wujm.ashlar.tool.text.ReliefText;
import cc.wujm.ashlar.tool.text.ToolText;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;

import static cc.wujm.ashlar.tool.mc.JsonUtil.intArray;

/** Pure-Java port of {@code mcp-server/src/tools/mc-survey.ts}. No text differences from the TS tool. */
public final class McSurvey implements Tool {

    private static final long MAX_AREA = 200_000;
    private static final List<String> HEIGHTMAP_TYPES = List.of("SOLID", "SOLID_OR_LIQUID", "SOLID_OR_LIQUID_NO_LEAVES", "ANY",
            "TOP_SOLID_IGNORE_DECOR");
    /** {@code walkHeight} prints one number per column, so it is capped well below the survey area. */
    static final long MAX_WALK_AREA = 4096;
    private static final List<String> FORMATS = List.of("image", "text");

    private final ToolSpec spec = ToolSpec.load("mc_survey");
    private final RpcHandler heightmapHandler;
    private final RpcHandler renderHandler;

    public McSurvey(RpcHandler heightmapHandler, RpcHandler renderHandler) {
        this.heightmapHandler = heightmapHandler;
        this.renderHandler = renderHandler;
    }

    @Override
    public ToolSpec spec() {
        return spec;
    }

    /** Client-side pre-check mirroring mc-survey.ts's {@code MAX_AREA} guard, exposed for unit testing. */
    static void checkArea(long area) {
        if (area > MAX_AREA) {
            throw new ToolArgError("mc_survey area " + area + " exceeds the 200,000-cell limit. Reduce the from/to range or split it into several calls.");
        }
    }

    record Args(String world, int[] from, int[] to, String type, String format, Boolean matrix, Integer snowLayers, boolean walkHeight) {
        static Args parse(JsonObject o) {
            String world = ArgParse.optString(o, "world");
            int[] from = ArgParse.requireCoords2(o, "from");
            int[] to = ArgParse.requireCoords2(o, "to");
            String type = ArgParse.optEnum(o, "type", HEIGHTMAP_TYPES, null);
            String format = ArgParse.optEnum(o, "format", FORMATS, null);
            Boolean matrix = ArgParse.optBooleanNullable(o, "matrix");
            Integer snowLayers = null;
            if (ArgParse.has(o, "snowLayers")) {
                snowLayers = ArgParse.requireInt(o, "snowLayers");
                if (snowLayers < 0 || snowLayers > 7) throw new ToolArgError("snowLayers: must be between 0 and 7, got " + snowLayers);
                if (!"TOP_SOLID_IGNORE_DECOR".equals(type)) throw new ToolArgError("snowLayers: only with type \"TOP_SOLID_IGNORE_DECOR\"");
            }
            return new Args(world, from, to, type, format, matrix, snowLayers, ArgParse.optBoolean(o, "walkHeight", false));
        }
    }

    @Override
    public CompletableFuture<ToolResult> call(InvocationContext ctx, JsonObject args) {
        return ToolRunner.runContent("mc_survey", () -> {
            Args a = Args.parse(args);
            int x1 = Math.min(a.from()[0], a.to()[0]);
            int x2 = Math.max(a.from()[0], a.to()[0]);
            int z1 = Math.min(a.from()[1], a.to()[1]);
            int z2 = Math.max(a.from()[1], a.to()[1]);
            long area = (long) (x2 - x1 + 1) * (z2 - z1 + 1);
            checkArea(area);
            if (a.walkHeight() && area > MAX_WALK_AREA) {
                throw new ToolArgError("walkHeight: at most " + MAX_WALK_AREA + " columns (64x64), got " + area + ". Survey the whole area without it, then ask for walk heights over the part you need.");
            }
            String requestedType = a.type() != null ? a.type() : "SOLID_OR_LIQUID_NO_LEAVES";
            String format = a.format() != null ? a.format() : "image";

            if (format.equals("text")) {
                return heightmapHandler.handle(ctx, heightmapParams(a, x1, z1, x2, z2, requestedType, a.walkHeight())).thenApply(el -> {
                    JsonObject r = el.getAsJsonObject();
                    ReliefText.ReliefInput input = new ReliefText.ReliefInput(
                            new int[]{x1, z1}, new int[]{x2, z2},
                            HeightmapJson.toIntGrid(r.getAsJsonArray("heights")),
                            HeightmapJson.toIntGrid(r.getAsJsonArray("classes")),
                            HeightmapJson.surfaceMap(r.getAsJsonObject("surface")),
                            a.matrix());
                    String text = ReliefText.renderRelief(input);
                    if (a.walkHeight()) text += "\n\n" + walkText(r.getAsJsonArray("walk"), x1, z1);
                    return List.<ContentBlock>of(ContentBlock.text(text));
                });
            }

            JsonObject renderParams = new JsonObject();
            if (a.world() != null) {
                renderParams.addProperty("world", a.world());
            }
            renderParams.add("from", intArray(x1, z1));
            renderParams.add("to", intArray(x2, z2));
            renderParams.addProperty("view", "heightmap");
            renderParams.addProperty("type", requestedType);
            if (a.snowLayers() != null) renderParams.addProperty("snowLayers", a.snowLayers());

            return renderHandler.handle(ctx, renderParams).thenCompose(el -> {
                JsonObject r = el.getAsJsonObject();
                HeightmapText.HeightmapRenderFields fields = HeightmapJson.renderFields(r);
                JsonObject axes = r.getAsJsonObject("axes");
                int grid = r.get("grid").getAsInt();
                String text = ToolText.surveyImageText(fields, axes.get("right").getAsString(), axes.get("down").getAsString(), x1, z1, grid);
                List<ContentBlock> content = new ArrayList<>();
                content.add(ContentBlock.image(r.get("png").getAsString(), "image/png"));
                content.add(ContentBlock.text(text));

                if (Boolean.TRUE.equals(a.matrix()) || a.walkHeight()) {
                    return heightmapHandler.handle(ctx, heightmapParams(a, x1, z1, x2, z2, requestedType, a.walkHeight())).thenApply(hmEl -> {
                        JsonObject hm = hmEl.getAsJsonObject();
                        if (Boolean.TRUE.equals(a.matrix())) {
                            int[][] heights = HeightmapJson.toIntGrid(hm.getAsJsonArray("heights"));
                            content.add(ContentBlock.text(ToolText.surveyMatrixText(heights, x1, z1)));
                        }
                        if (a.walkHeight()) content.add(ContentBlock.text(walkText(hm.getAsJsonArray("walk"), x1, z1)));
                        return content;
                    });
                }
                return CompletableFuture.completedFuture(content);
            });
        });
    }

    /** {@code walkHeight} matrix: feet y per column (ground block y + 1, plus slab, snow, carpet and fence heights). */
    static String walkText(com.google.gson.JsonArray walk, int x1, int z1) {
        StringBuilder sb = new StringBuilder("Walk heights (feet y of a player standing there; z rows top-to-bottom, x columns left-to-right, top-left = x="
                + x1 + " z=" + z1 + "):");
        for (int zi = 0; zi < walk.size(); zi++) {
            sb.append("\nz=").append(z1 + zi).append(':');
            for (com.google.gson.JsonElement v : walk.get(zi).getAsJsonArray()) sb.append(' ').append(walkNumber(v.getAsDouble()));
        }
        return sb.toString();
    }

    /** Shortest exact form: 65, 64.5, 65.125, 64.063. */
    static String walkNumber(double v) {
        if (v == Math.rint(v)) return Long.toString((long) v);
        String s = String.format(java.util.Locale.ROOT, "%.3f", v);
        while (s.endsWith("0")) s = s.substring(0, s.length() - 1);
        return s;
    }

    private static JsonObject heightmapParams(Args a, int x1, int z1, int x2, int z2, String type, boolean walk) {
        JsonObject params = new JsonObject();
        if (a.world() != null) {
            params.addProperty("world", a.world());
        }
        if (a.snowLayers() != null) params.addProperty("snowLayers", a.snowLayers());
        if (walk) params.addProperty("walk", true);
        params.add("from", intArray(x1, z1));
        params.add("to", intArray(x2, z2));
        params.addProperty("type", type);
        return params;
    }
}
