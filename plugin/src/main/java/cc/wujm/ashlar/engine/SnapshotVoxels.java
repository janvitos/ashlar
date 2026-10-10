// SPDX-License-Identifier: AGPL-3.0-or-later
package cc.wujm.ashlar.engine;

import cc.wujm.ashlar.render.BlockShapes;
import cc.wujm.ashlar.render.FirstPersonCamera;
import cc.wujm.ashlar.render.VoxelRay;
import org.bukkit.ChunkSnapshot;
import org.bukkit.block.data.BlockData;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * {@link VoxelRay.Voxels} over {@link ChunkSnapshot} copies, for one ray job on one thread (not
 * thread-safe). Snapshot reads are the Bukkit API's documented off-main path; the only other calls
 * are {@link BlockData#getAsString()} and {@link BlockData#getMapColor()} on the immutable states
 * those reads return. Each 16x16x16 section is converted to palette ids the first time a ray
 * enters it, so memory follows what the rays actually visit.
 */
public final class SnapshotVoxels implements VoxelRay.Voxels {
    private final Map<Long, ChunkSnapshot> snapshots;
    private final int minY, maxY;
    private final Map<Long, char[]> sections = new HashMap<>();
    private final Map<BlockData, Integer> ids = new HashMap<>();
    private final List<String> states = new ArrayList<>();
    private final List<BlockShapes.Shape> shapes = new ArrayList<>();
    private final List<Integer> colors = new ArrayList<>();
    private long lastKey = Long.MIN_VALUE;
    private char[] last;

    SnapshotVoxels(Map<Long, ChunkSnapshot> snapshots, int minY, int maxY) {
        this.snapshots = snapshots;
        this.minY = minY;
        this.maxY = maxY;
        // Id 0 is always air.
        states.add("minecraft:air");
        shapes.add(BlockShapes.resolve("minecraft:air"));
        colors.add(0);
    }

    @Override
    public int id(int x, int y, int z) {
        int cx = x >> 4, cz = z >> 4, sy = (y - minY) >> 4;
        // 22 bits per chunk coordinate covers +/-30,000,000 blocks; 8 bits cover 4096 blocks of height.
        long key = ((long) (cx & 0x3FFFFF) << 30) | ((long) (cz & 0x3FFFFF) << 8) | (sy & 0xFF);
        char[] sec;
        if (key == lastKey && last != null) {
            sec = last;
        } else {
            sec = sections.get(key);
            if (sec == null) {
                ChunkSnapshot snap = snapshots.get(FirstPersonCamera.pack(cx, cz));
                if (snap == null) return UNKNOWN;
                sec = convert(snap, minY + sy * 16);
                sections.put(key, sec);
            }
            lastKey = key;
            last = sec;
        }
        return sec[((y - minY) & 15) << 8 | (z & 15) << 4 | (x & 15)];
    }

    private char[] convert(ChunkSnapshot snap, int baseY) {
        char[] out = new char[4096];
        int top = Math.min(15, maxY - baseY);
        for (int dy = 0; dy <= top; dy++) {
            for (int dz = 0; dz < 16; dz++) {
                for (int dx = 0; dx < 16; dx++) {
                    BlockData d = snap.getBlockData(dx, baseY + dy, dz);
                    Integer id = ids.get(d);
                    if (id == null) id = register(d);
                    out[dy << 8 | dz << 4 | dx] = (char) id.intValue();
                }
            }
        }
        return out;
    }

    private int register(BlockData d) {
        String state = d.getAsString();
        int id;
        if (d.getMaterial().isAir()) {
            id = AIR;
        } else {
            if (states.size() >= 65_535) throw new IllegalStateException("too many distinct block states in view");
            id = states.size();
            states.add(state);
            shapes.add(BlockShapes.resolve(state));
            colors.add(color(d));
        }
        ids.put(d, id);
        return id;
    }

    private static int color(BlockData d) {
        var c = d.getMapColor();
        int rgb = c == null ? 0 : c.asRGB() & 0xFFFFFF;
        return rgb == 0 ? 0 : 0xFF000000 | rgb;
    }

    @Override
    public BlockShapes.Shape shape(int id) {
        return shapes.get(id);
    }

    @Override
    public String state(int id) {
        return states.get(id);
    }

    public int color(int id) {
        return colors.get(id);
    }

    @Override
    public int minY() {
        return minY;
    }

    @Override
    public int maxY() {
        return maxY;
    }
}
