// SPDX-License-Identifier: AGPL-3.0-or-later
package cc.wujm.ashlar.engine;

import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

/**
 * Player collision of one block state for {@link Walker}, pure Java. Each state is one vertical
 * interval {@code [bottom, top]} inside its cell (a fence or wall reaches 1.5, into the cell above)
 * plus what it means for movement. Heights follow vanilla collision boxes: snow {@code (layers-1)/8},
 * carpet 1/16, bottom slab 0.5, bed 9/16, farmland and dirt path 15/16, soul sand and mud 7/8,
 * fences, walls and closed fence gates 1.5. Unknown blocks count as full cubes (the safe side).
 */
public final class WalkShapes {
    private WalkShapes() {}

    public enum Kind {
        /** No collision: air, plants, torches, signs, rails, open doors. */
        NONE,
        /** An ordinary collision box. */
        SOLID,
        /** A bottom-half stair: 0.5 at the front, 1.0 at the back ({@code facing} side). */
        STAIR,
        /** A closed door or fence gate: blocks passage, never stood on. */
        DOOR,
        /** Ladder, vines or scaffolding: no collision, climbable. */
        LADDER,
        /** Water, bubble columns, kelp, seagrass: swimmable when allowed. */
        WATER,
        /** Lava, fire, berry bushes, powder snow: never entered. */
        HAZARD
    }

    /**
     * @param standable a player may stand on {@code top}
     * @param hurts     standing on it hurts (magma, campfire, cactus)
     * @param facing    stair facing (the high side), else null
     */
    public record Shape(Kind kind, double bottom, double top, boolean standable, boolean hurts, String facing) {
        public boolean collides() {
            return top > bottom;
        }
    }

    static final Shape NONE = new Shape(Kind.NONE, 0, 0, false, false, null);
    static final Shape CUBE = solid(0, 1);

    private static Shape solid(double bottom, double top) {
        return new Shape(Kind.SOLID, bottom, top, true, false, null);
    }

    private static final Set<String> PASSABLE = Set.of("air", "cave_air", "void_air", "light", "structure_void",
            "short_grass", "grass", "tall_grass", "fern", "large_fern", "dead_bush", "bush", "firefly_bush", "short_dry_grass",
            "tall_dry_grass", "dandelion", "poppy", "blue_orchid", "allium", "azure_bluet", "oxeye_daisy", "cornflower",
            "lily_of_the_valley", "wither_rose", "sunflower", "lilac", "rose_bush", "peony", "torchflower", "torchflower_crop",
            "pitcher_plant", "pitcher_crop", "open_eyeblossom", "closed_eyeblossom", "wheat", "carrots", "potatoes", "beetroots",
            "melon_stem", "pumpkin_stem", "attached_melon_stem", "attached_pumpkin_stem", "sugar_cane", "nether_wart",
            "brown_mushroom", "red_mushroom", "crimson_fungus", "warped_fungus", "crimson_roots", "warped_roots",
            "nether_sprouts", "hanging_roots", "glow_lichen", "sculk_vein", "spore_blossom", "pink_petals", "wildflowers",
            "leaf_litter", "redstone_wire", "tripwire", "tripwire_hook", "lever", "cobweb", "nether_portal", "end_portal",
            "frogspawn", "big_dripleaf_stem", "small_dripleaf", "resin_clump");
    private static final Set<String> CLIMB = Set.of("ladder", "vine", "scaffolding", "weeping_vines", "weeping_vines_plant",
            "twisting_vines", "twisting_vines_plant", "cave_vines", "cave_vines_plant");
    private static final Set<String> WATER = Set.of("water", "bubble_column", "kelp", "kelp_plant", "seagrass", "tall_seagrass");
    private static final Set<String> HAZARD = Set.of("lava", "fire", "soul_fire", "sweet_berry_bush", "powder_snow");
    private static final Map<String, Double> LOW = Map.ofEntries(
            Map.entry("farmland", 15 / 16.0), Map.entry("dirt_path", 15 / 16.0), Map.entry("soul_sand", 14 / 16.0),
            Map.entry("mud", 14 / 16.0), Map.entry("honey_block", 15 / 16.0), Map.entry("chest", 14 / 16.0),
            Map.entry("trapped_chest", 14 / 16.0), Map.entry("ender_chest", 14 / 16.0), Map.entry("enchanting_table", 12 / 16.0),
            Map.entry("daylight_detector", 6 / 16.0), Map.entry("stonecutter", 9 / 16.0), Map.entry("repeater", 2 / 16.0),
            Map.entry("comparator", 2 / 16.0), Map.entry("lily_pad", 1.5 / 16.0), Map.entry("end_portal_frame", 13 / 16.0),
            Map.entry("lantern", 9 / 16.0), Map.entry("soul_lantern", 9 / 16.0), Map.entry("flower_pot", 6 / 16.0),
            Map.entry("cake", 8 / 16.0), Map.entry("conduit", 11 / 16.0), Map.entry("sea_pickle", 6 / 16.0));

    public static Shape of(String state, boolean anyDoor) {
        String m = material(state);
        if (PASSABLE.contains(m) || m.endsWith("_sapling") || m.endsWith("_tulip") || m.contains("torch") || m.endsWith("_sign")
                || m.endsWith("_banner") || m.endsWith("_button") || m.endsWith("pressure_plate") || m.endsWith("rail")
                || m.endsWith("_coral_fan") || m.endsWith("_coral")) {
            return NONE;
        }
        if (CLIMB.contains(m)) return new Shape(Kind.LADDER, 0, 0, false, false, null);
        if (WATER.contains(m)) return new Shape(Kind.WATER, 0, 0, false, false, null);
        if (HAZARD.contains(m)) return new Shape(Kind.HAZARD, 0, 0, false, false, null);
        Map<String, String> p = properties(state);
        if (m.equals("snow")) {
            int layers = parseInt(p.get("layers"), 1);
            return layers <= 1 ? NONE : solid(0, (layers - 1) / 8.0);
        }
        if (m.endsWith("carpet")) return solid(0, 1 / 16.0);
        if (m.endsWith("_slab")) {
            return switch (p.getOrDefault("type", "bottom")) {
                case "top" -> solid(.5, 1);
                case "double" -> CUBE;
                default -> solid(0, .5);
            };
        }
        if (m.endsWith("_stairs")) {
            if ("top".equals(p.get("half"))) return CUBE;
            return new Shape(Kind.STAIR, 0, 1, true, false, p.getOrDefault("facing", "north"));
        }
        if (m.endsWith("_door")) {
            return "true".equals(p.get("open")) || anyDoor ? NONE : new Shape(Kind.DOOR, 0, 1, false, false, null);
        }
        if (m.endsWith("_fence_gate")) {
            return "true".equals(p.get("open")) || anyDoor ? NONE : new Shape(Kind.DOOR, 0, 1.5, false, false, null);
        }
        if (m.endsWith("_fence") || m.endsWith("_wall")) return solid(0, 1.5);
        if (m.endsWith("_trapdoor")) {
            if ("true".equals(p.get("open"))) return NONE;
            return "top".equals(p.get("half")) ? solid(13 / 16.0, 1) : solid(0, 3 / 16.0);
        }
        if (m.endsWith("_bed")) return solid(0, 9 / 16.0);
        if (m.endsWith("_head") || m.endsWith("_skull")) return solid(0, .5);
        if (m.endsWith("candle")) return solid(0, 6 / 16.0);
        if (m.equals("magma_block")) return new Shape(Kind.SOLID, 0, 1, true, true, null);
        if (m.equals("campfire") || m.equals("soul_campfire")) return new Shape(Kind.SOLID, 0, 7 / 16.0, true, true, null);
        if (m.equals("cactus") || m.equals("pointed_dripstone")) return new Shape(Kind.SOLID, 0, 15 / 16.0, false, true, null);
        Double low = LOW.get(m);
        if (low != null) return solid(0, low);
        return CUBE;
    }

    static String material(String state) {
        int i = state.indexOf('[');
        String m = i < 0 ? state : state.substring(0, i);
        int c = m.indexOf(':');
        return (c < 0 ? m : m.substring(c + 1)).toLowerCase(Locale.ROOT);
    }

    static Map<String, String> properties(String state) {
        Map<String, String> p = new TreeMap<>();
        int i = state.indexOf('[');
        if (i < 0 || !state.endsWith("]")) return p;
        for (String kv : state.substring(i + 1, state.length() - 1).split(",")) {
            int e = kv.indexOf('=');
            if (e > 0) p.put(kv.substring(0, e).trim(), kv.substring(e + 1).trim());
        }
        return p;
    }

    private static int parseInt(String s, int fallback) {
        try {
            return s == null ? fallback : Integer.parseInt(s);
        } catch (NumberFormatException e) {
            return fallback;
        }
    }
}
