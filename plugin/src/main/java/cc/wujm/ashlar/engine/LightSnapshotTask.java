// SPDX-License-Identifier: AGPL-3.0-or-later
package cc.wujm.ashlar.engine;

import com.google.gson.JsonElement;
import com.google.gson.JsonNull;
import org.bukkit.ChunkSnapshot;
import org.bukkit.World;
import org.bukkit.block.data.BlockData;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Main-thread half of {@code mc_light} (Step 18): copies every chunk column of the box into a
 * {@link ChunkSnapshot} (block states plus block and sky light), a few per tick under the
 * executor's budget, with the usual chunk tickets. All rules and planning run off the main thread
 * on the copies through {@link #cells()}.
 */
public final class LightSnapshotTask extends BuildTask {
    private final World world;
    private final List<Region.ChunkCoord> chunks;
    private final Map<Long, ChunkSnapshot> snapshots = new HashMap<>();
    private int index;
    private int minY, maxY;

    public LightSnapshotTask(World world, Region box) {
        super(box);
        this.world = world;
        List<Region.ChunkCoord> list = new java.util.ArrayList<>();
        box.chunkCoords().forEach(list::add);
        this.chunks = List.copyOf(list);
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
            if (index > 0 && System.nanoTime() >= deadlineNanos) return false;
            Region.ChunkCoord c = chunks.get(index++);
            snapshots.put(LightScan.key(c.cx(), 0, c.cz()), world.getChunkAt(c.cx(), c.cz()).getChunkSnapshot(false, false, false));
            advance(1);
        }
        return true;
    }

    @Override
    public JsonElement buildResult(long queuedMs, long elapsedMs) {
        return JsonNull.INSTANCE;
    }

    /** Off-main, only after completion (the completion future is the memory barrier). One reader per thread. */
    public LightScan.Cells cells() {
        Map<BlockData, String> names = new HashMap<>();
        return new LightScan.Cells() {
            private ChunkSnapshot at(int x, int z) {
                return snapshots.get(LightScan.key(x >> 4, 0, z >> 4));
            }

            @Override
            public String state(int x, int y, int z) {
                if (y > maxY) return "minecraft:air";
                ChunkSnapshot s = at(x, z);
                if (s == null || y < minY) return "minecraft:stone";
                return names.computeIfAbsent(s.getBlockData(x & 15, y, z & 15), BlockData::getAsString);
            }

            @Override
            public int blockLight(int x, int y, int z) {
                ChunkSnapshot s = at(x, z);
                return s == null || y < minY || y > maxY ? 15 : s.getBlockEmittedLight(x & 15, y, z & 15);
            }

            @Override
            public int skyLight(int x, int y, int z) {
                ChunkSnapshot s = at(x, z);
                if (y > maxY) return 15;
                return s == null || y < minY ? 15 : s.getBlockSkyLight(x & 15, y, z & 15);
            }
        };
    }
}
