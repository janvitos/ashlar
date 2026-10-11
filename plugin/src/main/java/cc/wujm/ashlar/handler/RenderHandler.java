// SPDX-License-Identifier: AGPL-3.0-or-later
package cc.wujm.ashlar.handler;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import cc.wujm.ashlar.config.ConfigHolder;
import cc.wujm.ashlar.engine.HeightmapTypes;
import cc.wujm.ashlar.engine.Region;
import cc.wujm.ashlar.engine.RenderService;
import cc.wujm.ashlar.engine.RequestValidator;
import cc.wujm.ashlar.rpc.InvocationContext;
import cc.wujm.ashlar.rpc.ErrorCode;
import cc.wujm.ashlar.render.RenderCamera;
import cc.wujm.ashlar.rpc.MainThread;
import cc.wujm.ashlar.rpc.RpcError;
import cc.wujm.ashlar.rpc.RpcHandler;
import org.bukkit.HeightMap;
import org.bukkit.World;

import java.util.concurrent.CompletableFuture;

/**
 * {@code render}: projects a region into a PNG (top/side/slice views,
 * docs/prompts/step4e-prompt.md). Parses and validates params for each of
 * the three view families (region, top, heightmap); execution (the budgeted
 * read plus image work) lives in {@link RenderService}, shared with the
 * in-process tool layer (plan.md step7).
 */
public final class RenderHandler implements RpcHandler {

    private final ConfigHolder configHolder;
    private final RenderService renderService;
    private cc.wujm.ashlar.snapshot.SnapshotStore snapshots;

    public RenderHandler(ConfigHolder configHolder, RenderService renderService) {
        this.configHolder = configHolder;
        this.renderService = renderService;
    }

    /** Enables {@code compareTo} for angled views (Step 20). */
    public RenderHandler withSnapshots(cc.wujm.ashlar.snapshot.SnapshotStore snapshots) {
        this.snapshots = snapshots;
        return this;
    }

    /** The snapshot named by {@code compareTo}, or null; checks view, world and overlap. */
    cc.wujm.ashlar.snapshot.Snapshot compareTo(JsonObject params, String view, String worldName, Region region) {
        if (!params.has("compareTo") || params.get("compareTo").isJsonNull()) return null;
        if (!params.get("compareTo").isJsonPrimitive() || !params.getAsJsonPrimitive("compareTo").isString())
            throw new RpcError(ErrorCode.BAD_REQUEST, "\"compareTo\" must be a snapshot id string");
        if (!RenderCamera.angled(view)) throw new RpcError(ErrorCode.BAD_REQUEST, "\"compareTo\" works only with view \"isometric\" or \"perspective\"");
        String id = params.get("compareTo").getAsString();
        var snap = snapshots == null ? null : snapshots.get(id).orElse(null);
        if (snap == null) throw new RpcError(ErrorCode.BAD_REQUEST, "no snapshot \"" + id + "\" (mc_snapshot list shows the stored ones)");
        if (!snap.world().equals(worldName)) throw new RpcError(ErrorCode.BAD_REQUEST, "snapshot " + id + " is of world \"" + snap.world() + "\", not \"" + worldName + "\"");
        Region s = snap.region();
        if (s.maxX() < region.minX() || s.minX() > region.maxX() || s.maxY() < region.minY() || s.minY() > region.maxY() || s.maxZ() < region.minZ() || s.minZ() > region.maxZ())
            throw new RpcError(ErrorCode.BAD_REQUEST, "snapshot " + id + " covers " + s.minX() + "," + s.minY() + "," + s.minZ() + " to " + s.maxX() + "," + s.maxY() + "," + s.maxZ() + ", which does not overlap the render bounds");
        return snap;
    }

    @Override
    public CompletableFuture<JsonElement> handle(InvocationContext ctx, JsonObject params) {
        try {
            RequestValidator validator = new RequestValidator(configHolder.get());
            World world = validator.resolveWorld(params);
            String view = validator.peekRenderView(params);
            try {
                RenderCamera.parse(params,view);
                RenderCamera.validateBounds(params,view);
            } catch (IllegalArgumentException e) {
                throw new RpcError(ErrorCode.BAD_REQUEST,e.getMessage());
            }
            if ("heightmap".equals(view)) {
                return startHeightmapRender(validator, world, params, ctx);
            }
            if ("top".equals(view)) {
                return MainThread.call(() -> new int[]{world.getMinHeight(), world.getMaxHeight()})
                        .thenCompose(heights -> startTopRender(validator, world, params, heights, ctx));
            }
            return MainThread.call(() -> new int[]{world.getMinHeight(), world.getMaxHeight()})
                    .thenCompose(heights -> startRender(validator, world, params, heights, ctx));
        } catch (RpcError e) {
            return CompletableFuture.failedFuture(e);
        }
    }

    private CompletableFuture<JsonElement> startRender(RequestValidator validator, World world, JsonObject params,
            int[] heights, InvocationContext ctx) {
        try {
            long cap = configHolder.get().limits().maxReadVolume();
            if (RenderCamera.angled(validator.peekRenderView(params))) cap = Math.min(cap,200_000);
            Region region = validator.validateReadRegion(params, heights[0], heights[1], cap);
            RequestValidator.RenderParams renderParams = validator.validateRenderParams(params, region);
            if (!params.has("compareTo")) return renderService.renderRegion(world, region, renderParams, ctx);
            return MainThread.call(world::getName).thenCompose(name -> {
                try {
                    return renderService.renderRegion(world, region, renderParams, compareTo(params, renderParams.view(), name, region), ctx);
                } catch (RpcError e) {
                    return CompletableFuture.failedFuture(e);
                }
            });
        } catch (RpcError e) {
            return CompletableFuture.failedFuture(e);
        }
    }

    private CompletableFuture<JsonElement> startTopRender(RequestValidator validator, World world, JsonObject params,
            int[] worldHeights, InvocationContext ctx) {
        try {
            Region region = validator.validateTopRegion(params, worldHeights[0], worldHeights[1], configHolder.get().limits().maxReadVolume());
            RequestValidator.RenderParams renderParams = validator.validateRenderParams(params, region);
            return renderService.renderTop(world, region, renderParams, ctx);
        } catch (RpcError e) {
            return CompletableFuture.failedFuture(e);
        }
    }

    private CompletableFuture<JsonElement> startHeightmapRender(RequestValidator validator, World world,
            JsonObject params, InvocationContext ctx) {
        try {
            RequestValidator.HeightmapRenderParams hp =
                    validator.validateHeightmapRenderParams(params, configHolder.get().limits().maxReadVolume());
            HeightMap requestedMap = HeightmapTypes.resolve(hp.type());
            return renderService.renderHeightmap(world, requestedMap, hp, ctx);
        } catch (RpcError e) {
            return CompletableFuture.failedFuture(e);
        }
    }
}
