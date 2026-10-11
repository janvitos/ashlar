// SPDX-License-Identifier: AGPL-3.0-or-later
package cc.wujm.ashlar.engine;

import cc.wujm.ashlar.config.ConfigHolder;
import cc.wujm.ashlar.rpc.InvocationContext;
import cc.wujm.ashlar.rpc.MainThread;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import org.bukkit.World;

import java.util.List;
import java.util.concurrent.CompletableFuture;

/**
 * Execution path for {@code mc_build entities} and {@code mc_inspect entities} (Step 17). Callers
 * have already resolved the world and checked the region; registry lookups ({@link
 * EntityAccess#check}) run here, off the main thread, before anything is queued.
 */
public final class EntityService {

    private final ConfigHolder configHolder;
    private final TickBudgetExecutor executor;

    public EntityService(ConfigHolder configHolder, TickBudgetExecutor executor) {
        this.configHolder = configHolder;
        this.executor = executor;
    }

    /** Problem with a variant or item id, or null; safe off the main thread. */
    public String check(List<EntitySpec> specs) {
        return EntityAccess.check(specs);
    }

    /** Resolves and allow-list-checks {@code worldName} (null: the default world) for a read. */
    public World world(String worldName) {
        JsonObject params = new JsonObject();
        if (worldName != null) params.addProperty("world", worldName);
        return new RequestValidator(configHolder.get()).resolveWorld(params);
    }

    /**
     * Resolves and allow-list-checks {@code worldName} (null: the default world) and checks {@code
     * bounds} against the build region, the world height and the chunk limit. Throws on a problem.
     */
    public World validate(String worldName, Region bounds) {
        JsonObject params = new JsonObject();
        if (worldName != null) params.addProperty("world", worldName);
        RequestValidator v = new RequestValidator(configHolder.get());
        World world = v.resolveWorld(params);
        v.checkWriteRegion(bounds, world.getMinHeight(), world.getMaxHeight());
        return world;
    }

    /** Places {@code specs}; {@code bounds} covers every entity and the replace box. Not from the main thread. */
    public CompletableFuture<JsonElement> place(World world, List<EntitySpec> specs, Region replaceBox, Region bounds, InvocationContext ctx) {
        MainThread.assertNotPrimary("EntityService.place");
        return executor.submit(new EntityPlaceTask(world, specs, replaceBox, bounds), ctx);
    }

    /** Reads the signs and decorative entities in {@code region} ({@code {signs, entities, truncated?}}). */
    public CompletableFuture<JsonObject> read(World world, Region region, InvocationContext ctx) {
        MainThread.assertNotPrimary("EntityService.read");
        SnapshotExtrasTask task = SnapshotExtrasTask.capture(region, world);
        return executor.submit(task, ctx).thenApply(ignored -> task.captured());
    }
}
