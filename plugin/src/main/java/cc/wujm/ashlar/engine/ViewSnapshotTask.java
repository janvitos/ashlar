// SPDX-License-Identifier: AGPL-3.0-or-later
package cc.wujm.ashlar.engine;

import cc.wujm.ashlar.render.FirstPersonCamera;
import com.google.gson.JsonElement;
import com.google.gson.JsonNull;
import org.bukkit.ChunkSnapshot;
import org.bukkit.World;

import java.util.List;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;

/**
 * Main-thread half of a first-person view or sightline: copies each requested chunk column into a
 * {@link ChunkSnapshot}, a few per tick under the executor's budget ({@link ViewChunkGather}).
 * Columns that are not loaded are loaded in the background with {@code getChunkAtAsync} without
 * generation, nearest first and at most {@code loadCap} per view; no chunk tickets are taken, so
 * nothing stays loaded afterwards. Columns that were never generated or are over the cap stay absent
 * and read as unknown. All ray work then happens off the main thread on the copies.
 */
public final class ViewSnapshotTask extends BuildTask {
    private final World world;
    private final ViewChunkGather<ChunkSnapshot> gather;
    private boolean started;
    private int minY, maxY;

    /** {@code chunks}: nearest first ({@link ViewChunkGather#byDistance}); {@code loadCap} 0 reads loaded columns only. */
    public ViewSnapshotTask(World world, List<Long> chunks, int loadCap) {
        super(bounds(chunks));
        this.world = world;
        this.gather = new ViewChunkGather<>(new ViewChunkGather.Source<>() {
            @Override
            public boolean isLoaded(int cx, int cz) {
                return world.isChunkLoaded(cx, cz);
            }

            @Override
            public ChunkSnapshot copy(int cx, int cz) {
                return world.getChunkAt(cx, cz).getChunkSnapshot(false, false, false);
            }

            @Override
            public CompletableFuture<Boolean> load(int cx, int cz) {
                return world.getChunkAtAsync(cx, cz, false).thenApply(Objects::nonNull);
            }
        }, chunks, loadCap);
    }

    private static Region bounds(List<Long> chunks) {
        int x0 = Integer.MAX_VALUE, z0 = Integer.MAX_VALUE, x1 = Integer.MIN_VALUE, z1 = Integer.MIN_VALUE;
        for (long c : chunks) {
            x0 = Math.min(x0, FirstPersonCamera.chunkX(c));
            x1 = Math.max(x1, FirstPersonCamera.chunkX(c));
            z0 = Math.min(z0, FirstPersonCamera.chunkZ(c));
            z1 = Math.max(z1, FirstPersonCamera.chunkZ(c));
        }
        if (chunks.isEmpty()) return new Region(0, 0, 0, 0, 0, 0);
        return new Region(x0 * 16, 0, z0 * 16, x1 * 16 + 15, 0, z1 * 16 + 15);
    }

    @Override
    public boolean needsChunkTickets() {
        return false;
    }

    @Override
    public World world() {
        return world;
    }

    @Override
    public long volume() {
        return gather.requested();
    }

    @Override
    public boolean step(long deadlineNanos) {
        if (!started) {
            started = true;
            minY = world.getMinHeight();
            maxY = world.getMaxHeight() - 1;
        }
        return gather.step(() -> System.nanoTime() < deadlineNanos);
    }

    @Override
    public JsonElement buildResult(long queuedMs, long elapsedMs) {
        return JsonNull.INSTANCE;
    }

    /** Off-main, only after completion (the completion future is the memory barrier). */
    public SnapshotVoxels voxels() {
        return new SnapshotVoxels(gather.copies(), minY, maxY);
    }

    /** Columns copied: already loaded plus loaded for this view. */
    public int loaded() {
        return gather.copies().size();
    }

    public int requested() {
        return gather.requested();
    }

    public int loadedForView() {
        return gather.loadedForView();
    }

    public int notGenerated() {
        return gather.notGenerated();
    }

    public int overCap() {
        return gather.overCap();
    }

    public int failed() {
        return gather.failed();
    }
}
