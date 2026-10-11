// SPDX-License-Identifier: AGPL-3.0-or-later
package cc.wujm.ashlar.engine;

import cc.wujm.ashlar.journal.JournalExtras;
import cc.wujm.ashlar.journal.JournalRecorder;
import org.bukkit.Tag;
import org.bukkit.entity.Entity;
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
    private final JournalExtras extras = new JournalExtras();
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

    /** Sign text and decorative entity changes of this call (Step 17). */
    public JournalExtras extras() {
        return extras;
    }

    /** Claims {@code w} as this call's world; false (and the entry is dropped) when it is a second world. */
    private boolean claim(World w) {
        if (mixedWorlds) return false;
        if (world == null) world = w;
        else if (world != w) {
            mixedWorlds = true;
            return false;
        }
        return true;
    }

    /** Records a sign's text before this call first changes it (block write or text-only write). */
    public void signBefore(Block block) {
        if (recorder.overflowed() || !claim(block.getWorld())) return;
        SignSnapshot s = SignAccess.read(block);
        if (s != null) extras.signBefore(block.getX(), block.getY(), block.getZ(), s.json());
    }

    /** An entity this call spawned (undo removes it). */
    public void entitySpawned(Entity e, EntitySpec spec) {
        if (claim(e.getWorld())) extras.spawned(e.getUniqueId(), spec);
    }

    /** An entity this call removed (undo spawns it again). */
    public void entityRemoved(World w, EntitySpec spec) {
        if (claim(w)) extras.removed(spec);
    }

    /** An entity whose values this call changed in place (undo sets them back). */
    public void entityChanged(Entity e, EntitySpec before, EntitySpec after) {
        if (claim(e.getWorld())) extras.changed(e.getUniqueId(), before, after);
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
        if (recorder.overflowed() || !claim(block.getWorld())) return;
        long key = JournalRecorder.pack(block.getX(), block.getY(), block.getZ());
        if (recorder.touch(key, index(current)) && hasBlockEntity(block, current.getMaterial())) {
            // Sign text comes back on undo; other block-entity data (inventories) does not.
            if (Tag.ALL_SIGNS.isTagged(current.getMaterial())) signBefore(block);
            else recorder.addBlockEntityCell();
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
        for (long k : extras.signKeys()) {
            SignSnapshot s = SignAccess.read(world.getBlockAt(JournalRecorder.x(k), JournalRecorder.y(k), JournalRecorder.z(k)));
            extras.signAfter(k, s == null ? null : s.json());
        }
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
