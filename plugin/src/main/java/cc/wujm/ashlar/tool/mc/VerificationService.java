// SPDX-License-Identifier: AGPL-3.0-or-later
package cc.wujm.ashlar.tool.mc;

import cc.wujm.ashlar.config.ConfigHolder;
import cc.wujm.ashlar.engine.*;
import cc.wujm.ashlar.rpc.InvocationContext;
import cc.wujm.ashlar.rpc.RpcHandler;
import cc.wujm.ashlar.tool.ToolArgError;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import java.util.*;
import java.util.concurrent.CompletableFuture;

/** Shared capture/check/repair orchestration. All executor-enqueue continuations run off-main. */
public final class VerificationService {
    private final McBuild build; private final BuildPreflight preflight; private final TickBudgetExecutor executor;
    private final ConfigHolder config; private final VerificationStore store; private final RpcHandler snapshot;
    public VerificationService(McBuild build,BuildPreflight preflight,TickBudgetExecutor executor,ConfigHolder config,VerificationStore store,RpcHandler snapshot) {
        this.build=build;this.preflight=preflight;this.executor=executor;this.config=config;this.store=store;this.snapshot=snapshot;
    }
    static String owner(InvocationContext ctx) {return ctx.principal().kind()+":"+ctx.principal().id();}
    static JsonObject metadata(VerificationStore.Plan p) {
        JsonObject r=new JsonObject();r.addProperty("planId",p.id);r.addProperty("world",p.expectation.world());r.addProperty("cells",p.expectation.cells().size());
        r.addProperty("createdAt",p.created.toString());r.addProperty("expiresAt",p.expires.toString());r.addProperty("persistent",false);return r;
    }
    public CompletableFuture<JsonObject> prepare(InvocationContext ctx,JsonObject request) {
        return build.prepare(request,true).thenCompose(preflight::validate).thenCompose(v -> preflight.analyze(ctx,v,null,true).thenApplyAsync(result -> {
            boolean connected=request.has("connect") && !request.get("connect").isJsonNull() ? request.get("connect").getAsBoolean() : v.settings().engine().connectBlocks();
            var e=result.task().expectation(result.report().get("world").getAsString(),connected,"flow".equals(request.has("liquids") && !request.get("liquids").isJsonNull() ? request.get("liquids").getAsString() : "static"));
            if(e.cells().isEmpty())throw new ToolArgError("no eligible final cells; prepare expectations before construction, especially with keep/filters");
            new RequestValidator(v.settings()).checkVolumeLimit(e.cells().size(),v.settings().limits().maxReadVolume(),"verification cells");
            var p=store.put(owner(ctx),e);JsonObject r=metadata(p);r.add("preflight",result.report());
            r.addProperty("note","Frozen BEFORE placement. Build with the same request, then check this planId. Reload/restart/expiry invalidates it. Skipped cells and arbitrary NBT are not audited.");return r;
        }));
    }
    public JsonArray list(InvocationContext ctx) {JsonArray a=new JsonArray();store.list(owner(ctx)).forEach(p -> a.add(metadata(p)));return a;}
    public void delete(InvocationContext ctx,String id) {store.delete(id,owner(ctx));}
    private CompletableFuture<BuildPreflight.Validated> validate(BuildExpectation e,List<BuildExpectation.Cell> cells,boolean backup,boolean placement,List<BuildExpectation.Observed> observed) {
        List<McBuild.SparseOpArg> blocks=new ArrayList<>();
        for(int i=0;i<cells.size();i++) {
            var c=cells.get(i);String state=c.state();
            if(placement && observed!=null && BuildExpectation.material(state).equals(BuildExpectation.material(observed.get(i).actual()))) {
                var props=BuildExpectation.properties(state);var actual=BuildExpectation.properties(observed.get(i).actual());
                for(String k:BuildExpectation.excluded(state,true,e.connected()))if(actual.containsKey(k))props.put(k,actual.get(k));
                if(!props.isEmpty())state=BuildExpectation.material(state)+"["+String.join(",",props.entrySet().stream().map(k -> k.getKey()+"="+k.getValue()).toList())+"]";
            }
            blocks.add(new McBuild.SparseOpArg(new int[]{c.pos().x(),c.pos().y(),c.pos().z()},state,null));
        }
        new RequestValidator(config.get()).checkVolumeLimit(cells.size(),config.get().limits().maxReadVolume(),"verification cells");
        return preflight.validate(new McBuild.Args(e.world(),List.of(),blocks,List.of(),backup,false,"static"));
    }
    public CompletableFuture<JsonObject> check(InvocationContext ctx,String id,boolean placement,int samples) {
        var p=store.get(id,owner(ctx));store.acquire(p);
        try {return checkInternal(ctx,p,placement,samples).whenComplete((r,t) -> store.release(p));}
        catch(RuntimeException e){store.release(p);throw e;}
    }
    private CompletableFuture<JsonObject> checkInternal(InvocationContext ctx,VerificationStore.Plan p,boolean placement,int samples) {
        return validate(p.expectation,p.expectation.cells(),false,false,null).thenComposeAsync(v -> {
            VerifyTask task=new VerifyTask(v.world(),p.expectation,placement,samples);
            return executor.submit(task,ctx).thenApplyAsync(result -> {
                var c=store.record(p,placement,task.differences());JsonObject r=result.getAsJsonObject();r.addProperty("planId",p.id);r.addProperty("comparisonId",c.id());return r;
            });
        });
    }
    static List<BuildExpectation.Observed> select(VerificationStore.Comparison c,Set<BuildExpectation.Pos> positions,int max) {
        List<BuildExpectation.Observed> selected=c.differences().stream().filter(d -> positions==null || positions.contains(d.expected().pos())).toList();
        if(positions!=null && selected.size()!=positions.size())throw new ToolArgError("positions must be unique mismatches from this comparison");
        if(selected.size()>max)throw new ToolArgError("repair selection exceeds maxChanges; select explicit positions or compare again with a larger maxChanges (at most 10000)");return selected;
    }
    public CompletableFuture<JsonObject> repair(InvocationContext ctx,String id,String comparison,Set<BuildExpectation.Pos> positions,int max,boolean backup,boolean allowEntities,int samples) {
        var p=store.get(id,owner(ctx));store.acquire(p);
        try {
            if(p.expectation.flowing())throw new ToolArgError("flowing-fluid expectations cannot be automatically repaired; use a static build");
            var c=store.comparison(p,comparison);var selected=select(c,positions,max);
            if(selected.isEmpty())return checkInternal(ctx,p,c.placement(),samples).thenApply(r -> {
                JsonObject noOp=new JsonObject();noOp.addProperty("selectedCells",0);noOp.addProperty("writtenCells",0);noOp.addProperty("signWrites",0);noOp.addProperty("guardPassed",true);noOp.addProperty("staleCells",0);noOp.addProperty("protectedBlockEntities",0);noOp.add("issues",new JsonArray());r.add("repair",noOp);return r;
            }).whenComplete((r,t) -> store.release(p));
            var cells=selected.stream().map(BuildExpectation.Observed::expected).toList();
            return validate(p.expectation,cells,backup,c.placement(),selected).thenComposeAsync(v -> {
                RepairTask guard=new RepairTask(v.bounds(),v.world(),selected,v.blocks(),true,allowEntities);
                return executor.submit(guard,ctx).thenComposeAsync(g -> {
                    if(!g.getAsJsonObject().get("guardPassed").getAsBoolean())throw new ToolArgError("repair guard rejected before snapshot/write: "+g);
                    CompletableFuture<JsonObject> snap;
                    if(backup) {
                        JsonObject a=new JsonObject();a.addProperty("world",p.expectation.world());a.add("from",JsonUtil.intArray(new int[]{v.bounds().minX(),v.bounds().minY(),v.bounds().minZ()}));a.add("to",JsonUtil.intArray(new int[]{v.bounds().maxX(),v.bounds().maxY(),v.bounds().maxZ()}));a.addProperty("label","mc_repair delta snapshot (block states only)");
                        snap=snapshot.handle(ctx,a).thenApply(el -> el.getAsJsonObject());
                    } else snap=CompletableFuture.completedFuture(null);
                    return snap.thenComposeAsync(s -> executor.submit(new RepairTask(v.bounds(),v.world(),selected,v.blocks(),false,allowEntities),ctx).thenComposeAsync(repaired ->
                            checkInternal(ctx,p,c.placement(),samples).thenApply(report -> {
                                report.add("repair",repaired);if(s!=null)report.add("snapshot",s);
                                report.addProperty("snapshotLimitations","Snapshots store block states only, not sign text/colors, inventory or arbitrary NBT. No atomic world lock; late edits are skipped.");return report;
                            })));
                });
            }).whenComplete((r,t) -> store.release(p));
        } catch(RuntimeException e){store.release(p);throw e;}
    }
}
