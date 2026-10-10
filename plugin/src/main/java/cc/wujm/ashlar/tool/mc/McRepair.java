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
            return diffs.repair(ctx,ArgParse.requireString(args,"diffId"),positions(args),McVerify.bounded(args,"maxChanges",1000,1,10000),
                    ArgParse.optBoolean(args,"snapshot",true),ArgParse.optBoolean(args,"allowBlockEntityReplacement",false)).thenApply(Object::toString);
        });
        return ToolRunner.runText("mc_repair",() -> service.repair(ctx,ArgParse.requireString(args,"planId"),ArgParse.requireString(args,"comparisonId"),positions(args),
                McVerify.bounded(args,"maxChanges",1000,1,10000),ArgParse.optBoolean(args,"snapshot",true),ArgParse.optBoolean(args,"allowBlockEntityReplacement",false),McVerify.bounded(args,"limit",100,1,1000)).thenApply(Object::toString));
    }
}
