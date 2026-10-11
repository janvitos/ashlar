// SPDX-License-Identifier: AGPL-3.0-or-later
package cc.wujm.ashlar.engine;

import com.google.gson.JsonElement;
import com.google.gson.JsonNull;
import org.bukkit.World;
import org.bukkit.entity.Entity;
import org.bukkit.entity.ItemFrame;
import org.bukkit.entity.Painting;
import org.bukkit.util.BoundingBox;

import java.util.ArrayList;
import java.util.List;

/** Budgeted world/state/color reads; region encoding and rendering finalized off-main. */
public final class RenderTask extends ReadTask {
    private int[] paletteArgb;
    private int paletteIndex;
    private boolean readDone;
    private RegionData data;
    private String worldName;
    private final boolean angled;
    private int[][] faceColors;
    private List<cc.wujm.ashlar.render.ShapeRenderer.Placeholder> placeholders;
    private boolean entitiesUnloaded;
    private int waits;

    public RenderTask(Region region, World world) { this(region, world, false); }

    /** {@code angled}: also resolve per-face colours and read painting/item-frame placeholders (Step 20). */
    public RenderTask(Region region, World world, boolean angled) {
        super(region, world);
        this.angled = angled;
    }

    @Override
    public boolean step(long deadline) {
        if (!readDone) {
            if (!super.step(deadline)) return false;
            readDone = true;
            paletteArgb = new int[paletteSize()];
            if (angled) faceColors = new int[paletteSize()][];
            worldName = world().getName();
        }
        while (paletteIndex < paletteArgb.length) {
            if (System.nanoTime() >= deadline) return false;
            paletteArgb[paletteIndex] = MapColorResolver.resolve(paletteEntry(paletteIndex));
            if (angled) {
                try {
                    faceColors[paletteIndex] = cc.wujm.ashlar.render.BlockShapes.faceColors(paletteEntry(paletteIndex), paletteArgb[paletteIndex], MapColorResolver::resolve);
                } catch (IllegalArgumentException e) {
                    faceColors[paletteIndex] = null; // a state this version cannot parse keeps one colour
                }
            }
            paletteIndex++;
        }
        if (angled && placeholders == null) {
            if (!EntityAccess.entitiesLoaded(world(), region()) && waits++ < 20) return false;
            entitiesUnloaded = !EntityAccess.entitiesLoaded(world(), region());
            placeholders = readPlaceholders();
        }
        return true;
    }

    @Override
    public JsonElement buildResult(long queuedMs, long elapsedMs) { return JsonNull.INSTANCE; }

    /** Call off-main only after task completion and its completion-future memory barrier. */
    @Override
    public RegionData regionData() {
        if (data == null) data = finishReadData();
        return data;
    }

    /** Cells of paintings and item frames in the region, relative to its minimum corner. */
    private List<cc.wujm.ashlar.render.ShapeRenderer.Placeholder> readPlaceholders() {
        Region r = region();
        List<cc.wujm.ashlar.render.ShapeRenderer.Placeholder> out = new ArrayList<>();
        BoundingBox bb = new BoundingBox(r.minX(), r.minY(), r.minZ(), r.maxX() + 1, r.maxY() + 1, r.maxZ() + 1);
        for (Entity e : world().getNearbyEntities(bb, x -> x instanceof Painting || x instanceof ItemFrame)) {
            String type = e instanceof Painting ? "painting" : "item_frame";
            String facing = e.getFacing().name().toLowerCase(java.util.Locale.ROOT);
            BoundingBox b = e.getBoundingBox();
            int x0 = (int) Math.floor(b.getMinX() + 1e-3), y0 = (int) Math.floor(b.getMinY() + 1e-3), z0 = (int) Math.floor(b.getMinZ() + 1e-3);
            int x1 = (int) Math.floor(b.getMaxX() - 1e-3), y1 = (int) Math.floor(b.getMaxY() - 1e-3), z1 = (int) Math.floor(b.getMaxZ() - 1e-3);
            for (int x = x0; x <= x1; x++) for (int y = y0; y <= y1; y++) for (int z = z0; z <= z1; z++) {
                if (x >= r.minX() && x <= r.maxX() && y >= r.minY() && y <= r.maxY() && z >= r.minZ() && z <= r.maxZ() && out.size() < 4096) out.add(new cc.wujm.ashlar.render.ShapeRenderer.Placeholder(x - r.minX(), y - r.minY(), z - r.minZ(), type, facing));
            }
        }
        return out;
    }

    public int[] paletteArgb() { return paletteArgb; }
    public int[][] faceColors() { return faceColors; }
    public List<cc.wujm.ashlar.render.ShapeRenderer.Placeholder> placeholders() { return placeholders; }
    public boolean entitiesUnloaded() { return entitiesUnloaded; }
    public String worldName() { return worldName; }
}
