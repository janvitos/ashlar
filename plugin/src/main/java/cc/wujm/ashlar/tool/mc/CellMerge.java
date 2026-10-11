// SPDX-License-Identifier: AGPL-3.0-or-later
package cc.wujm.ashlar.tool.mc;

import cc.wujm.ashlar.engine.LiquidBlocks;
import cc.wujm.ashlar.engine.Region;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Set;

/**
 * Turns a {@link CellSet} into fill boxes and build parts, pure Java and deterministic:
 * <ol>
 *   <li>each cell gets a phase: solids first, then air, then late blocks that need something to
 *       stand on or hang from (snow layers, carpets, torches, lanterns, plants, rails, ...);</li>
 *   <li>a greedy 3D merge grows a box from the lowest unused cell (sorted y, z, x): along x, then
 *       whole rows along z, then whole layers along y, while every cell matches its state;</li>
 *   <li>boxes are ordered by phase, then 128-block tile, then position, and packed into parts that
 *       each respect the per-operation limits (blocks, chunk envelope, flowing liquids, snapshot).</li>
 * </ol>
 */
final class CellMerge {
    private CellMerge() {}

    enum Phase { SOLID, AIR, LATE }

    static final int MAX_SPAN = 128;
    static final int TILE = 128;

    record Box(int x0, int y0, int z0, int x1, int y1, int z1, int id) {
        long volume() {
            return (long) (x1 - x0 + 1) * (y1 - y0 + 1) * (z1 - z0 + 1);
        }
    }

    record Limits(long blocks, int chunks, long liquids, long envelope) {}

    record Part(Phase phase, List<Box> boxes, Region envelope, long volume) {}

    private static final Set<String> AIR = Set.of("air", "cave_air", "void_air");
    private static final Set<String> LATE = Set.of("snow", "torch", "wall_torch", "soul_torch", "soul_wall_torch", "redstone_torch",
            "redstone_wall_torch", "copper_torch", "copper_wall_torch", "lantern", "soul_lantern", "light", "ladder", "vine", "lever",
            "redstone_wire", "repeater", "comparator", "tripwire", "tripwire_hook", "short_grass", "grass", "tall_grass", "fern",
            "large_fern", "dead_bush", "bush", "firefly_bush", "short_dry_grass", "tall_dry_grass", "dandelion", "poppy", "blue_orchid",
            "allium", "azure_bluet", "oxeye_daisy", "cornflower", "lily_of_the_valley", "wither_rose", "sunflower", "lilac", "rose_bush",
            "peony", "torchflower", "pitcher_plant", "open_eyeblossom", "closed_eyeblossom", "wheat", "carrots", "potatoes", "beetroots",
            "sugar_cane", "nether_wart", "brown_mushroom", "red_mushroom", "crimson_fungus", "warped_fungus", "crimson_roots",
            "warped_roots", "nether_sprouts", "hanging_roots", "glow_lichen", "sculk_vein", "spore_blossom", "pink_petals", "wildflowers",
            "leaf_litter", "cactus", "bamboo", "kelp", "kelp_plant", "seagrass", "tall_seagrass", "sea_pickle", "lily_pad", "cocoa",
            "flower_pot", "end_rod", "chain", "iron_chain", "bell", "scaffolding", "pointed_dripstone", "small_dripleaf", "big_dripleaf",
            "big_dripleaf_stem", "cave_vines", "cave_vines_plant", "weeping_vines", "weeping_vines_plant", "twisting_vines",
            "twisting_vines_plant", "frogspawn", "resin_clump", "item_frame", "glow_item_frame");

    static Phase phase(String state) {
        String m = material(state);
        if (AIR.contains(m)) return Phase.AIR;
        if (LATE.contains(m) || m.endsWith("carpet") || m.endsWith("_sapling")
                || m.endsWith("_tulip") || m.endsWith("_sign") || m.endsWith("_banner") || m.endsWith("_button")
                || m.endsWith("pressure_plate") || m.endsWith("rail") || m.endsWith("_coral") || m.endsWith("_coral_fan")
                || m.endsWith("_coral_wall_fan") || m.endsWith("candle") || m.endsWith("_door") || m.endsWith("_bed")
                || m.endsWith("_head") || m.endsWith("_skull") || m.endsWith("_trapdoor") || m.endsWith("_lantern")
                || m.endsWith("_torch") || m.endsWith("amethyst_bud") || m.equals("amethyst_cluster") || m.endsWith("_vines")
                || m.endsWith("_roots") || m.endsWith("_propagule") || m.equals("mangrove_propagule")) {
            return Phase.LATE;
        }
        return Phase.SOLID;
    }

    /** Greedy merge of every cell into boxes of one state each; boxes never exceed {@code maxVolume} or {@link #MAX_SPAN} in x/z. */
    static List<Box> merge(CellSet s, long maxVolume) {
        int n = s.size();
        boolean[] used = new boolean[n];
        List<Box> out = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            if (used[i]) continue;
            int id = s.ids[i];
            long k = s.keys[i];
            int x0 = CellSet.x(k), y0 = CellSet.y(k), z0 = CellSet.z(k);
            int len = 1;
            while (i + len < n && len < MAX_SPAN && len < maxVolume && s.keys[i + len] == k + len && s.ids[i + len] == id && !used[i + len]) {
                len++;
            }
            int x1 = x0 + len - 1;
            int z1 = z0;
            while (z1 - z0 + 1 < MAX_SPAN && (long) len * (z1 - z0 + 2) <= maxVolume && row(s, used, x0, len, y0, z1 + 1, id) >= 0) z1++;
            int y1 = y0;
            long layer = (long) len * (z1 - z0 + 1);
            while (layer * (y1 - y0 + 2) <= maxVolume && layerFree(s, used, x0, len, y1 + 1, z0, z1, id)) y1++;
            for (int y = y0; y <= y1; y++) {
                for (int z = z0; z <= z1; z++) {
                    int r = s.find(CellSet.key(x0, y, z));
                    for (int j = 0; j < len; j++) used[r + j] = true;
                }
            }
            out.add(new Box(x0, y0, z0, x1, y1, z1, id));
        }
        return out;
    }

    /** Start index of a free, matching row of {@code len} cells from (x0, y, z), or -1. */
    private static int row(CellSet s, boolean[] used, int x0, int len, int y, int z, int id) {
        long k = CellSet.key(x0, y, z);
        int r = s.find(k);
        if (r < 0 || r + len > s.size()) return -1;
        for (int j = 0; j < len; j++) if (s.keys[r + j] != k + j || s.ids[r + j] != id || used[r + j]) return -1;
        return r;
    }

    private static boolean layerFree(CellSet s, boolean[] used, int x0, int len, int y, int z0, int z1, int id) {
        for (int z = z0; z <= z1; z++) if (row(s, used, x0, len, y, z, id) < 0) return false;
        return true;
    }

    /** Orders boxes by phase, tile and position, then packs them into parts within {@code limits}. */
    static List<Part> pack(List<Box> boxes, List<String> palette, Limits limits) {
        List<Box> sorted = new ArrayList<>(boxes);
        sorted.sort(Comparator.<Box>comparingInt(b -> phase(palette.get(b.id())).ordinal())
                .thenComparingInt(b -> Math.floorDiv(b.x0(), TILE))
                .thenComparingInt(b -> Math.floorDiv(b.z0(), TILE))
                .thenComparingInt(Box::y0).thenComparingInt(Box::z0).thenComparingInt(Box::x0));
        List<Part> parts = new ArrayList<>();
        List<Box> current = new ArrayList<>();
        Phase phase = null;
        int[] env = null;
        long volume = 0, liquids = 0;
        for (Box b : sorted) {
            Phase p = phase(palette.get(b.id()));
            boolean liquid = LiquidBlocks.isFlowableBlockString(palette.get(b.id()));
            int[] grown = env == null ? new int[]{b.x0(), b.y0(), b.z0(), b.x1(), b.y1(), b.z1()} : new int[]{
                    Math.min(env[0], b.x0()), Math.min(env[1], b.y0()), Math.min(env[2], b.z0()),
                    Math.max(env[3], b.x1()), Math.max(env[4], b.y1()), Math.max(env[5], b.z1())};
            boolean fits = p == phase && volume + b.volume() <= limits.blocks() && chunks(grown) <= limits.chunks()
                    && (!liquid || liquids + b.volume() <= limits.liquids()) && envelope(grown) <= limits.envelope();
            if (!current.isEmpty() && !fits) {
                parts.add(part(phase, current, env, volume));
                current = new ArrayList<>();
                env = null;
                volume = 0;
                liquids = 0;
                grown = new int[]{b.x0(), b.y0(), b.z0(), b.x1(), b.y1(), b.z1()};
            }
            phase = p;
            current.add(b);
            env = grown;
            volume += b.volume();
            if (liquid) liquids += b.volume();
        }
        if (!current.isEmpty()) parts.add(part(phase, current, env, volume));
        return parts;
    }

    private static Part part(Phase phase, List<Box> boxes, int[] e, long volume) {
        return new Part(phase, List.copyOf(boxes), new Region(e[0], e[1], e[2], e[3], e[4], e[5]), volume);
    }

    static long chunks(int[] e) {
        return (long) (Math.floorDiv(e[3], 16) - Math.floorDiv(e[0], 16) + 1) * (Math.floorDiv(e[5], 16) - Math.floorDiv(e[2], 16) + 1);
    }

    static long envelope(int[] e) {
        return (long) (e[3] - e[0] + 1) * (e[4] - e[1] + 1) * (e[5] - e[2] + 1);
    }

    /** Material name without namespace or properties. */
    static String material(String state) {
        int i = state.indexOf('[');
        String m = i < 0 ? state : state.substring(0, i);
        int c = m.indexOf(':');
        return c < 0 ? m : m.substring(c + 1);
    }
}
