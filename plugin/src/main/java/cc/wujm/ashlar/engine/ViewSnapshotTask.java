// SPDX-License-Identifier: AGPL-3.0-or-later
package cc.wujm.ashlar.engine;

import cc.wujm.ashlar.render.FirstPersonCamera;
import com.google.gson.JsonElement;
import com.google.gson.JsonNull;
import org.bukkit.ChunkSnapshot;
import org.bukkit.World;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Main-thread half of a first-person view or sightline: copies each requested chunk column that is
 * already loaded into a {@link ChunkSnapshot}, a few per tick under the executor's budget. It never
 * loads a chunk and takes no chunk tickets; columns that are not loaded stay absent and read as
 * unknown. All ray work then happens off the main thread on the copies.
 */
public final class ViewSnapshotTask extends BuildTask {
    private final World world;
    private final List<Long> chunks;
    private final Map<Long, ChunkSnapshot> snapshots = new HashMap<>();
    private int index;
    private int minY, maxY;

    public ViewSnapshotTask(World world, List<Long> chunks) {
        super(bounds(chunks));
        this.world = world;
        this.chunks = List.copyOf(chunks);
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
        return chunks.size();
    }

    @Override
    public boolean step(long deadlineNanos) {
        if (index == 0) {
            minY = world.getMinHeight();
            maxY = world.getMaxHeight() - 1;
        }
        while (index < chunks.size()) {
            if (System.nanoTime() >= deadlineNanos) return false;
            long c = chunks.get(index++);
            int cx = FirstPersonCamera.chunkX(c), cz = FirstPersonCamera.chunkZ(c);
            if (world.isChunkLoaded(cx, cz)) {
                snapshots.put(c, world.getChunkAt(cx, cz).getChunkSnapshot(false, false, false));
            }
            advance(1);
        }
        return true;
    }

    @Override
    public JsonElement buildResult(long queuedMs, long elapsedMs) {
        return JsonNull.INSTANCE;
    }

    /** Off-main, only after completion (the completion future is the memory barrier). */
    public SnapshotVoxels voxels() {
        return new SnapshotVoxels(snapshots, minY, maxY);
    }

    public int loaded() {
        return snapshots.size();
    }

    public int requested() {
        return chunks.size();
    }
}
