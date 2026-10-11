// SPDX-License-Identifier: AGPL-3.0-or-later
package cc.wujm.ashlar.engine;

import java.util.Set;

/**
 * Step 18 hostile-spawn and light-spread rules on block state strings, pure Java. A spawn space is a
 * feet cell and the head cell above it that a mob fits into (no collision, no fluid, no rails or
 * redstone parts in the feet cell) over a floor with a full, sturdy top face (not glass, leaves,
 * bottom slabs, carpet, fences, chests). Light spreads through every cell that is not an opaque
 * full cube. Unknown blocks count as opaque full cubes, like {@link WalkShapes}.
 */
public final class LightRules {
    private LightRules() {}

    private static final Set<String> CLEAR_FULL = Set.of("barrier", "beacon", "spawner", "trial_spawner", "vault", "ice", "frosted_ice",
            "slime_block", "honey_block", "glowstone", "sea_lantern", "mangrove_roots", "iron_bars", "chain", "iron_chain", "conduit",
            "copper_grate", "light", "structure_void", "hopper");
    private static final Set<String> FEET_BLOCKERS = Set.of("redstone_wire", "lever", "tripwire_hook", "redstone_torch",
            "redstone_wall_torch", "nether_portal", "end_portal", "end_gateway");

    /** Lets light through: anything but an opaque full cube (glass, leaves, slabs, stairs, plants, air...). */
    public static boolean transmits(String state) {
        String m = WalkShapes.material(state);
        if (clear(m)) return true;
        WalkShapes.Shape s = WalkShapes.of(state, false);
        return switch (s.kind()) {
            case SOLID -> s.bottom() > 0 || s.top() < 1 || m.endsWith("_slab") || m.endsWith("_stairs") || m.endsWith("_fence")
                    || m.endsWith("_wall") || m.endsWith("_trapdoor") || m.endsWith("_bed");
            default -> true;
        };
    }

    private static boolean clear(String m) {
        return m.endsWith("glass") || m.endsWith("_pane") || m.endsWith("_leaves") || m.endsWith("_grate") || CLEAR_FULL.contains(m);
    }

    /** A floor mobs spawn on: a full, sturdy, non-transparent top face (top slabs and upside-down stairs count; soul sand and mud too). */
    public static boolean floor(String state) {
        String m = WalkShapes.material(state);
        if (clear(m) || m.equals("magma_block") || m.endsWith("_fence") || m.endsWith("_wall") || m.endsWith("_fence_gate")) return false;
        if (m.equals("soul_sand") || m.equals("mud")) return true;
        WalkShapes.Shape s = WalkShapes.of(state, false);
        return s.kind() == WalkShapes.Kind.SOLID && s.top() == 1.0 && !s.hurts();
    }

    /** The feet cell of a spawn space. */
    public static boolean feet(String state) {
        String m = WalkShapes.material(state);
        if (m.endsWith("rail") || m.endsWith("_button") || m.endsWith("pressure_plate") || FEET_BLOCKERS.contains(m)) return false;
        return head(state);
    }

    /** The head cell of a spawn space: no collision and no water. */
    public static boolean head(String state) {
        if (state.contains("waterlogged=true")) return false;
        String m = WalkShapes.material(state);
        WalkShapes.Kind k = WalkShapes.of(state, false).kind();
        return k == WalkShapes.Kind.NONE || (k == WalkShapes.Kind.LADDER && m.contains("vine"));
    }

    /** A ceiling a hanging light can hang from: any block with collision. */
    public static boolean ceiling(String state) {
        return WalkShapes.of(state, false).collides();
    }
}
