// SPDX-License-Identifier: AGPL-3.0-or-later
package cc.wujm.ashlar.tool.mc;

import cc.wujm.ashlar.config.PluginConfig;
import cc.wujm.ashlar.rpc.InvocationContext;
import cc.wujm.ashlar.rpc.RpcHandler;
import cc.wujm.ashlar.tool.ArgParse;
import cc.wujm.ashlar.tool.ToolArgError;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import java.util.Locale;
import java.util.Set;
import java.util.concurrent.CompletableFuture;

/**
 * {@code mc_build heightfield}: one target surface height per column plus a few materials. Reads
 * each column's live ground and top (as {@code mc_survey saveAs} does), turns the difference into
 * cells (fill up to the new surface, repaint its top layers, clear what stood above it) and builds
 * them through {@link CellBuild} under one journal entry. The cell computation is pure Java.
 */
final class Heightfield {
    private Heightfield() {}

    static final int MAX_COLUMNS = 200_000;
    static final int MAX_UNDER_DEPTH = 16;
    static final int SKIP = Integer.MIN_VALUE;
    private static final Set<String> KEYS = Set.of("heightfield", "world", "connect", "liquids", "snapshot", "label", "journal",
            "override", "detailed", "dryRun");
    private static final Set<String> FIELDS = Set.of("from", "width", "heights", "top", "under", "underDepth", "fill", "steep", "steepAt",
            "clearAbove");

    /** Target heights row-major from {@code [x0,z0]} ({@link #SKIP} leaves a column alone) and the materials. */
    record Spec(int x0, int z0, int width, int depth, int[] heights, String top, String under, int underDepth, String fill, String steep,
            int steepAt, boolean clearAbove) {}

    /** Live readings of the spec's columns, same order: first non-air block, ground below plants, trees and liquids. */
    record Ground(int[] topY, int[] groundY, String[] topState) {}

    record Stats(int columns, int skipped, int raised, int maxRaise, int lowered, int maxLower, int steep, long cells) {
        String line() {
            return String.format(Locale.ROOT, "Heightfield: %d columns (%d skipped): %d raised%s, %d lowered%s, %d level%s.",
                    columns, skipped, raised, raised > 0 ? " (up to +" + maxRaise + ")" : "", lowered, lowered > 0 ? " (up to -" + maxLower + ")" : "",
                    columns - skipped - raised - lowered, steep > 0 ? ", " + steep + " steep" : "");
        }
    }

    static boolean applies(JsonObject args) {
        return ArgParse.has(args, "heightfield");
    }

    static Spec parse(JsonObject args) {
        for (String k : args.keySet()) {
            if (!KEYS.contains(k)) throw new ToolArgError("with heightfield, mc_build accepts only " + String.join(", ", KEYS.stream().sorted().toList())
                    + "; got " + k);
        }
        JsonObject h = ArgParse.requireObject(args.get("heightfield"), "heightfield");
        for (String k : h.keySet()) {
            if (!FIELDS.contains(k)) throw new ToolArgError("heightfield accepts only " + String.join(", ", FIELDS.stream().sorted().toList()) + "; got " + k);
        }
        int[] from = ArgParse.requireCoords2(h, "from");
        int width = ArgParse.requireInt(h, "width");
        JsonArray a = ArgParse.requireArray(h, "heights");
        if (width < 1) throw new ToolArgError("heightfield.width: must be at least 1");
        if (a.isEmpty() || a.size() > MAX_COLUMNS) {
            throw new ToolArgError("heightfield.heights: 1-" + MAX_COLUMNS + " columns per call; split a larger area into tiles");
        }
        if (a.size() % width != 0) {
            throw new ToolArgError("heightfield.heights: " + a.size() + " values do not fill whole rows of width " + width);
        }
        int[] heights = new int[a.size()];
        for (int i = 0; i < heights.length; i++) {
            JsonElement e = a.get(i);
            if (e.isJsonNull()) {
                heights[i] = SKIP;
                continue;
            }
            if (!e.isJsonPrimitive() || !e.getAsJsonPrimitive().isNumber()) throw new ToolArgError("heightfield.heights[" + i + "]: must be an integer y or null");
            double d = e.getAsDouble();
            if (d != Math.rint(d) || Math.abs(d) > 4096) throw new ToolArgError("heightfield.heights[" + i + "]: must be an integer y or null");
            heights[i] = (int) d;
        }
        String top = ArgParse.requireString(h, "top");
        String fill = ArgParse.requireString(h, "fill");
        String under = ArgParse.has(h, "under") ? ArgParse.requireString(h, "under") : fill;
        String steep = ArgParse.has(h, "steep") ? ArgParse.requireString(h, "steep") : null;
        Integer depth = ArgParse.optInt(h, "underDepth");
        int underDepth = depth == null ? 3 : depth;
        if (underDepth < 0 || underDepth > MAX_UNDER_DEPTH) throw new ToolArgError("heightfield.underDepth: must be 0-" + MAX_UNDER_DEPTH);
        Integer at = ArgParse.optInt(h, "steepAt");
        if (at != null && steep == null) throw new ToolArgError("heightfield.steepAt: only with steep");
        int steepAt = at == null ? 3 : at;
        if (steepAt < 1 || steepAt > 64) throw new ToolArgError("heightfield.steepAt: must be 1-64");
        return new Spec(from[0], from[1], width, heights.length / width, heights, top, under, underDepth, fill, steep, steepAt,
                ArgParse.optBoolean(h, "clearAbove", true));
    }

    /** The {@code columns} part of a heightmap read over the spec's area. */
    static Ground ground(JsonObject columns) {
        JsonArray palette = columns.getAsJsonArray("palette");
        JsonArray topY = columns.getAsJsonArray("topY"), groundY = columns.getAsJsonArray("groundY"), top = columns.getAsJsonArray("top");
        int n = topY.size();
        Ground g = new Ground(new int[n], new int[n], new String[n]);
        for (int i = 0; i < n; i++) {
            g.topY()[i] = topY.get(i).getAsInt();
            g.groundY()[i] = groundY.get(i).getAsInt();
            g.topState()[i] = palette.get(top.get(i).getAsInt()).getAsString();
        }
        return g;
    }

    /**
     * Adds every column's cells to {@code out}. A column gets its materials from {@code min(ground,
     * target - underDepth)} up to the target (top block, {@code underDepth} under blocks, fill below),
     * and air above it up to its old top ({@code clearAbove}) or old ground. Under water or lava the
     * cleared space is that liquid, so a lowered bed stays wet. With {@code steep}, columns whose
     * height differs from a neighbour by at least {@code steepAt} use it for the top and under layers.
     * Counts first and refuses before adding anything when the total exceeds {@code max}.
     */
    static Stats cells(Spec s, Ground g, CellSet.Builder out, long max) {
        int n = s.heights().length;
        if (g.topY().length != n) throw new IllegalArgumentException("ground readings do not match the heightfield");
        long total = 0;
        int skipped = 0;
        for (int i = 0; i < n; i++) {
            int h = s.heights()[i];
            if (h == SKIP) {
                skipped++;
                continue;
            }
            total += h - low(s, g, i) + 1 + Math.max(0, high(s, g, i) - h);
        }
        if (skipped == n) throw new ToolArgError("heightfield.heights: every column is null; nothing to build");
        if (total > max) {
            throw new ToolArgError(String.format(Locale.ROOT, "heightfield: these heights need %,d cells, more than %,d per call. Nothing was"
                    + " written. Split the area into tiles (about %d columns each) or change less height per call.", total, max,
                    Math.max(1, (long) ((double) (n - skipped) * max / total))));
        }
        int raised = 0, lowered = 0, maxRaise = 0, maxLower = 0, steepCols = 0;
        for (int i = 0; i < n; i++) {
            int h = s.heights()[i];
            if (h == SKIP) continue;
            int x = s.x0() + i % s.width(), z = s.z0() + i / s.width();
            int ground = g.groundY()[i];
            if (h > ground) {
                raised++;
                maxRaise = Math.max(maxRaise, h - ground);
            } else if (h < ground) {
                lowered++;
                maxLower = Math.max(maxLower, ground - h);
            }
            boolean steep = s.steep() != null && slope(s, g, i) >= s.steepAt();
            if (steep) steepCols++;
            for (int y = low(s, g, i); y <= h; y++) {
                int d = h - y;
                out.add(x, y, z, d == 0 ? (steep ? s.steep() : s.top()) : d <= s.underDepth() ? (steep ? s.steep() : s.under()) : s.fill());
            }
            String liquid = liquid(g.topState()[i]);
            for (int y = h + 1; y <= high(s, g, i); y++) out.add(x, y, z, liquid != null && y <= g.topY()[i] ? liquid : "minecraft:air");
        }
        return new Stats(n, skipped, raised, maxRaise, lowered, maxLower, steepCols, total);
    }

    private static int low(Spec s, Ground g, int i) {
        return Math.min(g.groundY()[i], s.heights()[i] - s.underDepth());
    }

    /** Highest y cleared above the new surface. A liquid column only clears down to its old bed. */
    private static int high(Spec s, Ground g, int i) {
        return s.clearAbove() && liquid(g.topState()[i]) == null ? Math.max(g.topY()[i], g.groundY()[i]) : g.groundY()[i];
    }

    /** Largest height difference to a side neighbour; a skipped neighbour counts with its live ground. */
    private static int slope(Spec s, Ground g, int i) {
        int x = i % s.width(), z = i / s.width(), h = s.heights()[i], best = 0;
        int[][] sides = {{-1, 0}, {1, 0}, {0, -1}, {0, 1}};
        for (int[] d : sides) {
            int nx = x + d[0], nz = z + d[1];
            if (nx < 0 || nz < 0 || nx >= s.width() || nz >= s.depth()) continue;
            int j = nz * s.width() + nx;
            int other = s.heights()[j] == SKIP ? g.groundY()[j] : s.heights()[j];
            best = Math.max(best, Math.abs(h - other));
        }
        return best;
    }

    /** {@code minecraft:water} or {@code minecraft:lava} for a liquid top block, else null. */
    static String liquid(String state) {
        int b = state.indexOf('[');
        String m = b < 0 ? state : state.substring(0, b);
        if (m.startsWith("minecraft:")) m = m.substring("minecraft:".length());
        return switch (m) {
            case "water", "bubble_column" -> "minecraft:water";
            case "lava" -> "minecraft:lava";
            default -> null;
        };
    }

    /** Reads the columns, computes the cells and builds them; not called from the main thread. */
    static CompletableFuture<String> run(McBuild build, InvocationContext ctx, JsonObject args, PluginConfig cfg, RpcHandler heightmap) {
        Spec s = parse(args);
        McBuild.JournalArgs.parse(args);
        JsonObject p = new JsonObject();
        if (ArgParse.has(args, "world")) p.addProperty("world", ArgParse.requireString(args, "world"));
        JsonArray from = new JsonArray(), to = new JsonArray();
        from.add(s.x0());
        from.add(s.z0());
        to.add(s.x0() + s.width() - 1);
        to.add(s.z0() + s.depth() - 1);
        p.add("from", from);
        p.add("to", to);
        p.addProperty("type", "WORLD_SURFACE");
        p.addProperty("columns", true);
        return heightmap.handle(ctx, p).thenComposeAsync(el -> {
            Ground g = ground(el.getAsJsonObject().getAsJsonObject("columns"));
            CellSet.Builder b = new CellSet.Builder(CellBuild.MAX_CELLS, "heightfield");
            Stats st = cells(s, g, b, CellBuild.MAX_CELLS);
            return CellBuild.run(build, ctx, args, cfg, CellBuild.planCells(args, cfg, b.build()), st.line(),
                    "Repeat the same heightfield call to finish: it reads the ground again, and finished columns come out unchanged.");
        });
    }
}
