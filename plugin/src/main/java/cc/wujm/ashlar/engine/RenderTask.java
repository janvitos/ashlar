// SPDX-License-Identifier: AGPL-3.0-or-later
package cc.wujm.ashlar.engine;

import com.google.gson.JsonElement;
import com.google.gson.JsonNull;
import org.bukkit.World;

/** Budgeted world/state/color reads; region encoding and rendering finalized off-main. */
public final class RenderTask extends ReadTask {
    private int[] paletteArgb;
    private int paletteIndex;
    private boolean readDone;
    private RegionData data;
    private String worldName;

    public RenderTask(Region region, World world) { super(region, world); }

    @Override
    public boolean step(long deadline) {
        if (!readDone) {
            if (!super.step(deadline)) return false;
            readDone = true;
            paletteArgb = new int[paletteSize()];
            worldName = world().getName();
        }
        while (paletteIndex < paletteArgb.length) {
            if (System.nanoTime() >= deadline) return false;
            paletteArgb[paletteIndex] = MapColorResolver.resolve(paletteEntry(paletteIndex));
            paletteIndex++;
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

    public int[] paletteArgb() { return paletteArgb; }
    public String worldName() { return worldName; }
}
