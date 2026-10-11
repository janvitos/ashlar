// SPDX-License-Identifier: AGPL-3.0-or-later
package cc.wujm.ashlar.engine;

import java.util.Set;

/**
 * Step 18 surface rules on block state strings, pure Java: what {@code TOP_SOLID_IGNORE_DECOR}
 * looks through, and the height a player's feet rest at on a block ({@link WalkShapes} collision).
 */
public final class SurfaceRules {
    private SurfaceRules() {}

    /** Default for {@code snowLayers}: thin snow up to this many layers counts as decoration. */
    public static final int DEFAULT_SNOW_LAYERS = 3;
    /** Blocks a column scan steps through before it gives up and keeps the heightmap value. */
    public static final int MAX_SCAN = 64;

    private static final Set<String> SMALL = Set.of("lantern", "soul_lantern", "flower_pot", "sea_pickle", "end_rod",
            "lightning_rod", "chain", "iron_chain", "decorated_pot", "turtle_egg", "sniffer_egg", "frogspawn", "lily_pad", "light",
            "amethyst_cluster", "large_amethyst_bud", "medium_amethyst_bud", "small_amethyst_bud", "pointed_dripstone", "cobweb");

    /**
     * Decoration on top of the ground: no-collision blocks (plants, torches, signs, rails, buttons,
     * plates, vines), carpet, snow up to {@code snowLayers} layers, lanterns, candles, pots, heads.
     */
    public static boolean decor(String state, int snowLayers) {
        String m = WalkShapes.material(state);
        if (m.equals("snow")) return layers(state) <= snowLayers;
        if (m.endsWith("carpet") || m.endsWith("candle") || m.startsWith("potted_") || m.endsWith("_head") || m.endsWith("_skull")
                || m.endsWith("_wall_head") || m.endsWith("_wall_skull") || SMALL.contains(m)) {
            return true;
        }
        WalkShapes.Kind k = WalkShapes.of(state, false).kind();
        return k == WalkShapes.Kind.NONE || k == WalkShapes.Kind.LADDER;
    }

    static boolean leaves(String m) {
        return m.endsWith("_leaves");
    }

    /** True when {@code TOP_SOLID_IGNORE_DECOR} keeps scanning down past this block (air, decoration, leaves). */
    public static boolean skip(String state, int snowLayers) {
        return leaves(WalkShapes.material(state)) || decor(state, snowLayers);
    }

    /**
     * Where feet rest on this block, relative to its own y: its collision top (1 for a cube, 0.5 for
     * a bottom slab, 1.5 for a fence, 1/16 for carpet), 1 for the surface of water or lava, or
     * {@code NaN} when it has no collision (or is leaves) and the scan continues below.
     */
    public static double walkTop(String state) {
        if (leaves(WalkShapes.material(state))) return Double.NaN;
        WalkShapes.Shape s = WalkShapes.of(state, false);
        return switch (s.kind()) {
            case NONE, LADDER -> Double.NaN;
            case WATER, HAZARD -> 1.0;
            default -> s.collides() ? s.top() : Double.NaN;
        };
    }

    private static int layers(String state) {
        String v = WalkShapes.properties(state).get("layers");
        try {
            return v == null ? 1 : Integer.parseInt(v);
        } catch (NumberFormatException e) {
            return 1;
        }
    }
}
