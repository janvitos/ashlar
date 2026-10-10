// SPDX-License-Identifier: AGPL-3.0-or-later
package cc.wujm.ashlar.tool.mc;

import cc.wujm.ashlar.engine.BuildExpectation;
import cc.wujm.ashlar.rpc.InvocationContext;
import cc.wujm.ashlar.tool.*;
import com.google.gson.JsonObject;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.CompletableFuture;

/** Explicit guarded sparse repair; never reconstructs the complete original build. */
public final class McRepair implements Tool {
    private final ToolSpec spec=ToolSpec.load("mc_repair");private final VerificationService service;private final DiffService diffs;
    public McRepair(VerificationService service){this(service,null);}
    public McRepair(VerificationService service,DiffService diffs){this.service=service;this.diffs=diffs;}
    private JournalService journals;
    public McRepair withJournal(JournalService service){this.journals=service;return this;}
    /** Runs one repair with its writes journalled (when a journal service is wired) and reports the entry in the JSON. */
    private CompletableFuture<String> journalled(InvocationContext ctx,java.util.function.Function<InvocationContext,CompletableFuture<JsonObject>> body){
        if(journals==null)return body.apply(ctx).thenApply(Object::toString);
        return journals.journalled(ctx,true,"mc_repair",null,body,(r,commit) -> commit.addTo(r));
    }
    @Override public ToolSpec spec(){return spec;}
    static Set<BuildExpectation.Pos> positions(JsonObject args) {
        if(!ArgParse.has(args,"positions"))return null;
        var a=ArgParse.requireArray(args,"positions");if(a.size()>10000)throw new ToolArgError("positions cannot exceed 10000 entries");
        Set<BuildExpectation.Pos> positions=new HashSet<>();
        for(var el:a){JsonObject p=new JsonObject();p.add("pos",el);int[] c=BuildTransform.strictCoords(p,"pos");if(!positions.add(new BuildExpectation.Pos(c[0],c[1],c[2])))throw new ToolArgError("positions must be unique");}
        return positions;
    }
    @Override public CompletableFuture<ToolResult> call(InvocationContext ctx,JsonObject args) {
        if(ArgParse.has(args,"diffId")) return ToolRunner.runText("mc_repair",() -> {
            if(ArgParse.has(args,"planId") || ArgParse.has(args,"comparisonId") || ArgParse.has(args,"limit"))throw new ToolArgError("diffId cannot be combined with planId, comparisonId or limit");
            if(diffs==null)throw new ToolArgError("mc_diff receipts are unavailable");
            String id=ArgParse.requireString(args,"diffId");var positions=positions(args);int max=McVerify.bounded(args,"maxChanges",1000,1,10000);
            return journalled(ctx,c -> diffs.repair(c,id,positions,max,ArgParse.optBoolean(args,"snapshot",true),ArgParse.optBoolean(args,"allowBlockEntityReplacement",false)));
        });
        return ToolRunner.runText("mc_repair",() -> {
            String planId=ArgParse.requireString(args,"planId"),comparisonId=ArgParse.requireString(args,"comparisonId");var positions=positions(args);
            int max=McVerify.bounded(args,"maxChanges",1000,1,10000),limit=McVerify.bounded(args,"limit",100,1,1000);
            return journalled(ctx,c -> service.repair(c,planId,comparisonId,positions,max,ArgParse.optBoolean(args,"snapshot",true),ArgParse.optBoolean(args,"allowBlockEntityReplacement",false),limit));
        });
    }
}
