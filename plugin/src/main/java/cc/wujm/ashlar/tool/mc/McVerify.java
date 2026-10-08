// SPDX-License-Identifier: AGPL-3.0-or-later
package cc.wujm.ashlar.tool.mc;

import cc.wujm.ashlar.rpc.InvocationContext;
import cc.wujm.ashlar.tool.*;
import com.google.gson.JsonObject;
import java.util.List;
import java.util.concurrent.CompletableFuture;

/** Non-destructive verification with frozen pre-build expectations. */
public final class McVerify implements Tool {
    private final ToolSpec spec=ToolSpec.load("mc_verify");private final VerificationService service;
    public McVerify(McBuild build,VerificationService service) {this.service=service;spec.inputSchema().getAsJsonObject("properties").add("build",build.spec().inputSchema().deepCopy());}
    @Override public ToolSpec spec(){return spec;}
    static int bounded(JsonObject args,String field,int fallback,int min,int max) {
        int n=ArgParse.has(args,field) ? BuildTransform.strictInt(args.get(field),field) : fallback;
        if(n<min || n>max)throw new ToolArgError(field+" must be between "+min+" and "+max);return n;
    }
    @Override public CompletableFuture<ToolResult> call(InvocationContext ctx,JsonObject args) {
        return ToolRunner.runText("mc_verify",() -> {
            String action=ArgParse.optEnum(args,"action",List.of("prepare","check","list","delete"),"check");
            return switch(action) {
                case "prepare" -> service.prepare(ctx,ArgParse.requireObject(args.get("build"),"build")).thenApply(Object::toString);
                case "check" -> service.check(ctx,ArgParse.requireString(args,"planId"),"placement".equals(ArgParse.optEnum(args,"mode",List.of("exact","placement"),"exact")),bounded(args,"limit",100,1,1000)).thenApply(Object::toString);
                case "list" -> CompletableFuture.completedFuture(service.list(ctx).toString());
                case "delete" -> {service.delete(ctx,ArgParse.requireString(args,"planId"));yield CompletableFuture.completedFuture("Verification plan deleted; placed blocks are unchanged.");}
                default -> throw new ToolArgError("unknown verification action");
            };
        });
    }
}
