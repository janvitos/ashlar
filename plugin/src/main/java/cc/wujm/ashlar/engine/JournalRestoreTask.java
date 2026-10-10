// SPDX-License-Identifier: AGPL-3.0-or-later
package cc.wujm.ashlar.engine;

import cc.wujm.ashlar.journal.JournalCells;
import cc.wujm.ashlar.journal.JournalRecorder;
import cc.wujm.ashlar.journal.UndoRules;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.TileState;
import org.bukkit.block.data.BlockData;

import java.util.EnumMap;
import java.util.Map;

/**
 * Undoes one journal entry cell by cell ({@link UndoRules}): writes each original state back with
 * {@code setBlockData(old,false)} where allowed and reports the rest. No connection pass: the
 * journal also holds the neighbour shapes its call changed, so exact states come back. With
 * {@code dryRun} it only counts. Writes are themselves journalled through the attached capture.
 */
public final class JournalRestoreTask extends BuildTask {

    private static final int DEADLINE_CHECK_INTERVAL = 256;

    private final World world;
    private final JournalCells cells;
    private final BlockData[] palette;
    private final UndoRules.Mode mode;
    private final boolean allowBlockEntityReplacement;
    private final boolean dryRun;
    private final int maxSamples;
    private final Map<Material, Boolean> blockEntity = new EnumMap<>(Material.class);
    private final JsonArray samples = new JsonArray();
    private int cursor;
    private long restored, overwritten, alreadyOriginal, changedConflicts, blockEntityConflicts;
    private int minX = Integer.MAX_VALUE, minY = Integer.MAX_VALUE, minZ = Integer.MAX_VALUE;
    private int maxX = Integer.MIN_VALUE, maxY = Integer.MIN_VALUE, maxZ = Integer.MIN_VALUE;

    public JournalRestoreTask(Region bounds, World world, JournalCells cells, BlockData[] palette, UndoRules.Mode mode,
            boolean allowBlockEntityReplacement, boolean dryRun, int maxSamples) {
        super(bounds);
        this.world = world;
        this.cells = cells;
        this.palette = palette;
        this.mode = mode;
        this.allowBlockEntityReplacement = allowBlockEntityReplacement;
        this.dryRun = dryRun;
        this.maxSamples = maxSamples;
    }

    @Override
    public World world() {
        return world;
    }

    @Override
    public long volume() {
        return cells.size();
    }

    @Override
    public boolean step(long deadlineNanos) {
        int n = cells.size();
        while (cursor < n) {
            long key = cells.pos()[cursor];
            int x = JournalRecorder.x(key), y = JournalRecorder.y(key), z = JournalRecorder.z(key);
            Block b = world.getBlockAt(x, y, z);
            BlockData live = b.getBlockData();
            BlockData old = palette[cells.oldState()[cursor]];
            BlockData written = palette[cells.newState()[cursor]];
            boolean isOld = live.equals(old);
            boolean isNew = live.equals(written);
            boolean entity = !isOld && (isNew || mode == UndoRules.Mode.FORCE) && hasBlockEntity(b, live.getMaterial());
            switch (UndoRules.decide(isOld, isNew, entity, mode, allowBlockEntityReplacement)) {
                case ALREADY_ORIGINAL -> alreadyOriginal++;
                case RESTORE -> write(b, live, old);
                case RESTORE_OVERWRITE -> {
                    overwritten++;
                    write(b, live, old);
                }
                case CONFLICT_CHANGED -> {
                    changedConflicts++;
                    conflict(x, y, z, "changed since", written, live);
                }
                case CONFLICT_BLOCK_ENTITY -> {
                    blockEntityConflicts++;
                    conflict(x, y, z, "block entity", written, live);
                }
            }
            cursor++;
            advance(1);
            if (cursor % DEADLINE_CHECK_INTERVAL == 0 && cursor < n && System.nanoTime() >= deadlineNanos) {
                return false;
            }
        }
        return true;
    }

    private void write(Block b, BlockData live, BlockData old) {
        restored++;
        if (dryRun) return;
        JournalCapture j = journal();
        if (j != null) j.before(b, live);
        b.setBlockData(old, false);
        addChanged(1);
    }

    private void conflict(int x, int y, int z, String kind, BlockData expected, BlockData live) {
        minX = Math.min(minX, x); minY = Math.min(minY, y); minZ = Math.min(minZ, z);
        maxX = Math.max(maxX, x); maxY = Math.max(maxY, y); maxZ = Math.max(maxZ, z);
        if (samples.size() >= maxSamples) return;
        JsonObject s = new JsonObject();
        JsonArray pos = new JsonArray();
        pos.add(x);
        pos.add(y);
        pos.add(z);
        s.add("pos", pos);
        s.addProperty("kind", kind);
        s.addProperty("journalled", expected.getAsString());
        s.addProperty("live", live.getAsString());
        samples.add(s);
    }

    private boolean hasBlockEntity(Block block, Material material) {
        Boolean known = blockEntity.get(material);
        if (known == null) {
            known = block.getState(false) instanceof TileState;
            blockEntity.put(material, known);
        }
        return known;
    }

    @Override
    public JsonElement buildResult(long queuedMs, long elapsedMs) {
        JsonObject r = new JsonObject();
        r.addProperty("cells", cells.size());
        r.addProperty("restored", restored);
        r.addProperty("overwritten", overwritten);
        r.addProperty("alreadyOriginal", alreadyOriginal);
        r.addProperty("conflictsChanged", changedConflicts);
        r.addProperty("conflictsBlockEntity", blockEntityConflicts);
        if (changedConflicts + blockEntityConflicts > 0) {
            JsonArray box = new JsonArray();
            for (int v : new int[]{minX, minY, minZ, maxX, maxY, maxZ}) box.add(v);
            r.add("conflictBounds", box);
        }
        r.add("samples", samples);
        r.addProperty("dryRun", dryRun);
        r.addProperty("elapsedMs", elapsedMs);
        return r;
    }
}
