// SPDX-License-Identifier: AGPL-3.0-or-later
package cc.wujm.ashlar.tool.mc;

import cc.wujm.ashlar.config.ConfigHolder;
import cc.wujm.ashlar.engine.ReadTask;
import cc.wujm.ashlar.engine.Region;
import cc.wujm.ashlar.engine.RegionData;
import cc.wujm.ashlar.engine.RequestValidator;
import cc.wujm.ashlar.engine.TickBudgetExecutor;
import cc.wujm.ashlar.rpc.InvocationContext;
import cc.wujm.ashlar.rpc.MainThread;
import cc.wujm.ashlar.tool.ArgParse;
import cc.wujm.ashlar.tool.ToolArgError;
import com.google.gson.JsonElement;
import com.google.gson.JsonNull;
import com.google.gson.JsonObject;
import org.bukkit.Bukkit;
import org.bukkit.World;
import org.bukkit.block.data.type.Stairs;
import java.util.concurrent.CompletableFuture;

/** One bounded readonly capture, then pure fitting and shared validation before persistence. */
public final class TerrainFitService {
    private final ConfigHolder config;
    private final TickBudgetExecutor executor;
    private final BuildPreflight preflight;
    public TerrainFitService(ConfigHolder config,TickBudgetExecutor executor,BuildPreflight preflight) {
        this.config=config;this.executor=executor;this.preflight=preflight;
    }
    public CompletableFuture<String> fit(InvocationContext ctx,JsonObject args,BlueprintStore store) {
        String id=ArgParse.requireString(args,"id");BlueprintCompiler.name(id);
        boolean overwrite=ArgParse.optBoolean(args,"overwrite",false);
        JsonObject site=ArgParse.requireObject(args.get("site"),"site");
        TerrainFitter.Request request=TerrainFitter.parse(site);
        var full=Bukkit.createBlockData(request.full());
        if(!(Bukkit.createBlockData(request.stairs()) instanceof Stairs))throw new ToolArgError("site.materials.stairs must be a stair material");
        var settings=config.get();RequestValidator validator=new RequestValidator(settings);
        World world=validator.resolveWorld(site);
        String worldName=ArgParse.has(site,"world")?ArgParse.requireString(site,"world"):settings.world().defaultWorld();
        Region bounds=request.readBounds();JsonObject read=new JsonObject();
        read.add("from",TerrainFitter.point(bounds.minX(),bounds.minY(),bounds.minZ()));read.add("to",TerrainFitter.point(bounds.maxX(),bounds.maxY(),bounds.maxZ()));
        return MainThread.call(()->{
            if(!full.getMaterial().isOccluding()||full.getMaterial().hasGravity())throw new ToolArgError("site.materials.full must be an occluding, non-gravity solid material");
            return new int[]{world.getMinHeight(),world.getMaxHeight()};
        }).thenComposeAsync(heights->{
            validator.validateReadRegion(read,heights[0],heights[1],Math.min(200_000,settings.limits().maxReadVolume()));
            Capture task=new Capture(bounds,world);
            return executor.submit(task,ctx).thenComposeAsync(ignored->{
                var fitted=TerrainFitter.fit(request,task.regionData());
                JsonObject build=new JsonObject(),selector=new JsonObject(),transform=new JsonObject();
                selector.addProperty("id",id);build.add("blueprint",selector);build.addProperty("world",worldName);build.addProperty("connect",false);
                transform.add("origin",TerrainFitter.point(request.x0(),request.floorY(),request.z0()));build.add("transform",transform);
                var live=config.get().limits();
                var limits=new BlueprintCompiler.Limits(live.maxBlocksPerOperation(),live.maxChunksPerOperation(),live.maxFlowingLiquidsPerOperation());
                var parts=BlueprintCompiler.compile(fitted.document(),build,limits);
                return BuildStateTransform.applyParts(parts,true).thenCompose(preflight::validate).thenApplyAsync(validated->{
                    JsonObject report=fitted.summary();report.addProperty("world",worldName);report.addProperty("observedAt",java.time.Instant.now().toString());
                    // Site metadata is advisory; later placement retains ordinary current build validation.
                    JsonObject constraints=new JsonObject();constraints.add("fittedSite",report.deepCopy());fitted.document().add("constraints",constraints);
                    JsonObject saved=store.save(id,fitted.document(),overwrite);
                    build.addProperty("snapshot",true);report.add("build",build);saved.add("site",report);
                    return saved.toString();
                });
            });
        });
    }
    /** Capture states on-main; finalize immutable encoding off-main after the future barrier. */
    private static final class Capture extends ReadTask {
        private RegionData captured;
        Capture(Region r,World w){super(r,w);}
        @Override public JsonElement buildResult(long queuedMs,long elapsedMs){return JsonNull.INSTANCE;}
        @Override public RegionData regionData(){if(captured==null)captured=finishReadData();return captured;}
    }
}
