// SPDX-License-Identifier: AGPL-3.0-or-later
package cc.wujm.ashlar.engine;

import java.util.ArrayList;
import java.util.BitSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Composition checks behind {@code mc_diff} anomalies "composition" and the advice section of the
 * {@code mc_build} reply, pure Java over block state strings. They catch mistakes a player notices
 * although the physics checks pass: railings players slip through, lamps and objects at head height,
 * blocked doorways, sealed rooms, revision leftovers and light from invisible blocks. Each part is a
 * read box ({@code outer}) around the cells judged ({@code inner}); rules look at most
 * {@link #MARGIN} blocks around a cell, so callers read that much margin. Cells outside the read box
 * are unknown and never cause a hit. Findings are advice: a build may break a rule on purpose.
 */
public final class CompositionScan {

    /** Neighbourhood the rules read around a judged cell (the hidden-light search radius). */
    public static final int MARGIN = 6;

    /** Rule ids in report order. */
    public static final List<String> RULES = List.of("diagonal-rail", "rail-gap", "lone-post", "fence-clump", "head-height",
            "low-lamp", "blocked-door", "sealed-room", "flowing-liquid", "floating-leaves", "hidden-light");

    private static final String[] SIDES = {"north", "east", "south", "west"};
    private static final int[][] SIDE_STEP = {{0, -1}, {1, 0}, {0, 1}, {-1, 0}};

    private static final Set<String> AIR = Set.of("air", "cave_air", "void_air", "light");
    private static final Set<String> VISIBLE_LIGHT = Set.of("lantern", "soul_lantern", "copper_lantern", "torch", "wall_torch",
            "soul_torch", "soul_wall_torch", "copper_torch", "copper_wall_torch", "redstone_torch", "redstone_wall_torch", "glowstone",
            "sea_lantern", "shroomlight", "ochre_froglight", "verdant_froglight", "pearlescent_froglight", "end_rod", "jack_o_lantern",
            "lava", "fire", "soul_fire", "beacon", "conduit", "magma_block", "crying_obsidian", "glow_lichen", "lava_cauldron",
            "respawn_anchor", "sculk_catalyst", "amethyst_cluster", "large_amethyst_bud", "enchanting_table", "ender_chest",
            "brewing_stand", "end_portal_frame", "nether_portal", "end_portal", "end_gateway", "sea_pickle", "glow_berries",
            "cave_vines", "cave_vines_plant", "light_block", "creaking_heart", "firefly_bush");
    private static final Set<String> LIT_WHEN_LIT = Set.of("campfire", "soul_campfire", "furnace", "blast_furnace", "smoker",
            "redstone_lamp", "redstone_ore", "deepslate_redstone_ore", "copper_bulb", "exposed_copper_bulb", "weathered_copper_bulb",
            "oxidized_copper_bulb", "waxed_copper_bulb", "waxed_exposed_copper_bulb", "waxed_weathered_copper_bulb",
            "waxed_oxidized_copper_bulb");
    private static final Set<String> NATURAL = Set.of("stone", "dirt", "grass_block", "sand", "red_sand", "gravel", "deepslate",
            "tuff", "granite", "diorite", "andesite", "calcite", "clay", "coarse_dirt", "rooted_dirt", "podzol", "mycelium", "mud",
            "moss_block", "sandstone", "red_sandstone", "terracotta", "netherrack", "basalt", "blackstone", "snow_block", "snow", "ice",
            "packed_ice", "blue_ice", "obsidian", "bedrock", "water", "lava", "kelp", "kelp_plant", "seagrass", "tall_seagrass",
            "bubble_column", "dripstone_block", "pointed_dripstone", "magma_block", "soul_sand", "soul_soil", "end_stone");
    private static final Set<String> FURNISHING = Set.of("chest", "trapped_chest", "barrel", "crafting_table", "furnace",
            "blast_furnace", "smoker", "bookshelf", "chiseled_bookshelf", "lectern", "anvil", "cauldron", "flower_pot", "loom",
            "cartography_table", "fletching_table", "smithing_table", "grindstone", "stonecutter", "brewing_stand", "bell",
            "enchanting_table", "jukebox", "note_block", "decorated_pot", "composter");

    private enum Family { NONE, FENCE, PANE, WALL }

    /** What one palette state means to the rules; computed once per state. */
    private record Cls(String material, Family family, boolean connected, boolean open, boolean passable, boolean floor,
            boolean collides, boolean air, boolean hangingLamp, boolean standingLamp, boolean bed, boolean seat, boolean table,
            boolean visibleLight, boolean lightBlock, boolean leaves, boolean flowing, boolean natural, boolean furnishing,
            boolean doorLower, int doorAxis, boolean low, boolean solid) {
    }

    private final Set<String> skip;
    private final int samples;
    private final Map<String, Long> counts = new LinkedHashMap<>();
    private final Map<String, List<AnomalyHit>> hits = new LinkedHashMap<>();
    private final Map<String, Cls> cache = new java.util.HashMap<>();

    // The part being scanned.
    private Region outer;
    private int dx, dz;
    private int[] grid;
    private List<String> palette;
    private Cls[] cls;

    /** {@code skip}: rule ids not to run (e.g. flowing-liquid for a build that placed flowing water). */
    public CompositionScan(Set<String> skip, int samples) {
        this.skip = skip;
        this.samples = samples;
    }

    /** Hit counts by rule, in {@link #RULES} order; rules without hits are absent. */
    public Map<String, Long> counts() {
        Map<String, Long> out = new LinkedHashMap<>();
        for (String r : RULES) if (counts.containsKey(r)) out.put(r, counts.get(r));
        return out;
    }

    /** The first {@code samples} hits per rule, in {@link #RULES} order, each in y,z,x order of the scanned parts. */
    public List<AnomalyHit> hits() {
        List<AnomalyHit> out = new ArrayList<>();
        for (String r : RULES) out.addAll(hits.getOrDefault(r, List.of()));
        return out;
    }

    public boolean isEmpty() {
        return counts.isEmpty();
    }

    /**
     * Judges the cells of {@code inner}. {@code grid} holds palette indexes over {@code outer} in
     * y,z,x order (the {@link DiffScanTask} layout); parts must arrive in y,z,x order to keep samples sorted.
     */
    public void scan(Region outer, Region inner, List<String> palette, int[] grid) {
        this.outer = outer;
        this.dx = outer.maxX() - outer.minX() + 1;
        this.dz = outer.maxZ() - outer.minZ() + 1;
        this.grid = grid;
        this.palette = palette;
        this.cls = new Cls[palette.size()];
        for (int i = 0; i < cls.length; i++) cls[i] = cache.computeIfAbsent(palette.get(i), CompositionScan::classify);
        for (int y = inner.minY(); y <= inner.maxY(); y++) {
            for (int z = inner.minZ(); z <= inner.maxZ(); z++) {
                for (int x = inner.minX(); x <= inner.maxX(); x++) {
                    int p = grid[offset(x, y, z)];
                    cell(cls[p], palette.get(p), x, y, z);
                }
            }
        }
        if (!skip.contains("sealed-room")) sealedRooms(inner, palette);
    }

    // ------------------------------------------------------------------ classification

    static Cls classify(String state) {
        String m = WalkShapes.material(state);
        Map<String, String> p = WalkShapes.properties(state);
        Family family = Family.NONE;
        if (m.endsWith("_fence")) family = Family.FENCE;
        else if (m.endsWith("_pane") || m.equals("iron_bars") || m.endsWith("_bars")) family = Family.PANE;
        else if (m.endsWith("_wall")) family = Family.WALL;
        boolean connected = false;
        for (String s : SIDES) {
            String v = p.get(s);
            if (v != null && !v.equals("false") && !v.equals("none")) connected = true;
        }
        WalkShapes.Shape closed = WalkShapes.of(state, false);
        WalkShapes.Shape anyDoor = WalkShapes.of(state, true);
        boolean open = closed.kind() == WalkShapes.Kind.NONE;
        boolean passable = switch (anyDoor.kind()) {
            case NONE, LADDER, WATER -> true;
            default -> m.endsWith("_trapdoor");
        };
        boolean lantern = m.equals("lantern") || m.equals("soul_lantern") || m.endsWith("copper_lantern");
        boolean hanging = lantern && "true".equals(p.get("hanging"));
        boolean visible = VISIBLE_LIGHT.contains(m) || m.endsWith("copper_lantern") || m.endsWith("_froglight")
                || (LIT_WHEN_LIT.contains(m) && "true".equals(p.get("lit")))
                || (m.endsWith("candle") && "true".equals(p.get("lit")))
                || (m.endsWith("candle_cake") && "true".equals(p.get("lit")));
        boolean flowing = (m.equals("water") || m.equals("lava")) && p.containsKey("level") && !"0".equals(p.get("level"));
        boolean doorLower = m.endsWith("_door") && "lower".equals(p.get("half"));
        String facing = p.getOrDefault("facing", "north");
        int axis = facing.equals("east") || facing.equals("west") ? 0 : 1;
        boolean seat = m.endsWith("_stairs") && !"top".equals(p.get("half"));
        return new Cls(m, family, connected, open, passable, closed.standable() && closed.collides(), closed.collides(),
                AIR.contains(m), hanging, lantern && !hanging, m.endsWith("_bed"), seat,
                family == Family.FENCE || family == Family.WALL || m.endsWith("_slab") || m.endsWith("pressure_plate") || m.endsWith("carpet"),
                visible, m.equals("light"), m.endsWith("_leaves"), flowing, NATURAL.contains(m),
                FURNISHING.contains(m) || m.endsWith("_bed") || m.endsWith("_door") || m.endsWith("_sign") || m.endsWith("carpet")
                        || m.endsWith("_banner") || m.endsWith("candle"),
                doorLower, axis, closed.kind() == WalkShapes.Kind.SOLID && closed.bottom() == 0 && closed.top() <= 0.5,
                closed.kind() == WalkShapes.Kind.SOLID && closed.bottom() == 0 && closed.top() >= 1);
    }

    // ------------------------------------------------------------------ grid access

    private int offset(int x, int y, int z) {
        return ((y - outer.minY()) * dz + (z - outer.minZ())) * dx + (x - outer.minX());
    }

    private boolean inside(int x, int y, int z) {
        return x >= outer.minX() && x <= outer.maxX() && y >= outer.minY() && y <= outer.maxY() && z >= outer.minZ() && z <= outer.maxZ();
    }

    /** The class at a position, or null outside the read box (unknown). */
    private Cls at(int x, int y, int z) {
        return inside(x, y, z) ? cls[grid[offset(x, y, z)]] : null;
    }

    private void hit(String rule, int x, int y, int z, String state, String reason) {
        if (skip.contains(rule)) return;
        counts.merge(rule, 1L, Long::sum);
        List<AnomalyHit> list = hits.computeIfAbsent(rule, k -> new ArrayList<>());
        if (list.size() < samples) list.add(new AnomalyHit(rule, x, y, z, state, reason));
    }

    // ------------------------------------------------------------------ per-cell rules

    private void cell(Cls c, String state, int x, int y, int z) {
        if (c.family() != Family.NONE) railing(c, state, x, y, z);
        if (c.hangingLamp() || c.standingLamp()) lamp(c, state, x, y, z);
        if (c.open()) headHeight(x, y, z);
        if (c.doorLower()) door(c, state, x, y, z);
        if (c.flowing()) flowing(c, state, x, y, z);
        if (c.leaves()) leaves(state, x, y, z);
        if (c.lightBlock()) hiddenLight(state, x, y, z);
    }

    private static boolean sameFamily(Cls a, Cls b) {
        return b != null && a.family() == b.family();
    }

    private void railing(Cls c, String state, int x, int y, int z) {
        if (!c.connected()) {
            boolean diagonal = false;
            for (int i = -1; i <= 1 && !diagonal; i += 2) {
                for (int k = -1; k <= 1 && !diagonal; k += 2) diagonal = sameFamily(c, at(x + i, y, z + k));
            }
            if (diagonal) {
                hit("diagonal-rail", x, y, z, state, "touches its neighbours only at a corner, so players slip through; run railings straight or in L-steps");
            } else if (stepped(c, x, y, z)) {
                // Reported by rail-gap.
            } else if (c.family() != Family.WALL && besideJoinable(c, x, y, z)) {
                // A free-standing post (bollard, mast, cresting) is a choice; a piece left unjoined
                // beside a wall or a rail is a placement leftover that shows as a thin post.
                hit("lone-post", x, y, z, state, "shows as a thin post although it stands beside "
                        + "something it should join; place it with connect or set its sides");
            }
        }
        // A thin railing that climbs a stair as separate posts, one block higher per step: the posts
        // never connect, so the gap between them is wider than a player. Walls have wide posts and
        // close the gap themselves.
        if (c.family() != Family.WALL) {
            for (int s = 0; s < 4; s++) {
                int ax = x + SIDE_STEP[s][0], az = z + SIDE_STEP[s][1];
                Cls next = at(ax, y + 1, az), level = at(ax, y, az), back = at(x, y + 1, z);
                int px = SIDE_STEP[(s + 1) % 4][0], pz = SIDE_STEP[(s + 1) % 4][1];
                boolean row = sameFamily(c, at(x + px, y, z + pz)) || sameFamily(c, at(x - px, y, z - pz));
                if (!row && sameFamily(c, next) && !sameFamily(c, level) && (back == null || !sameFamily(c, back)) && !connectsBack(ax, y + 1, az, s)) {
                    hit("rail-gap", x, y, z, state, "railing climbs as separate posts to " + ax + "," + (y + 1) + "," + az
                            + " and leaves a gap players fit through; fill the step under the rail or use a wall");
                    break;
                }
            }
        }
        if (c.family() == Family.FENCE && sameFamily(c, at(x + 1, y, z)) && sameFamily(c, at(x, y, z + 1)) && sameFamily(c, at(x + 1, y, z + 1)))
            hit("fence-clump", x, y, z, state, "fences fill a 2x2 block and merge into a solid mass; give tables corner legs with air between them");
    }

    /** A horizontal neighbour a fence, pane or bar would join: the same family or a solid block. */
    private boolean besideJoinable(Cls c, int x, int y, int z) {
        for (int[] d : SIDE_STEP) {
            Cls o = at(x + d[0], y, z + d[1]);
            if (o != null && (sameFamily(c, o) || o.family() == Family.NONE && o.solid())) return true;
        }
        return false;
    }

    /** A same-family piece one step up or down beside this one: a railing climbing a stair. */
    private boolean stepped(Cls c, int x, int y, int z) {
        for (int[] d : SIDE_STEP) {
            if (sameFamily(c, at(x + d[0], y + 1, z + d[1])) || sameFamily(c, at(x + d[0], y - 1, z + d[1]))) return true;
        }
        return false;
    }

    /** The piece at (x, y, z) connects back toward side {@code s} of the piece it was reached from. */
    private boolean connectsBack(int x, int y, int z, int s) {
        String v = WalkShapes.properties(stateAt(x, y, z)).get(SIDES[(s + 2) % 4]);
        return v != null && !v.equals("false") && !v.equals("none");
    }

    private void lamp(Cls c, String state, int x, int y, int z) {
        if (c.standingLamp()) {
            Cls below = at(x, y - 1, z);
            if (below != null && below.bed()) hit("low-lamp", x, y, z, state, "lantern rests on a bed");
            return;
        }
        int clear = 0;
        Cls below = null;
        int by = y - 1;
        for (; clear <= 3; clear++, by--) {
            below = at(x, by, z);
            if (below == null || !below.open()) break;
        }
        if (below == null || clear > 3 || !below.collides()) return;
        String what = below.bed() ? "a bed" : below.seat() ? "a seat" : below.table() ? "a table" : "the floor";
        if (clear <= 1 && !below.table()) {
            hit("low-lamp", x, y, z, state, "hanging lantern " + clear + " block" + (clear == 1 ? "" : "s") + " above " + what + ", at head height; hang it higher or use a wall or ceiling light");
        } else if (clear == 0) {
            hit("low-lamp", x, y, z, state, "hanging lantern touches " + what);
        } else if (clear == 2 && below.floor() && !below.table()) {
            Cls above = at(x, y + 1, z);
            if (above != null && !above.open() && above.family() == Family.NONE && !above.material().contains("chain"))
                hit("low-lamp", x, y, z, state, "hanging lantern in a 3-high room sits in the line of sight; use a flush or wall light, or a taller room");
        }
    }

    /** A 1-high squeeze: floor, open feet, something solid at head height, next to 2-high walkable cells. */
    private void headHeight(int x, int y, int z) {
        Cls floor = at(x, y - 1, z), head = at(x, y + 1, z);
        if (floor == null || head == null || !floor.floor() || head.open() || !head.collides() || head.family() != Family.NONE) return;
        if (head.natural() || head.leaves() && floor.natural() || head.hangingLamp() || head.standingLamp() || head.material().endsWith("_door") || head.material().endsWith("_stairs") && floor.material().endsWith("_stairs")) return;
        for (int axis = 0; axis < 2; axis++) {
            int sx = axis == 0 ? 1 : 0, sz = axis == 0 ? 0 : 1;
            if (walkable2(x + sx, y, z + sz) && walkable2(x - sx, y, z - sz)) {
                String obj = WalkShapes.material(stateAt(x, y + 1, z));
                hit("head-height", x, y + 1, z, stateAt(x, y + 1, z), obj + " leaves 1 block of headroom across a walkway; raise it or clear the way");
                return;
            }
        }
    }

    private String stateAt(int x, int y, int z) {
        return palette.get(grid[offset(x, y, z)]);
    }

    private boolean walkable2(int x, int y, int z) {
        Cls f = at(x, y - 1, z), a = at(x, y, z), b = at(x, y + 1, z);
        return f != null && a != null && b != null && f.floor() && a.open() && b.open();
    }

    private void door(Cls c, String state, int x, int y, int z) {
        int fx = c.doorAxis() == 0 ? 1 : 0, fz = c.doorAxis() == 0 ? 0 : 1;
        for (int sg = -1; sg <= 1; sg += 2) {
            int ax = x + sg * fx, az = z + sg * fz;
            Cls feet = at(ax, y, az), head = at(ax, y + 1, az);
            if (feet == null || head == null) continue;
            boolean feetClear = feet.passable() || feet.low();
            if (!feetClear || !head.passable()) {
                String what = WalkShapes.material(stateAt(ax, feetClear ? y + 1 : y, az));
                hit("blocked-door", x, y, z, state, what + " fills the doorway at " + ax + "," + (feetClear ? y + 1 : y) + "," + az + "; keep both sides of a door clear");
                return;
            }
        }
    }

    private void flowing(Cls c, String state, int x, int y, int z) {
        int[][] n = {{1, 0, 0}, {-1, 0, 0}, {0, 1, 0}, {0, -1, 0}, {0, 0, 1}, {0, 0, -1}};
        for (int[] d : n) {
            Cls o = at(x + d[0], y + d[1], z + d[2]);
            if (o != null && o.collides() && !o.natural()) {
                hit("flowing-liquid", x, y, z, state, "flowing " + c.material() + " beside built blocks: a spill or leftover?");
                return;
            }
        }
    }

    private void leaves(String state, int x, int y, int z) {
        int[][] n = {{1, 0, 0}, {-1, 0, 0}, {0, 1, 0}, {0, -1, 0}, {0, 0, 1}, {0, 0, -1}};
        for (int[] d : n) {
            Cls o = at(x + d[0], y + d[1], z + d[2]);
            if (o == null || !o.air()) return;
        }
        hit("floating-leaves", x, y, z, state, "leaf block touching nothing");
    }

    private void hiddenLight(String state, int x, int y, int z) {
        // Light over bare terrain keeps mobs off it; nobody expects a lamp there.
        boolean built = false;
        for (int i = -2; i <= 2 && !built; i++) {
            for (int j = -2; j <= 2 && !built; j++) {
                for (int k = -2; k <= 2 && !built; k++) {
                    Cls o = at(x + i, y + j, z + k);
                    built = o != null && o.collides() && !o.natural() && !o.leaves();
                }
            }
        }
        if (!built) return;
        for (int i = -MARGIN; i <= MARGIN; i++) {
            for (int j = -MARGIN; j <= MARGIN; j++) {
                for (int k = -MARGIN; k <= MARGIN; k++) {
                    Cls o = at(x + i, y + j, z + k);
                    if (o == null || o.visibleLight()) return;
                }
            }
        }
        hit("hidden-light", x, y, z, state, "invisible light block with no visible lamp within " + MARGIN + " blocks");
    }

    // ------------------------------------------------------------------ sealed rooms

    /**
     * Spaces a player could stand in that cannot be reached from the read box boundary through open
     * cells, doors, gates, trapdoors, ladders or water. Only furnished spaces with room to stand count:
     * natural caves and hollow walls are not rooms.
     */
    private void sealedRooms(Region inner, List<String> palette) {
        int dy = outer.maxY() - outer.minY() + 1;
        int volume = dx * dy * dz;
        BitSet reached = new BitSet(volume);
        int[] queue = new int[volume];
        int head = 0, tail = 0;
        for (int i = 0; i < volume; i++) {
            int x = i % dx, z = (i / dx) % dz, y = i / (dx * dz);
            if ((x == 0 || x == dx - 1 || z == 0 || z == dz - 1 || y == 0 || y == dy - 1) && cls[grid[i]].passable()) {
                reached.set(i);
                queue[tail++] = i;
            }
        }
        tail = flood(reached, queue, head, tail, dy);
        BitSet seen = (BitSet) reached.clone();
        for (int i = 0; i < volume; i++) {
            if (seen.get(i) || !cls[grid[i]].passable()) continue;
            int start = tail;
            seen.set(i);
            queue[tail++] = i;
            tail = flood(seen, queue, start, tail, dy);
            int stand = 0;
            boolean furnished = false, touchesInner = false;
            int bx = Integer.MAX_VALUE, by = Integer.MAX_VALUE, bz = Integer.MAX_VALUE;
            for (int q = start; q < tail; q++) {
                int c = queue[q];
                int x = outer.minX() + c % dx, z = outer.minZ() + (c / dx) % dz, y = outer.minY() + c / (dx * dz);
                if (x >= inner.minX() && x <= inner.maxX() && y >= inner.minY() && y <= inner.maxY() && z >= inner.minZ() && z <= inner.maxZ())
                    touchesInner = true;
                if (cls[grid[c]].furnishing()) furnished = true;
                Cls f = at(x, y - 1, z), up = at(x, y + 1, z);
                if (f != null && up != null && f.floor() && up.passable()) {
                    stand++;
                    if (f.furnishing()) furnished = true;
                }
                if (y < by || y == by && (z < bz || z == bz && x < bx)) { bx = x; by = y; bz = z; }
                if (!furnished) {
                    for (int[] d : new int[][] {{1, 0, 0}, {-1, 0, 0}, {0, 1, 0}, {0, 0, 1}, {0, 0, -1}}) {
                        Cls o = at(x + d[0], y + d[1], z + d[2]);
                        if (o != null && o.furnishing()) { furnished = true; break; }
                    }
                }
            }
            tail = start;
            if (touchesInner && furnished && stand >= 4)
                hit("sealed-room", bx, by, bz, palette.get(grid[offset(bx, by, bz)]), "furnished space of " + stand
                        + " standing cells with no way in (door, opening, ladder or hatch)");
        }
    }

    private int flood(BitSet reached, int[] queue, int head, int tail, int dy) {
        while (head < tail) {
            int i = queue[head++];
            int x = i % dx, z = (i / dx) % dz, y = i / (dx * dz);
            if (x > 0) tail = take(i - 1, reached, queue, tail);
            if (x < dx - 1) tail = take(i + 1, reached, queue, tail);
            if (z > 0) tail = take(i - dx, reached, queue, tail);
            if (z < dz - 1) tail = take(i + dx, reached, queue, tail);
            if (y > 0) tail = take(i - dx * dz, reached, queue, tail);
            if (y < dy - 1) tail = take(i + dx * dz, reached, queue, tail);
        }
        return tail;
    }

    private int take(int j, BitSet reached, int[] queue, int tail) {
        if (!reached.get(j) && cls[grid[j]].passable()) {
            reached.set(j);
            queue[tail++] = j;
        }
        return tail;
    }
}
