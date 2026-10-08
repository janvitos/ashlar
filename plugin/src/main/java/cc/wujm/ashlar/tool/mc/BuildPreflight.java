// SPDX-License-Identifier: AGPL-3.0-or-later
package cc.wujm.ashlar.tool.mc;

import cc.wujm.ashlar.config.ConfigHolder;
import cc.wujm.ashlar.config.PluginConfig;
import cc.wujm.ashlar.engine.*;
import cc.wujm.ashlar.rpc.InvocationContext;
import cc.wujm.ashlar.render.RenderCamera;
import cc.wujm.ashlar.rpc.MainThread;
import cc.wujm.ashlar.tool.ToolArgError;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import org.bukkit.World;

import java.util.List;
import java.util.concurrent.CompletableFuture;

/** Shared complete request validation and readonly site analysis. No world-writing handlers. */
public final class BuildPreflight {
    private final ConfigHolder config;
    private final TickBudgetExecutor executor;
    record Validated(World world, Region bounds, List<FillOp> fills, List<SparseOp> blocks,
            int minY, int maxY, long volume, PluginConfig settings) {}
    record Result(JsonObject report, PlanTask task, RequestValidator.RenderParams preview) {}

    public BuildPreflight(ConfigHolder config, TickBudgetExecutor executor) {
        this.config = config; this.executor = executor;
    }

    CompletableFuture<Validated> validate(McBuild.Args args) {
        PluginConfig settings = config.get();
        RequestValidator validator = new RequestValidator(settings);
        JsonObject options = new JsonObject();
        if (args.world() != null) options.addProperty("world",args.world());
        if (args.liquids() != null) options.addProperty("liquids",args.liquids());
        if (args.connect() != null) options.addProperty("connect",args.connect());
        World world = validator.resolveWorld(options);
        int[][] box = McBuild.boundingBox(args.fills(),args.blocks(),args.text());
        Region bounds = Region.of(box[0],box[1]);
        checkHorizontal(bounds);
        // The executor acquires the entire envelope's tickets, not just the union of operation chunks.
        validator.checkChunkCount(bounds);
        JsonArray fillJson = new JsonArray();
        for (var f:args.fills()) fillJson.add(McBuild.fillOpJson(f));
        for (var t:args.text()) {
            if (t.background() != null) fillJson.add(fill(t.expanded().bboxMin(),t.expanded().bboxMax(),t.background()));
            for (var run:t.expanded().inkRuns()) fillJson.add(fill(run.from(),run.to(),t.block()));
        }
        JsonArray sparseJson = new JsonArray();
        for (var b:args.blocks()) sparseJson.add(McBuild.sparseOpJson(b));
        long volume = sparseJson.size(), liquidVolume = 0;
        for (var el:sparseJson) if (LiquidBlocks.isFlowableBlockString(el.getAsJsonObject().get("block").getAsString())) liquidVolume++;
        for (var el:fillJson) {
            var o = el.getAsJsonObject();
            int[] from = cc.wujm.ashlar.tool.ArgParse.requireCoords3(o,"from");
            int[] to = cc.wujm.ashlar.tool.ArgParse.requireCoords3(o,"to");
            long n = PlanGeometry.checkedVolume(Region.of(from,to));
            if (LiquidBlocks.isFlowableBlockString(o.get("block").getAsString())) liquidVolume = Math.addExact(liquidVolume,n);
            try { volume = Math.addExact(volume,n); }
            catch (ArithmeticException e) { throw new ToolArgError("aggregate build volume overflows"); }
        }
        validator.checkVolumeLimit(volume,settings.limits().maxBlocksPerOperation(),"aggregate build volume");
        if (args.snapshot()) {
            if (!settings.snapshot().enabled()) throw new ToolArgError("snapshot is disabled in config.yml");
            validator.checkVolumeLimit(PlanGeometry.checkedVolume(bounds),settings.snapshot().maxVolume(),"snapshot volume");
        }
        boolean flow = validator.resolveLiquidsFlow(options);
        validator.resolveConnect(options);
        if (flow) validator.checkVolumeLimit(liquidVolume,settings.limits().maxFlowingLiquidsPerOperation(),"aggregate flowing liquid blocks");
        long finalVolume = volume;
        return MainThread.call(() -> new int[]{world.getMinHeight(),world.getMaxHeight()}).thenApply(heights -> {
            List<FillOp> fills = fillJson.isEmpty() ? List.of() : validator.validateFillOps(fillJson,heights[0],heights[1],flow);
            List<SparseOp> blocks = sparseJson.isEmpty() ? List.of() : validator.validateSparseOps(sparseJson,heights[0],heights[1],flow);
            return new Validated(world,bounds,fills,blocks,heights[0],heights[1],finalVolume,settings);
        });
    }

    CompletableFuture<Result> analyze(InvocationContext ctx, Validated v, JsonObject preview) {
        return analyze(ctx,v,preview,false);
    }
    CompletableFuture<Result> analyze(InvocationContext ctx, Validated v, JsonObject preview, boolean capture) {
        RequestValidator validator = new RequestValidator(v.settings());
        RequestValidator.RenderParams rp = preview == null ? null : validator.validateRenderParams(preview,v.bounds());
        if (rp != null && rp.view().equals("heightmap")) throw new ToolArgError("planned previews support top, compass facades, slice, isometric and perspective; not heightmap");
        Region b = v.bounds();
        Region imageBounds = rp == null ? null : b;
        if (rp != null && rp.view().equals("slice")) imageBounds = switch (rp.sliceAxis()) {
            case "x" -> new Region(rp.sliceAt(),b.minY(),b.minZ(),rp.sliceAt(),b.maxY(),b.maxZ());
            case "y" -> new Region(b.minX(),rp.sliceAt(),b.minZ(),b.maxX(),rp.sliceAt(),b.maxZ());
            case "z" -> new Region(b.minX(),b.minY(),rp.sliceAt(),b.maxX(),b.maxY(),rp.sliceAt());
            default -> throw new ToolArgError("invalid slice axis");
        };
        long previewCap = v.settings().limits().maxReadVolume();
        if (rp != null && RenderCamera.angled(rp.view())) previewCap = Math.min(200_000,previewCap);
        if (imageBounds != null) validator.checkVolumeLimit(PlanGeometry.checkedVolume(imageBounds),previewCap,
                "preview envelope volume; use image:false or a slice for large designs");
        Region tickets = new Region(Math.max(-29_999_999,b.minX()-1),Math.max(v.minY(),b.minY()-1),Math.max(-29_999_999,b.minZ()-1),
                Math.min(29_999_999,b.maxX()+1),Math.min(v.maxY()-1,b.maxY()+1),Math.min(29_999_999,b.maxZ()+1));
        validator.checkChunkCount(tickets);
        PlanTask task = new PlanTask(tickets,b,v.world(),v.fills(),v.blocks(),v.minY(),v.maxY(),imageBounds,v.volume(),
                (int)Math.min(200_000,v.settings().limits().maxReadVolume()));
        if (capture) task.captureExpectation();
        return executor.submit(task,ctx).thenApply(report -> new Result(report.getAsJsonObject(),task,rp));
    }
    private static JsonObject fill(int[] from,int[] to,String block) {
        JsonObject o = new JsonObject(); o.add("from",JsonUtil.intArray(from)); o.add("to",JsonUtil.intArray(to)); o.addProperty("block",block); return o;
    }
    static void checkHorizontal(Region bounds) {
        if (bounds.minX() <= -30_000_000 || bounds.maxX() >= 30_000_000 || bounds.minZ() <= -30_000_000 || bounds.maxZ() >= 30_000_000)
            throw new ToolArgError("build coordinates must be strictly inside horizontal +/-30000000 safety bounds");
    }
}
