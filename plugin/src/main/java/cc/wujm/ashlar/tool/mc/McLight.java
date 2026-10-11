// SPDX-License-Identifier: AGPL-3.0-or-later
package cc.wujm.ashlar.tool.mc;

import cc.wujm.ashlar.config.ConfigHolder;
import cc.wujm.ashlar.engine.LightScan;
import cc.wujm.ashlar.engine.LightSnapshotTask;
import cc.wujm.ashlar.engine.Region;
import cc.wujm.ashlar.engine.RequestValidator;
import cc.wujm.ashlar.engine.TickBudgetExecutor;
import cc.wujm.ashlar.rpc.InvocationContext;
import cc.wujm.ashlar.rpc.MainThread;
import cc.wujm.ashlar.tool.ArgParse;
import cc.wujm.ashlar.tool.Tool;
import cc.wujm.ashlar.tool.ToolArgError;
import cc.wujm.ashlar.tool.ToolResult;
import cc.wujm.ashlar.tool.ToolRunner;
import cc.wujm.ashlar.tool.ToolSpec;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import org.bukkit.Bukkit;
import org.bukkit.World;

import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;

/**
 * Read-only {@code mc_light} (Step 18): dark spawn spaces in a box and, optionally, a greedy list of
 * light positions in {@code mc_build cells} form. The main thread only copies chunk snapshots; rules
 * and planning run on the compute executor.
 */
public final class McLight implements Tool {

    private static final List<String> PLACEMENTS = List.of("hidden", "floor", "ceiling");
    static final int MAX_LIGHTS_LISTED = 2000;

    private final ToolSpec spec = ToolSpec.load("mc_light");
    private final ConfigHolder config;
    private final TickBudgetExecutor executor;
    private final Executor compute;

    public McLight(ConfigHolder config, TickBudgetExecutor executor, Executor compute) {
        this.config = config;
        this.executor = executor;
        this.compute = compute;
    }

    @Override
    public ToolSpec spec() {
        return spec;
    }

    record Args(String world, Region box, LightScan.Options options, LightScan.Suggest suggest, int samples) {
        static Args parse(JsonObject o) {
            String world = ArgParse.optString(o, "world");
            Region box = Region.of(ArgParse.requireCoords3(o, "from"), ArgParse.requireCoords3(o, "to"));
            int threshold = range(o, "threshold", 0, 0, 14);
            int samples = range(o, "samples", 20, 0, 200);
            LightScan.Options opts = new LightScan.Options(threshold, ArgParse.optBoolean(o, "includeSky", false),
                    ArgParse.optBoolean(o, "spawnable", true));
            LightScan.Suggest suggest = null;
            if (ArgParse.has(o, "suggest")) {
                JsonObject s = ArgParse.requireObject(o.get("suggest"), "suggest");
                for (String k : s.keySet()) {
                    if (!List.of("block", "level", "spacing", "placement").contains(k)) throw new ToolArgError("suggest." + k + ": unknown field");
                }
                String block = ArgParse.has(s, "block") ? EntityArgs.id(ArgParse.requireString(s, "block").replaceAll("\\[.*$", ""), "suggest.block")
                        + bracket(ArgParse.requireString(s, "block")) : "minecraft:light";
                boolean light = block.equals("minecraft:light") || block.startsWith("minecraft:light[");
                int level = range(s, "level", 11, 1, 15);
                if (level <= threshold) throw new ToolArgError("suggest.level: must be above threshold " + threshold);
                String placement = ArgParse.optEnum(s, "placement", PLACEMENTS, light ? "hidden" : "floor");
                if (placement.equals("hidden") && !light) throw new ToolArgError("suggest.placement \"hidden\" needs block \"light\" (invisible); use floor or ceiling for " + block);
                if (light) block = "minecraft:light[level=" + level + "]";
                suggest = new LightScan.Suggest(block, level, range(s, "spacing", 1, 1, 32), placement);
            }
            return new Args(world, box, opts, suggest, samples);
        }

        private static String bracket(String raw) {
            int i = raw.indexOf('[');
            return i < 0 ? "" : raw.substring(i).trim();
        }

        private static int range(JsonObject o, String field, int def, int min, int max) {
            Integer v = ArgParse.optInt(o, field);
            int r = v == null ? def : v;
            if (r < min || r > max) throw new ToolArgError(field + ": must be between " + min + " and " + max + ", got " + r);
            return r;
        }
    }

    @Override
    public CompletableFuture<ToolResult> call(InvocationContext ctx, JsonObject args) {
        return ToolRunner.runText("mc_light", () -> {
            Args a = Args.parse(args);
            if (a.suggest() != null && !a.suggest().block().startsWith("minecraft:light[")) {
                try {
                    Bukkit.createBlockData(a.suggest().block());
                } catch (IllegalArgumentException e) {
                    throw new ToolArgError("suggest.block: \"" + a.suggest().block() + "\" is not a block state");
                }
            }
            RequestValidator v = new RequestValidator(config.get());
            JsonObject params = new JsonObject();
            if (a.world() != null) params.addProperty("world", a.world());
            World world = v.resolveWorld(params);
            return MainThread.call(() -> new int[]{world.getMinHeight(), world.getMaxHeight()}).thenComposeAsync(h -> {
                params.add("from", JsonUtil.intArray(a.box().minX(), a.box().minY(), a.box().minZ()));
                params.add("to", JsonUtil.intArray(a.box().maxX(), a.box().maxY(), a.box().maxZ()));
                Region box = v.validateReadRegion(params, h[0], h[1], config.get().limits().maxReadVolume());
                LightSnapshotTask task = new LightSnapshotTask(world, box);
                return executor.submit(task, ctx).thenApplyAsync(ignored -> text(world.getName(), a, LightScan.scan(task.cells(), box, a.options(), a.suggest())), compute);
            }, compute);
        });
    }

    static String text(String world, Args a, LightScan.Result r) {
        Region b = a.box();
        LightScan.Options o = a.options();
        StringBuilder sb = new StringBuilder("Light in " + world + " x=[" + b.minX() + "," + b.maxX() + "] y=[" + b.minY() + "," + b.maxY() + "] z=["
                + b.minZ() + "," + b.maxZ() + "]: " + r.dark().length + " dark of " + r.spaces() + (o.spawnable() ? " spawn spaces" : " open cells")
                + " (block light" + (o.includeSky() ? " and sky light" : "") + " <= " + o.threshold() + (o.includeSky() ? "" : "; sky light ignored") + ").");
        if (r.dark().length > 0) {
            int[] min = r.dark()[0].clone(), max = r.dark()[0].clone();
            for (int[] d : r.dark()) for (int i = 0; i < 3; i++) { min[i] = Math.min(min[i], d[i]); max[i] = Math.max(max[i], d[i]); }
            sb.append("\nDark bounds: ").append(pos(min)).append("..").append(pos(max)).append("; by feet y:");
            int shown = 0;
            for (Map.Entry<Integer, Integer> e : r.darkByY().entrySet()) {
                if (shown++ == 24) {
                    sb.append(" ...");
                    break;
                }
                sb.append(' ').append(e.getKey()).append('=').append(e.getValue());
            }
            if (a.samples() > 0) {
                sb.append("\nSamples (feet cells, y,z,x order):");
                for (int i = 0; i < Math.min(a.samples(), r.dark().length); i++) sb.append(' ').append(pos(r.dark()[i]));
            }
        }
        LightScan.Suggest s = a.suggest();
        if (s == null) return sb.toString();
        if (r.note() != null) return sb.append("\nSuggestions: ").append(r.note()).append('.').toString();
        if (r.dark().length == 0) return sb.append("\nSuggestions: none needed.").toString();
        sb.append("\nSuggested ").append(r.lights().size()).append(" x ").append(s.block()).append(" (placement ").append(s.placement())
                .append(s.spacing() > 1 ? ", at least " + s.spacing() + " apart" : "").append(") light ").append(r.covered()).append(" of ")
                .append(r.dark().length).append(" dark cells");
        if (r.uncoverable() > 0) sb.append("; ").append(r.uncoverable()).append(" stay dark (no position within reach").append(s.spacing() > 1 ? " at that spacing" : "").append(")");
        sb.append(". Estimate assumes light spreads only inside the box; light updates take a moment after building, then run mc_light again.");
        JsonArray cells = new JsonArray();
        for (int i = 0; i < Math.min(MAX_LIGHTS_LISTED, r.lights().size()); i++) {
            int[] p = r.lights().get(i);
            JsonArray c = new JsonArray();
            c.add(p[0]);
            c.add(p[1]);
            c.add(p[2]);
            c.add(s.block());
            cells.add(c);
        }
        if (r.lights().size() > MAX_LIGHTS_LISTED) sb.append("\nOnly the first ").append(MAX_LIGHTS_LISTED).append(" are listed.");
        sb.append("\nmc_build cells: ").append(cells);
        return sb.toString();
    }

    private static String pos(int[] p) {
        return "[" + p[0] + "," + p[1] + "," + p[2] + "]";
    }
}
