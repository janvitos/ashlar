// SPDX-License-Identifier: AGPL-3.0-or-later
package cc.wujm.ashlar.tool.mc;

import cc.wujm.ashlar.engine.RenderService;
import cc.wujm.ashlar.render.ImageRenderer;
import cc.wujm.ashlar.rpc.InvocationContext;
import cc.wujm.ashlar.tool.*;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

import java.util.Base64;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;

/** Readonly plan validation, site diagnostics and bounded map-color previews. */
public final class McPlan implements Tool {
    private final ToolSpec spec = ToolSpec.load("mc_plan");
    private final McBuild build;
    private final BuildPreflight preflight;
    private final Executor images;

    public McPlan(McBuild build, BuildPreflight preflight, Executor images) {
        this.build = build; this.preflight = preflight; this.images = images;
        // Keep the nested build schema identical to mc_build without maintaining a second copy.
        spec.inputSchema().getAsJsonObject("properties").add("build",build.spec().inputSchema().deepCopy());
    }
    @Override public ToolSpec spec() { return spec; }
    @Override public CompletableFuture<ToolResult> call(InvocationContext ctx, JsonObject args) {
        return ToolRunner.runContent("mc_plan",() -> {
            JsonObject request = ArgParse.requireObject(args.get("build"),"build");
            boolean image = ArgParse.optBoolean(args,"image",true);
            JsonObject preview = image ? (ArgParse.has(args,"preview") ? ArgParse.requireObject(args.get("preview"),"preview") : new JsonObject()) : null;
            if (preview != null) {
                for (String field : List.of("scale", "grid")) if (ArgParse.has(preview, field))
                    BuildTransform.strictInt(preview.get(field), "preview." + field);
                if (ArgParse.has(preview, "slice")) {
                    JsonObject slice = ArgParse.requireObject(preview.get("slice"), "preview.slice");
                    if (ArgParse.has(slice, "at")) BuildTransform.strictInt(slice.get("at"), "preview.slice.at");
                }
            }
            return build.prepare(request,true).thenCompose(preflight::validate)
                    .thenCompose(v -> preflight.analyze(ctx,v,preview)).thenApplyAsync(this::content,images);
        });
    }
    private List<ContentBlock> content(BuildPreflight.Result result) {
        JsonObject report = result.report();
        if (result.preview() == null) return List.of(ContentBlock.text(report.toString()));
        var rp = result.preview(); int scale = rp.scale();
        try {
            ImageRenderer.Output out; byte[] png;
            while (true) {
                out = ImageRenderer.render(result.task().previewData(),result.task().paletteArgb(),rp.view(),rp.sliceAxis(),rp.sliceAt(),scale,rp.grid());
                png = RenderService.encodePng(out.pixels(),out.width(),out.height());
                if (png.length <= 3*1024*1024 || out.scale() <= 1) break;
                scale = out.scale()/2;
            }
            if (png.length > 3*1024*1024) throw new ToolArgError("preview PNG exceeds 3 MiB even at scale=1");
            JsonObject info = new JsonObject(); info.addProperty("view",rp.view());
            info.addProperty("width",out.width()); info.addProperty("height",out.height()); info.addProperty("scale",out.scale());
            info.addProperty("axisRight",out.axisRight()); info.addProperty("axisDown",out.axisDown());
            info.addProperty("topLeftA",out.topLeftA()); info.addProperty("topLeftB",out.topLeftB());
            info.addProperty("grid",out.grid()); JsonArray legend = new JsonArray();
            for (var entry:out.legend().stream().limit(50).toList()) {
                JsonObject row = new JsonObject(); row.addProperty("block",entry.block()); row.addProperty("color",entry.colorHex()); row.addProperty("pixels",entry.pixels()); legend.add(row);
            }
            info.add("legend",legend); info.addProperty("legendTruncated",out.legend().size() > 50); report.add("preview",info);
            return List.of(ContentBlock.text(report.toString()),ContentBlock.image(Base64.getEncoder().encodeToString(png),"image/png"));
        } catch (java.io.IOException e) { throw new ToolArgError("preview encoding failed: " + e.getMessage()); }
    }
}
