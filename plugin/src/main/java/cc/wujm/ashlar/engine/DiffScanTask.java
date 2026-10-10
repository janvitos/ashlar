// SPDX-License-Identifier: AGPL-3.0-or-later
package cc.wujm.ashlar.engine;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.BlockFace;
import org.bukkit.block.BlockSupport;
import org.bukkit.block.data.Bisected;
import org.bukkit.block.data.BlockData;
import org.bukkit.block.data.Rail;
import org.bukkit.block.data.type.Lantern;
import org.bukkit.block.data.type.Snow;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Predicate;

/**
 * One read-only {@code mc_diff} sub-box: reads {@link #region()} (the inner box plus a one-block
 * anomaly margin, clamped to the world height) into a palette and an index grid, then evaluates the
 * requested placement anomalies for the inner cells. Comparison against the reference happens off
 * the main thread on {@link #palette()} / {@link #grid()}; nothing here writes to the world.
 */
public final class DiffScanTask extends BuildTask {

    private static final int DEADLINE_CHECK_INTERVAL = 256;

    private final World world;
    private final Region inner;
    private final boolean floating, stacked, unsupported;
    private final Predicate<String> ignored;
    private final int sampleCap;

    private final Map<String, Integer> index = new HashMap<>();
    private final List<String> palette = new ArrayList<>();
    private final List<BlockData> paletteData = new ArrayList<>();
    private final int[] grid;
    private final int dx, dz;

    private final Map<String, Long> counts = new LinkedHashMap<>();
    private final List<AnomalyHit> hits = new ArrayList<>();
    private final Map<String, Integer> hitsPerKind = new HashMap<>();

    private int phase, cx, cy, cz;
    private boolean cursorReady;
    private AnomalyRules.Kind[] kinds;
    private AnomalyRules.Below[] belows;
    private boolean[] ignoredPalette, needsSupport;
    private BlockData air;
    private SupportCheck support;

    /** {@code outer} must contain {@code inner}; anomalies is a subset of floating/stacked/unsupported. */
    public DiffScanTask(Region outer, Region inner, World world, Set<String> anomalies, Predicate<String> ignored, int sampleCap) {
        super(outer);
        this.world = world;
        this.inner = inner;
        this.floating = anomalies.contains("floating");
        this.stacked = anomalies.contains("stacked");
        this.unsupported = anomalies.contains("unsupported");
        this.ignored = ignored;
        this.sampleCap = sampleCap;
        this.dx = outer.maxX() - outer.minX() + 1;
        this.dz = outer.maxZ() - outer.minZ() + 1;
        this.grid = new int[Math.toIntExact(outer.volume())];
    }

    @Override public World world() { return world; }
    @Override public long volume() { return region().volume() + (anyAnomaly() ? inner.volume() : 0); }

    private boolean anyAnomaly() { return floating || stacked || unsupported; }

    @Override
    public boolean step(long deadline) {
        if (phase == 0 && !read(deadline)) return false;
        if (phase == 1 && !anomalies(deadline)) return false;
        return true;
    }

    private boolean read(long deadline) {
        Region r = region();
        if (!cursorReady) { cx = r.minX(); cy = r.minY(); cz = r.minZ(); cursorReady = true; }
        int since = 0, i = offset(cx, cy, cz);
        while (cy <= r.maxY()) {
            BlockData data = world.getBlockAt(cx, cy, cz).getBlockData();
            String s = data.getAsString();
            Integer idx = index.get(s);
            if (idx == null) {
                idx = palette.size();
                index.put(s, idx);
                palette.add(s);
                paletteData.add(data);
            }
            grid[i++] = idx;
            advance(1);
            if (++cx > r.maxX()) {
                cx = r.minX();
                if (++cz > r.maxZ()) { cz = r.minZ(); cy++; }
            }
            if (++since >= DEADLINE_CHECK_INTERVAL) {
                since = 0;
                if (cy <= r.maxY() && System.nanoTime() >= deadline) return false;
            }
        }
        phase = anyAnomaly() ? 1 : 2;
        cursorReady = false;
        return true;
    }

    private int offset(int x, int y, int z) {
        Region r = region();
        return ((y - r.minY()) * dz + (z - r.minZ())) * dx + (x - r.minX());
    }

    /** Palette index at a position, or -1 outside the read box (beyond the world height: treated as air). */
    private int paletteIndex(int x, int y, int z) {
        Region r = region();
        if (x < r.minX() || x > r.maxX() || y < r.minY() || y > r.maxY() || z < r.minZ() || z > r.maxZ()) return -1;
        return grid[offset(x, y, z)];
    }

    private BlockData lookup(int x, int y, int z) {
        int p = paletteIndex(x, y, z);
        return p < 0 ? air : paletteData.get(p);
    }

    private void classify() {
        air = BlockDataParser.parse("minecraft:air");
        int n = paletteData.size();
        kinds = new AnomalyRules.Kind[n];
        belows = new AnomalyRules.Below[n];
        ignoredPalette = new boolean[n];
        needsSupport = new boolean[n];
        for (int i = 0; i < n; i++) {
            BlockData d = paletteData.get(i);
            kinds[i] = kind(d);
            belows[i] = below(d);
            ignoredPalette[i] = ignored.test(palette.get(i));
            // Floating-family blocks are judged by the sharper rules above, not twice.
            needsSupport[i] = kinds[i] == AnomalyRules.Kind.NONE && SupportCheck.needsCheck(d, false);
        }
        support = new SupportCheck(this::lookup, List.of(), List.of(), true, false);
    }

    static AnomalyRules.Kind kind(BlockData d) {
        if (d instanceof Bisected b && b.getHalf() == Bisected.Half.TOP) return AnomalyRules.Kind.NONE;
        Material m = d.getMaterial();
        String name = m.name();
        if (d instanceof Snow) return AnomalyRules.Kind.SNOW;
        if (name.endsWith("_CARPET")) return AnomalyRules.Kind.CARPET;
        if (name.endsWith("_PRESSURE_PLATE")) return AnomalyRules.Kind.PLATE;
        if (d instanceof Rail) return AnomalyRules.Kind.RAIL;
        if (name.endsWith("TORCH") && !name.contains("WALL")) return AnomalyRules.Kind.TORCH;
        if (d instanceof Lantern l && !l.isHanging()) return AnomalyRules.Kind.LANTERN;
        if (SupportCheck.isDecorativePlant(m)) return AnomalyRules.Kind.PLANT;
        return AnomalyRules.Kind.NONE;
    }

    static AnomalyRules.Below below(BlockData d) {
        Material m = d.getMaterial();
        return new AnomalyRules.Below(m.isAir(), m.isSolid(),
                d.isFaceSturdy(BlockFace.UP, BlockSupport.FULL),
                d.isFaceSturdy(BlockFace.UP, BlockSupport.RIGID),
                d.isFaceSturdy(BlockFace.UP, BlockSupport.CENTER),
                d instanceof Snow s ? s.getLayers() : 0,
                m == Material.HONEY_BLOCK || m == Material.SOUL_SAND,
                m == Material.ICE || m == Material.PACKED_ICE || m == Material.BARRIER);
    }

    private boolean anomalies(long deadline) {
        if (kinds == null) classify();
        if (!cursorReady) { cx = inner.minX(); cy = inner.minY(); cz = inner.minZ(); cursorReady = true; }
        int since = 0;
        while (cy <= inner.maxY()) {
            int p = grid[offset(cx, cy, cz)];
            if (!ignoredPalette[p]) evaluate(p, cx, cy, cz);
            advance(1);
            if (++cx > inner.maxX()) {
                cx = inner.minX();
                if (++cz > inner.maxZ()) { cz = inner.minZ(); cy++; }
            }
            if (++since >= DEADLINE_CHECK_INTERVAL) {
                since = 0;
                if (cy <= inner.maxY() && System.nanoTime() >= deadline) return false;
            }
        }
        phase = 2;
        return true;
    }

    private void evaluate(int p, int x, int y, int z) {
        AnomalyRules.Kind k = kinds[p];
        if (k != AnomalyRules.Kind.NONE && (floating || stacked)) {
            int bi = paletteIndex(x, y - 1, z);
            AnomalyRules.Below below = bi < 0 ? AnomalyRules.Below.AIR : belows[bi];
            AnomalyRules.Result result = AnomalyRules.evaluate(k, below);
            if ((result == AnomalyRules.Result.FLOATING && floating) || (result == AnomalyRules.Result.STACKED && stacked)) {
                hit(result == AnomalyRules.Result.FLOATING ? "floating" : "stacked", x, y, z, palette.get(p), AnomalyRules.reason(k, result, below));
            }
        }
        if (unsupported && needsSupport[p]) {
            SupportCheck.Warning w = support.check(x, y, z);
            if (w != null) hit("unsupported", x, y, z, palette.get(p), w.reason());
        }
    }

    private void hit(String kind, int x, int y, int z, String state, String reason) {
        counts.merge(kind, 1L, Long::sum);
        int n = hitsPerKind.merge(kind, 1, Integer::sum);
        if (n <= sampleCap) hits.add(new AnomalyHit(kind, x, y, z, state, reason));
    }

    /** Raw {@code getAsString()} states, indexed by {@link #grid()} values. Valid after completion. */
    public List<String> palette() { return palette; }
    /** Palette indexes over {@link #region()} in y,z,x order. Valid after completion. */
    public int[] grid() { return grid; }
    public Region inner() { return inner; }
    /** Exact anomaly counts by kind for the inner box (ignored states excluded). */
    public Map<String, Long> anomalyCounts() { return counts; }
    /** The first {@code sampleCap} hits per kind, in y,z,x order. */
    public List<AnomalyHit> anomalyHits() { return hits; }

    @Override
    public JsonElement buildResult(long queuedMs, long elapsedMs) {
        JsonObject r = new JsonObject();
        r.addProperty("cells", region().volume());
        r.addProperty("elapsedMs", elapsedMs);
        return r;
    }
}
