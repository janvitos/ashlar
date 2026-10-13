// SPDX-License-Identifier: AGPL-3.0-or-later
package cc.wujm.ashlar.tool.mc;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
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
import cc.wujm.ashlar.tool.text.ToolText;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;

import static cc.wujm.ashlar.tool.mc.JsonUtil.intArray;
import static cc.wujm.ashlar.tool.mc.JsonUtil.toIntArrayAny;

/** Pure-Java port of {@code mcp-server/src/tools/mc-render.ts}. No text differences from the TS tool. */
public final class McRender implements Tool {

    private static final long MAX_VOLUME = 200_000;
    private static final long MAX_HEIGHTMAP_AREA = 200_000;
    private static final List<String> VIEWS = List.of("top", "north", "south", "east", "west", "slice", "heightmap", "isometric", "perspective", "first-person");
    private static final List<String> HEIGHTMAP_TYPES = List.of("SOLID", "SOLID_OR_LIQUID", "SOLID_OR_LIQUID_NO_LEAVES", "ANY");
    private static final List<String> SLICE_AXES = List.of("x", "y", "z");

    private final ToolSpec spec = ToolSpec.load("mc_render");
    private static final java.util.Set<String> FIRST_PERSON_KEYS =
            java.util.Set.of("view", "eye", "player", "world", "yaw", "pitch", "fov", "distance", "width", "height");

    private final RpcHandler renderHandler;
    private cc.wujm.ashlar.engine.ViewService views;
    private cc.wujm.ashlar.config.ConfigHolder config;

    public McRender(RpcHandler renderHandler) {
        this.renderHandler = renderHandler;
    }

    /** Enables {@code view:"first-person"} (Step 14). */
    public McRender withFirstPerson(cc.wujm.ashlar.engine.ViewService views, cc.wujm.ashlar.config.ConfigHolder config) {
        this.views = views;
        this.config = config;
        return this;
    }

    record FirstPersonArgs(EyeArgs eye, double fov, int distance, int width, int height) {
        static FirstPersonArgs parse(JsonObject o) {
            for (String k : o.keySet()) {
                if (!FIRST_PERSON_KEYS.contains(k)) throw new ToolArgError(k + " does not apply to view \"first-person\"");
            }
            EyeArgs eye = EyeArgs.parse(o, true);
            double fov = ArgParse.has(o, "fov") ? EyeArgs.number(o.get("fov"), "fov") : 70;
            int distance = ArgParse.has(o, "distance") ? BuildTransform.strictInt(o.get("distance"), "distance") : 128;
            int width = ArgParse.has(o, "width") ? BuildTransform.strictInt(o.get("width"), "width") : 960;
            int height = ArgParse.has(o, "height") ? BuildTransform.strictInt(o.get("height"), "height") : 540;
            try {
                new cc.wujm.ashlar.render.FirstPersonCamera(0, 64, 0, 0, 0, fov, distance, width, height);
            } catch (IllegalArgumentException e) {
                throw new ToolArgError(e.getMessage());
            }
            return new FirstPersonArgs(eye, fov, distance, width, height);
        }
    }

    private CompletableFuture<List<ContentBlock>> firstPerson(InvocationContext ctx, JsonObject args) {
        if (views == null) throw new ToolArgError("view \"first-person\" is not available");
        FirstPersonArgs a = FirstPersonArgs.parse(args);
        return a.eye().resolve(views, config).thenCompose(eye -> {
            cc.wujm.ashlar.render.FirstPersonCamera cam;
            try {
                cam = new cc.wujm.ashlar.render.FirstPersonCamera(eye.x(), eye.y(), eye.z(), eye.yaw(), eye.pitch(),
                        a.fov(), a.distance(), a.width(), a.height());
            } catch (IllegalArgumentException e) {
                throw new ToolArgError(e.getMessage());
            }
            return views.render(ctx, eye.world(), cam).thenApply(r -> {
                JsonObject summary = new JsonObject();
                summary.addProperty("view", "first-person");
                summary.addProperty("world", eye.world().getName());
                if (eye.player() != null) summary.addProperty("player", eye.player());
                summary.add("eye", EyeArgs.json(eye));
                summary.addProperty("yaw", Math.round(eye.yaw() * 10) / 10.0);
                summary.addProperty("pitch", Math.round(eye.pitch() * 10) / 10.0);
                summary.addProperty("fov", a.fov());
                summary.addProperty("distance", a.distance());
                summary.addProperty("width", a.width());
                summary.addProperty("height", a.height());
                JsonObject chunks = new JsonObject();
                chunks.addProperty("inView", r.snapshot().requested());
                chunks.addProperty("loaded", r.snapshot().loaded());
                cc.wujm.ashlar.engine.ViewService.Snapshot sn = r.snapshot();
                if (sn.loadedForView() > 0) chunks.addProperty("loadedForView", sn.loadedForView());
                if (sn.notGenerated() > 0) chunks.addProperty("notGenerated", sn.notGenerated());
                if (sn.overCap() > 0) chunks.addProperty("overLoadCap", sn.overCap());
                if (sn.failed() > 0) chunks.addProperty("failed", sn.failed());
                summary.add("chunks", chunks);
                summary.add("geometry", AngledRenderText.geometry(r.output().details(), cc.wujm.ashlar.engine.BukkitStateDefaults.CANON::canon));
                JsonArray legend = new JsonArray();
                for (var e : r.output().image().legend()) {
                    JsonObject l = new JsonObject();
                    l.addProperty("block", e.block());
                    l.addProperty("color", e.colorHex());
                    legend.add(l);
                }
                AngledRenderText.addLegend(summary, legend, false, cc.wujm.ashlar.engine.BukkitStateDefaults.CANON::canon);
                return List.of(ContentBlock.image(java.util.Base64.getEncoder().encodeToString(r.png()), "image/png"),
                        ContentBlock.text(summary.toString()));
            });
        });
    }

    @Override
    public ToolSpec spec() {
        return spec;
    }

    record SliceArg(String axis, int at) {
    }

    record Args(String world, int[] from, int[] to, String view, SliceArg slice, Integer scale, Integer grid,
                String heightmapType, Integer contour, JsonObject camera) {
        static Args parse(JsonObject o) {
            String world = ArgParse.optString(o, "world");
            String view = ArgParse.optEnum(o, "view", VIEWS, null);
            boolean angled = cc.wujm.ashlar.render.RenderCamera.angled(view == null ? "top" : view);
            cc.wujm.ashlar.render.RenderCamera.parse(o,view == null ? "top" : view);
            int[] from = angled ? BuildTransform.strictCoords(o,"from") : ArgParse.requireCoords2Or3(o, "from");
            int[] to = angled ? BuildTransform.strictCoords(o,"to") : ArgParse.requireCoords2Or3(o, "to");
            if (angled) BuildPreflight.checkHorizontal(cc.wujm.ashlar.engine.Region.of(from,to));
            SliceArg slice = null;
            if (ArgParse.has(o, "slice")) {
                JsonObject s = ArgParse.requireObject(o.get("slice"), "slice");
                String axis = ArgParse.requireEnum(s, "axis", SLICE_AXES);
                int at = ArgParse.requireInt(s, "at");
                slice = new SliceArg(axis, at);
            }
            Integer scale = angled && ArgParse.has(o,"scale") ? Integer.valueOf(BuildTransform.strictInt(o.get("scale"),"scale")) : ArgParse.optInt(o, "scale");
            int maxScale = angled ? cc.wujm.ashlar.render.ShapeRenderer.MAX_SCALE : 16;
            if (scale != null && (scale < 0 || scale > maxScale)) {
                throw new ToolArgError("scale: must be between 0 and " + maxScale + ", got " + scale);
            }
            Integer grid = angled && ArgParse.has(o,"grid") ? Integer.valueOf(BuildTransform.strictInt(o.get("grid"),"grid")) : ArgParse.optInt(o, "grid");
            if (angled && grid != null && grid != 0) throw new ToolArgError("angled views require grid:0");
            if (grid != null && (grid < 0 || grid > 64)) {
                throw new ToolArgError("grid: must be between 0 and 64, got " + grid);
            }
            String heightmapType = ArgParse.optEnum(o, "heightmapType", HEIGHTMAP_TYPES, null);
            Integer contour = ArgParse.optInt(o, "contour");
            if (contour != null && (contour < 0 || contour > 4096)) {
                throw new ToolArgError("contour: must be between 0 and 4096, got " + contour);
            }
            return new Args(world, from, to, view, slice, scale, grid, heightmapType, contour,
                    ArgParse.has(o,"camera") ? o.getAsJsonObject("camera").deepCopy() : null);
        }
    }

    /**
     * {@code zoom: {pos, radius}} (Step 20): replaces from/to with a cube of {@code radius} (default 4,
     * 1-16) around {@code pos} and defaults the view to isometric. Returns {@code o} unchanged without zoom.
     */
    static JsonObject applyZoom(JsonObject o) {
        if (!ArgParse.has(o, "zoom")) return o;
        if (ArgParse.has(o, "from") || ArgParse.has(o, "to")) throw new ToolArgError("zoom: give either zoom or from/to, not both");
        JsonObject z = ArgParse.requireObject(o.get("zoom"), "zoom");
        for (String k : z.keySet()) if (!k.equals("pos") && !k.equals("radius")) throw new ToolArgError("zoom." + k + ": zoom takes pos and radius");
        int[] pos = BuildTransform.strictCoords(z, "pos");
        int r = ArgParse.has(z, "radius") ? BuildTransform.strictInt(z.get("radius"), "zoom.radius") : 4;
        if (r < 1 || r > 16) throw new ToolArgError("zoom.radius: must be between 1 and 16, got " + r);
        JsonObject out = o.deepCopy();
        out.remove("zoom");
        if (!ArgParse.has(out, "view")) out.addProperty("view", "isometric");
        String view = out.get("view").isJsonPrimitive() ? out.get("view").getAsString() : "";
        if (!cc.wujm.ashlar.render.RenderCamera.angled(view)) throw new ToolArgError("zoom: works only with view \"isometric\" or \"perspective\"");
        out.add("from", intArray(pos[0] - r, pos[1] - r, pos[2] - r));
        out.add("to", intArray(pos[0] + r, pos[1] + r, pos[2] + r));
        if (!ArgParse.has(out, "scale")) out.addProperty("scale", r <= 4 ? cc.wujm.ashlar.render.ShapeRenderer.MAX_SCALE : 0);
        return out;
    }

    /** These four checks mirror mc-render.ts's plain body-level throws; extracted for unit testing. */
    static void checkShapeCompat(boolean footprintOnly, String resolvedView) {
        if (footprintOnly && !resolvedView.equals("top") && !resolvedView.equals("heightmap")) {
            throw new ToolArgError("mc_render view \"" + resolvedView + "\" needs [x, y, z] corners; only \"top\" and \"heightmap\" accept [x, z].");
        }
    }

    static void checkFromToLengthsMatch(boolean footprintOnly, int fromLen, int toLen) {
        if (footprintOnly && fromLen != toLen) {
            throw new ToolArgError("mc_render: from and to must both be [x, z] or both be [x, y, z].");
        }
    }

    static void checkArea(String resolvedView, long area) {
        checkArea(resolvedView, area, "Reduce the from/to range or split it into several calls.");
    }

    /** [x, z] corners. */
    static void checkArea(String resolvedView, int[] lo, int[] hi) {
        long area = (long) (hi[0] - lo[0] + 1) * (hi[1] - lo[1] + 1);
        if (area > MAX_HEIGHTMAP_AREA) checkArea(resolvedView, area, SplitBoxes.hint(lo, hi, MAX_HEIGHTMAP_AREA, "calls"));
    }

    private static void checkArea(String resolvedView, long area, String advice) {
        if (area > MAX_HEIGHTMAP_AREA) {
            throw new ToolArgError("mc_render " + resolvedView + " area " + area + " exceeds the 200,000-cell limit. " + advice);
        }
    }

    static void checkVolume(long volume) {
        checkVolume(volume, "Reduce the from/to range or split it into several calls.");
    }

    static void checkVolume(int[] lo, int[] hi) {
        checkVolume(lo, hi, MAX_VOLUME, "");
    }

    /** {@code max} is {@link #MAX_VOLUME}, or limits.max-render-volume for isometric/perspective views. */
    static void checkVolume(int[] lo, int[] hi, long max, String which) {
        long volume = cc.wujm.ashlar.engine.PlanGeometry.checkedVolume(new cc.wujm.ashlar.engine.Region(lo[0], lo[1], lo[2], hi[0], hi[1], hi[2]));
        if (volume > max) checkVolume(volume, max, which, SplitBoxes.hint(lo, hi, max, "calls"));
    }

    private static void checkVolume(long volume, String advice) {
        checkVolume(volume, MAX_VOLUME, "", advice);
    }

    private static void checkVolume(long volume, long max, String which, String advice) {
        if (volume > max) {
            throw new ToolArgError("mc_render region volume " + volume + " exceeds the " + String.format(java.util.Locale.ROOT, "%,d", max) + "-block limit" + which + ". " + advice);
        }
    }

    private long angledMaxVolume() {
        return config == null ? cc.wujm.ashlar.config.PluginConfig.LimitsConfig.DEFAULT_MAX_RENDER_VOLUME : config.get().limits().maxRenderVolume();
    }

    static void checkSliceRequired(String resolvedView, SliceArg slice) {
        if (resolvedView.equals("slice") && slice == null) {
            throw new ToolArgError("mc_render view \"slice\" requires the \"slice\" parameter: {\"axis\": \"x\"|\"y\"|\"z\", \"at\": <coordinate>}.");
        }
    }

    @Override
    public CompletableFuture<ToolResult> call(InvocationContext ctx, JsonObject args) {
        return ToolRunner.runContent("mc_render", () -> {
            if (args.has("view") && args.get("view").isJsonPrimitive() && "first-person".equals(args.get("view").getAsString())) {
                return firstPerson(ctx, args);
            }
            JsonObject zoomed = applyZoom(args);
            Args a = Args.parse(zoomed);
            String resolvedView = a.view() != null ? a.view() : "top";
            boolean footprintOnly = a.from().length == 2 || a.to().length == 2;
            checkShapeCompat(footprintOnly, resolvedView);
            checkFromToLengthsMatch(footprintOnly, a.from().length, a.to().length);
            int fx = a.from()[0], fz = a.from().length == 2 ? a.from()[1] : a.from()[2];
            int tx = a.to()[0], tz = a.to().length == 2 ? a.to()[1] : a.to()[2];
            int fy = a.from().length == 3 ? a.from()[1] : 0, ty = a.to().length == 3 ? a.to()[1] : 0;
            int x1 = Math.min(fx, tx), y1 = Math.min(fy, ty), z1 = Math.min(fz, tz);
            int x2 = Math.max(fx, tx), y2 = Math.max(fy, ty), z2 = Math.max(fz, tz);

            if (resolvedView.equals("heightmap") || resolvedView.equals("top")) {
                checkArea(resolvedView, new int[] {x1, z1}, new int[] {x2, z2});
            } else if (cc.wujm.ashlar.render.RenderCamera.angled(resolvedView)) {
                checkVolume(new int[] {x1, y1, z1}, new int[] {x2, y2, z2}, angledMaxVolume(), " for angled views");
            } else {
                checkVolume(new int[] {x1, y1, z1}, new int[] {x2, y2, z2});
            }
            checkSliceRequired(resolvedView, a.slice());

            JsonObject params = new JsonObject();
            if (a.world() != null) {
                params.addProperty("world", a.world());
            }
            boolean useFootprint = resolvedView.equals("heightmap") || footprintOnly;
            params.add("from", useFootprint ? intArray(x1, z1) : intArray(x1, y1, z1));
            params.add("to", useFootprint ? intArray(x2, z2) : intArray(x2, y2, z2));
            if (a.view() != null) params.addProperty("view", a.view());
            if (a.camera() != null) params.add("camera",a.camera());
            if (ArgParse.has(zoomed, "compareTo")) {
                if (!cc.wujm.ashlar.render.RenderCamera.angled(resolvedView)) throw new ToolArgError("compareTo: works only with view \"isometric\" or \"perspective\"");
                params.addProperty("compareTo", ArgParse.requireString(zoomed, "compareTo"));
            }
            if (a.slice() != null) {
                JsonObject sliceJson = new JsonObject();
                sliceJson.addProperty("axis", a.slice().axis());
                sliceJson.addProperty("at", a.slice().at());
                params.add("slice", sliceJson);
            }
            if (a.scale() != null) {
                params.addProperty("scale", a.scale());
            }
            if (a.grid() != null) {
                params.addProperty("grid", a.grid());
            }
            if (resolvedView.equals("heightmap")) {
                if (a.heightmapType() != null) {
                    params.addProperty("type", a.heightmapType());
                }
                if (a.contour() != null) {
                    params.addProperty("contour", a.contour());
                }
            }

            return renderHandler.handle(ctx, params).thenApply(el -> {
                JsonObject r = el.getAsJsonObject();
                JsonObject bounds = r.getAsJsonObject("bounds");
                int[] boundsFrom = toIntArrayAny(bounds.getAsJsonArray("from"));
                int[] boundsTo = toIntArrayAny(bounds.getAsJsonArray("to"));
                JsonObject axes = r.getAsJsonObject("axes");
                JsonArray topLeftArr = r.getAsJsonArray("topLeft");
                int width = r.get("width").getAsInt();
                int height = r.get("height").getAsInt();
                int scaleOut = r.get("scale").getAsInt();
                int grid = r.get("grid").getAsInt();
                String view = r.get("view").getAsString();

                String text;
                if (cc.wujm.ashlar.render.RenderCamera.angled(view)) {
                    text = AngledRenderText.text(r, cc.wujm.ashlar.engine.BukkitStateDefaults.CANON::canon);
                } else if (view.equals("heightmap")) {
                    HeightmapText.HeightmapRenderFields fields = HeightmapJson.renderFields(r);
                    text = ToolText.renderText(view, boundsFrom, boundsTo, width, height, scaleOut,
                            axes.get("right").getAsString(), axes.get("down").getAsString(),
                            topLeftArr.get(0).getAsInt(), topLeftArr.get(1).getAsInt(), grid, fields, null);
                } else {
                    List<ToolText.ColorLegendEntry> legend = new ArrayList<>();
                    for (JsonElement le : r.getAsJsonArray("legend")) {
                        JsonObject l = le.getAsJsonObject();
                        legend.add(new ToolText.ColorLegendEntry(l.get("color").getAsString(), AngledRenderText.state(l.get("block").getAsString(), cc.wujm.ashlar.engine.BukkitStateDefaults.CANON::canon), l.get("pixels").getAsInt()));
                    }
                    text = ToolText.renderText(view, boundsFrom, boundsTo, width, height, scaleOut,
                            axes.get("right").getAsString(), axes.get("down").getAsString(),
                            topLeftArr.get(0).getAsInt(), topLeftArr.get(1).getAsInt(), grid, null, legend);
                }
                return List.of(ContentBlock.image(r.get("png").getAsString(), "image/png"), ContentBlock.text(text));
            });
        });
    }
}
