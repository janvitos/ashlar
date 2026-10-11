// SPDX-License-Identifier: AGPL-3.0-or-later
package cc.wujm.ashlar.engine;

import org.bukkit.HeightMap;
import org.bukkit.World;

/** Main-thread column reads shared by the heightmap tasks (Step 18). */
final class SurfaceReader {
    private SurfaceReader() {}

    /**
     * The surface block's own y for {@code heightMap}, stepping down one block if the reported y is
     * air. With {@code decorSnow >= 0} ({@code TOP_SOLID_IGNORE_DECOR}) it then keeps stepping down
     * through decoration, leaves and air, at most {@link SurfaceRules#MAX_SCAN} blocks.
     */
    static int surfaceY(World world, int x, int z, HeightMap heightMap, int decorSnow) {
        int min = world.getMinHeight();
        int y = world.getHighestBlockYAt(x, z, heightMap);
        if (y > min && world.getBlockAt(x, y, z).getType().isAir()) y--;
        if (decorSnow < 0) return y;
        // Thin snow is not motion-blocking, so the scan starts at the highest non-air block instead.
        int top = world.getHighestBlockYAt(x, z, HeightMap.WORLD_SURFACE);
        for (int i = 0; i < SurfaceRules.MAX_SCAN && top > min; i++, top--) {
            if (!SurfaceRules.skip(world.getBlockAt(x, top, z).getBlockData().getAsString(), decorSnow)) return top;
        }
        return y;
    }

    /** Feet y of a player standing on the column's top collision (leaves ignored); see {@link SurfaceRules#walkTop}. */
    static double walkY(World world, int x, int z) {
        int min = world.getMinHeight();
        int y = world.getHighestBlockYAt(x, z, HeightMap.WORLD_SURFACE);
        for (int i = 0; i < SurfaceRules.MAX_SCAN && y >= min; i++, y--) {
            double top = SurfaceRules.walkTop(world.getBlockAt(x, y, z).getBlockData().getAsString());
            if (!Double.isNaN(top)) return y + top;
        }
        return surfaceY(world, x, z, HeightMap.MOTION_BLOCKING_NO_LEAVES, -1) + 1;
    }
}
