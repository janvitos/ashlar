// SPDX-License-Identifier: AGPL-3.0-or-later
package cc.wujm.ashlar.tool.mc;

import cc.wujm.ashlar.config.ConfigHolder;
import cc.wujm.ashlar.engine.ViewService;
import cc.wujm.ashlar.render.Sightline;
import cc.wujm.ashlar.rpc.InvocationContext;
import cc.wujm.ashlar.tool.ArgParse;
import cc.wujm.ashlar.tool.Tool;
import cc.wujm.ashlar.tool.ToolArgError;
import cc.wujm.ashlar.tool.ToolResult;
import cc.wujm.ashlar.tool.ToolRunner;
import cc.wujm.ashlar.tool.ToolSpec;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;

/** {@code mc_sightline}: read-only line-of-sight tests from an eye (Step 14). */
public final class McSightline implements Tool {

    private static final Set<String> KEYS = Set.of("eye", "player", "world", "targets", "cone", "ignore");
    private static final Set<String> CONE_KEYS = Set.of("yaw", "pitch", "fov", "rays", "distance");
    static final int MAX_TARGET_DISTANCE = 256;

    private final ToolSpec spec = ToolSpec.load("mc_sightline");
    private final ViewService views;
    private final ConfigHolder config;

    public McSightline(ViewService views, ConfigHolder config) {
        this.views = views;
        this.config = config;
    }

    @Override
    public ToolSpec spec() {
        return spec;
    }

    record ConeArgs(Double yaw, Double pitch, double fov, int rays, int distance) {}

    record Args(EyeArgs eye, List<int[]> targets, ConeArgs cone, List<Sightline.Ignore> ignore) {
        static Args parse(JsonObject o) {
            for (String k : o.keySet()) if (!KEYS.contains(k)) throw new ToolArgError("unknown parameter: " + k);
            EyeArgs eye = EyeArgs.parse(o, false);
            boolean hasTargets = ArgParse.has(o, "targets"), hasCone = ArgParse.has(o, "cone");
            if (hasTargets == hasCone) throw new ToolArgError("give exactly one of targets or cone");
            List<int[]> targets = null;
            if (hasTargets) {
                JsonArray a = ArgParse.requireArray(o, "targets");
                if (a.isEmpty() || a.size() > Sightline.MAX_TARGETS) throw new ToolArgError("targets: 1-" + Sightline.MAX_TARGETS + " positions");
                targets = new ArrayList<>();
                for (JsonElement e : a) {
                    if (!e.isJsonArray() || e.getAsJsonArray().size() != 3) throw new ToolArgError("targets: each must be [x,y,z]");
                    int[] t = new int[3];
                    for (int i = 0; i < 3; i++) t[i] = BuildTransform.strictInt(e.getAsJsonArray().get(i), "targets");
                    targets.add(t);
                }
                if (eye.eye() != null) checkReach(eye.eye(), targets);
            }
            ConeArgs cone = null;
            if (hasCone) {
                JsonObject c = ArgParse.requireObject(o.get("cone"), "cone");
                for (String k : c.keySet()) if (!CONE_KEYS.contains(k)) throw new ToolArgError("unknown cone field: " + k);
                Double yaw = c.has("yaw") ? EyeArgs.number(c.get("yaw"), "cone.yaw") : null;
                Double pitch = c.has("pitch") ? EyeArgs.number(c.get("pitch"), "cone.pitch") : null;
                if (eye.eye() != null && (yaw == null || pitch == null)) throw new ToolArgError("cone.yaw and cone.pitch are required with eye");
                double fov = c.has("fov") ? EyeArgs.number(c.get("fov"), "cone.fov") : 70;
                int rays = c.has("rays") ? BuildTransform.strictInt(c.get("rays"), "cone.rays") : 24;
                int distance = c.has("distance") ? BuildTransform.strictInt(c.get("distance"), "cone.distance") : 128;
                if (yaw != null && (yaw < -360 || yaw > 360)) throw new ToolArgError("cone.yaw: must be in [-360,360]");
                if (pitch != null && (pitch < -90 || pitch > 90)) throw new ToolArgError("cone.pitch: must be in [-90,90]");
                if (fov < 10 || fov > 110) throw new ToolArgError("cone.fov: must be in [10,110]");
                if (rays < 4 || rays > 48) throw new ToolArgError("cone.rays: must be 4-48");
                if (distance < 8 || distance > 256) throw new ToolArgError("cone.distance: must be 8-256");
                cone = new ConeArgs(yaw, pitch, fov, rays, distance);
            }
            List<Sightline.Ignore> ignore = new ArrayList<>();
            if (ArgParse.has(o, "ignore")) {
                JsonArray a = ArgParse.requireArray(o, "ignore");
                if (a.size() > 32) throw new ToolArgError("ignore: at most 32 patterns");
                for (JsonElement e : a) {
                    if (!e.isJsonPrimitive() || !e.getAsJsonPrimitive().isString()) throw new ToolArgError("ignore: must be strings");
                    try {
                        ignore.add(Sightline.Ignore.parse(e.getAsString()));
                    } catch (IllegalArgumentException ex) {
                        throw new ToolArgError("ignore: " + ex.getMessage());
                    }
                }
            }
            return new Args(eye, targets, cone, List.copyOf(ignore));
        }
    }

    static void checkReach(double[] eye, List<int[]> targets) {
        for (int[] t : targets) {
            double d = Math.sqrt(Math.pow(t[0] + .5 - eye[0], 2) + Math.pow(t[1] + .5 - eye[1], 2) + Math.pow(t[2] + .5 - eye[2], 2));
            if (d > MAX_TARGET_DISTANCE) {
                throw new ToolArgError("target [" + t[0] + "," + t[1] + "," + t[2] + "] is " + Math.round(d) + " blocks from the eye; the limit is " + MAX_TARGET_DISTANCE);
            }
        }
    }

    @Override
    public CompletableFuture<ToolResult> call(InvocationContext ctx, JsonObject args) {
        return ToolRunner.runText("mc_sightline", () -> {
            Args a = Args.parse(args);
            return a.eye().resolve(views, config).thenCompose(eye -> {
                if (a.targets() != null) {
                    checkReach(new double[]{eye.x(), eye.y(), eye.z()}, a.targets());
                    return views.sightlines(ctx, eye, a.targets(), a.ignore()).thenApply(l -> targetsText(eye, l));
                }
                ConeArgs c = a.cone();
                ViewService.Eye aimed = new ViewService.Eye(eye.world(), eye.x(), eye.y(), eye.z(),
                        c.yaw() != null ? c.yaw() : eye.yaw(), c.pitch() != null ? c.pitch() : eye.pitch(), eye.player());
                return views.cone(ctx, aimed, c.fov(), c.rays(), c.distance(), a.ignore()).thenApply(r -> coneText(aimed, c, r));
            });
        });
    }

    static String targetsText(ViewService.Eye eye, ViewService.Lines l) {
        int visible = 0, blocked = 0, unknown = 0;
        List<String> rows = new ArrayList<>();
        for (Sightline.Result r : l.results()) {
            String pos = r.target()[0] + "," + r.target()[1] + "," + r.target()[2];
            switch (r.status()) {
                case VISIBLE -> {
                    visible++;
                    rows.add(pos + "  visible  " + fmt(r.distance()) + " blocks");
                }
                case BLOCKED -> {
                    blocked++;
                    rows.add(pos + "  blocked by " + r.state() + " at " + r.at()[0] + "," + r.at()[1] + "," + r.at()[2] + " (" + fmt(r.distance()) + " blocks from the eye)");
                }
                case UNKNOWN -> {
                    unknown++;
                    rows.add(pos + "  unknown: chunk not loaded at " + r.at()[0] + "," + r.at()[1] + "," + r.at()[2] + " (" + fmt(r.distance()) + " blocks)");
                }
            }
        }
        StringBuilder sb = new StringBuilder();
        sb.append("Sightlines from ").append(EyeArgs.describe(eye)).append(" in ").append(eye.world().getName()).append(": ")
                .append(visible).append(" visible, ").append(blocked).append(" blocked");
        if (unknown > 0) sb.append(", ").append(unknown).append(" unknown");
        sb.append(" of ").append(l.results().size()).append(".\n").append(String.join("\n", rows));
        if (l.snapshot().loaded() < l.snapshot().requested()) {
            sb.append("\n").append(l.snapshot().requested() - l.snapshot().loaded()).append(" of ").append(l.snapshot().requested())
                    .append(" chunks along these lines are not loaded; lines through them answer unknown.");
        }
        return sb.toString();
    }

    static String coneText(ViewService.Eye eye, ConeArgs c, ViewService.Cone r) {
        Map<String, Integer> materials = new LinkedHashMap<>();
        int none = 0, unknown = 0;
        StringBuilder grid = new StringBuilder();
        for (Sightline.ConeHit[] row : r.grid()) {
            for (Sightline.ConeHit h : row) {
                if (h == null) {
                    grid.append('.');
                    none++;
                } else if (h.unknown()) {
                    grid.append('?');
                    unknown++;
                } else {
                    grid.append((char) ('0' + Math.min(9, (int) (h.distance() * 10 / c.distance()))));
                    materials.merge(h.state().replaceFirst("\\[.*", "").replace("minecraft:", ""), 1, Integer::sum);
                }
            }
            grid.append('\n');
        }
        int total = c.rays() * c.rays();
        List<Map.Entry<String, Integer>> top = new ArrayList<>(materials.entrySet());
        top.sort(Map.Entry.<String, Integer>comparingByValue(Comparator.reverseOrder()));
        StringBuilder sb = new StringBuilder();
        sb.append(String.format(Locale.ROOT, "Cone from %s in %s: fov %.0f, %dx%d rays, distance %d.%n", EyeArgs.describe(eye),
                eye.world().getName(), c.fov(), c.rays(), c.rays(), c.distance()));
        sb.append("Rows top to bottom; digit = hit distance in tenths of ").append(c.distance()).append(" blocks, '.' = nothing, '?' = unloaded.\n");
        sb.append(grid);
        sb.append(total - none - unknown).append(" rays hit, ").append(none).append(" open");
        if (unknown > 0) sb.append(", ").append(unknown).append(" unknown");
        sb.append(". Most hit: ");
        List<String> parts = new ArrayList<>();
        for (var e : top.subList(0, Math.min(8, top.size()))) parts.add(e.getKey() + " " + e.getValue());
        sb.append(parts.isEmpty() ? "nothing" : String.join(", ", parts)).append('.');
        return sb.toString();
    }

    private static String fmt(double d) {
        return String.format(Locale.ROOT, "%.1f", d);
    }
}
