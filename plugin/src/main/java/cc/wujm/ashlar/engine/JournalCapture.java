// SPDX-License-Identifier: AGPL-3.0-or-later
package cc.wujm.ashlar.engine;

import cc.wujm.ashlar.journal.JournalRecorder;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.TileState;
import org.bukkit.block.data.BlockData;

import java.util.EnumMap;
import java.util.HashMap;
import java.util.Map;

/**
 * Bukkit adapter around {@link JournalRecorder} for one tool call. Write sites call {@link #before}
 * right before every {@code setBlockData}; {@link BuildTask#runStep} calls {@link #finish} after the
 * task's own work so final states are read before any other queued task can run. Main thread only,
 * except {@link #recorder()} and {@link #world()}, which are read after the call's futures completed.
 */
public final class JournalCapture {

    private static final int DEADLINE_CHECK_INTERVAL = 256;
    private static final int[] NX = {0, 1, -1, 0, 0, 0, 0};
    private static final int[] NY = {0, 0, 0, 1, -1, 0, 0};
    private static final int[] NZ = {0, 0, 0, 0, 0, 1, -1};

    private final JournalRecorder recorder;
    private final Map<BlockData, Integer> paletteCache = new HashMap<>();
    private final Map<Material, Boolean> blockEntity = new EnumMap<>(Material.class);
    private World world;
    private boolean mixedWorlds;
    private int finishCursor;

    public JournalCapture(int maxCells) {
        this.recorder = new JournalRecorder(maxCells);
    }

    public JournalRecorder recorder() {
        return recorder;
    }

    /** The world written to; {@code null} when nothing was recorded. */
    public World world() {
        return world;
    }

    /** True if one call wrote into two worlds; the entry is then not kept. */
    public boolean mixedWorlds() {
        return mixedWorlds;
    }

    /** Records {@code block}'s original state ({@code current}) the first time it is written. */
    public void before(Block block, BlockData current) {
        if (recorder.overflowed() || mixedWorlds) return;
        World w = block.getWorld();
        if (world == null) world = w;
        else if (world != w) { mixedWorlds = true; return; }
        long key = JournalRecorder.pack(block.getX(), block.getY(), block.getZ());
        if (recorder.touch(key, index(current)) && hasBlockEntity(block, current.getMaterial())) {
            recorder.addBlockEntityCell();
        }
    }

    /**
     * Records a cell and its six face neighbours before a physics-enabled rewrite, which may change
     * the neighbours' connection shapes without any call we can hook.
     */
    public void beforeNeighbourhood(World w, int x, int y, int z) {
        for (int i = 0; i < NX.length; i++) {
            int ny = y + NY[i];
            if (ny < w.getMinHeight() || ny >= w.getMaxHeight()) continue;
            Block b = w.getBlockAt(x + NX[i], ny, z + NZ[i]);
            before(b, b.getBlockData());
        }
    }

    /** A sign value written without a block state change: the journal cannot undo it. */
    public void noteBlockEntityWrite() {
        recorder.addBlockEntityWrite();
    }

    /** Reads the final state of every cell touched since the last finish. Returns true once done. */
    boolean finish(long deadlineNanos) {
        if (world == null || recorder.overflowed() || mixedWorlds) {
            recorder.clearPending();
            return true;
        }
        int n = recorder.pendingSize();
        while (finishCursor < n) {
            int entry = recorder.pendingEntry(finishCursor);
            long key = recorder.key(entry);
            Block b = world.getBlockAt(JournalRecorder.x(key), JournalRecorder.y(key), JournalRecorder.z(key));
            recorder.setNew(entry, index(b.getBlockData()));
            finishCursor++;
            if (finishCursor % DEADLINE_CHECK_INTERVAL == 0 && finishCursor < n && System.nanoTime() >= deadlineNanos) {
                return false;
            }
        }
        recorder.clearPending();
        finishCursor = 0;
        return true;
    }

    private int index(BlockData data) {
        Integer i = paletteCache.get(data);
        if (i == null) {
            i = recorder.paletteIndex(data.getAsString());
            paletteCache.put(data, i);
        }
        return i;
    }

    private boolean hasBlockEntity(Block block, Material material) {
        Boolean known = blockEntity.get(material);
        if (known == null) {
            known = block.getState(false) instanceof TileState;
            blockEntity.put(material, known);
        }
        return known;
    }
}
