// SPDX-License-Identifier: AGPL-3.0-or-later
package cc.wujm.ashlar.tool.mc;

import cc.wujm.ashlar.config.ConfigHolder;
import cc.wujm.ashlar.engine.Region;
import cc.wujm.ashlar.engine.RegionData;
import cc.wujm.ashlar.engine.Walker;
import cc.wujm.ashlar.rpc.InvocationContext;
import cc.wujm.ashlar.rpc.RpcHandler;
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
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;

/** {@code mc_walk}: read-only player-movement reachability inside a box (Step 15). */
public final class McWalk implements Tool {

    private static final Set<String> KEYS = Set.of("from", "to", "box", "world", "mode", "rules", "targets", "samples");
    private static final Set<String> RULE_KEYS = Set.of("jump", "stepHeight", "allowLadders", "allowWater", "doors", "maxDrop");
    static final int READ_MULTIPLIER = 4;
    static final int MAX_LIST_TARGETS = 256;
    static final int MAX_FOUND_TARGETS = 2000;

    private final ToolSpec spec = ToolSpec.load("mc_walk");
    private final RpcHandler readRegionHandler;
    private final ConfigHolder config;
    private final Executor compute;

    public McWalk(RpcHandler readRegionHandler, ConfigHolder config, Executor compute) {
        this.readRegionHandler = readRegionHandler;
        this.config = config;
        this.compute = compute;
    }

    @Override
    public ToolSpec spec() {
        return spec;
    }

    record Args(int[] from, int[] to, Region box, String world, String mode, Walker.Rules rules, String targetKind,
            List<int[]> targets, int samples) {
        static Args parse(JsonObject o) {
            for (String k : o.keySet()) if (!KEYS.contains(k)) throw new ToolArgError("unknown parameter: " + k);
            int[] from = ArgParse.requireCoords3(o, "from");
            int[] to = ArgParse.has(o, "to") ? ArgParse.requireCoords3(o, "to") : null;
            JsonObject b = ArgParse.requireObject(o.get("box"), "box");
            for (String k : b.keySet()) if (!k.equals("from") && !k.equals("to")) throw new ToolArgError("unknown box field: " + k);
            Region box = Region.of(ArgParse.requireCoords3(b, "from"), ArgParse.requireCoords3(b, "to"));
            String mode = ArgParse.optEnum(o, "mode", List.of("path", "reach"), to != null ? "path" : "reach");
            if (mode.equals("path") && to == null) throw new ToolArgError("mode \"path\" needs to:[x,y,z]");
            if (mode.equals("reach") && to != null) throw new ToolArgError("to: only for mode \"path\"; reach mode uses targets");
            if (!contains(box, from)) throw new ToolArgError("from: must be inside box");
            if (to != null && !contains(box, to)) throw new ToolArgError("to: must be inside box");
            Walker.Rules rules = Walker.Rules.DEFAULT;
            if (ArgParse.has(o, "rules")) rules = rules(ArgParse.requireObject(o.get("rules"), "rules"));
            String kind = null;
            List<int[]> targets = null;
            if (ArgParse.has(o, "targets")) {
                if (!mode.equals("reach")) throw new ToolArgError("targets: only for mode \"reach\"");
                JsonElement t = o.get("targets");
                if (t.isJsonPrimitive() && t.getAsJsonPrimitive().isString()) {
                    kind = t.getAsString();
                    if (!List.of("doors", "beds", "stairs").contains(kind)) throw new ToolArgError("targets: \"doors\", \"beds\", \"stairs\" or a list of [x,y,z]");
                } else {
                    JsonArray a = ArgParse.requireArray(o, "targets");
                    if (a.isEmpty() || a.size() > MAX_LIST_TARGETS) throw new ToolArgError("targets: 1-" + MAX_LIST_TARGETS + " positions");
                    targets = new ArrayList<>();
                    for (JsonElement e : a) {
                        if (!e.isJsonArray() || e.getAsJsonArray().size() != 3) throw new ToolArgError("targets: each must be [x,y,z]");
                        int[] p = new int[3];
                        for (int i = 0; i < 3; i++) p[i] = BuildTransform.strictInt(e.getAsJsonArray().get(i), "targets");
                        if (!contains(box, p)) throw new ToolArgError("targets: [" + p[0] + "," + p[1] + "," + p[2] + "] is outside box");
                        targets.add(p);
                    }
                }
            }
            int samples = ArgParse.has(o, "samples") ? ArgParse.requireInt(o, "samples") : 50;
            if (samples < 1 || samples > 200) throw new ToolArgError("samples: must be 1-200");
            return new Args(from, to, box, ArgParse.optString(o, "world"), mode, rules, kind, targets, samples);
        }

        static Walker.Rules rules(JsonObject r) {
            for (String k : r.keySet()) if (!RULE_KEYS.contains(k)) throw new ToolArgError("unknown rules field: " + k);
            double step = r.has("stepHeight") ? EyeArgs.number(r.get("stepHeight"), "rules.stepHeight") : .6;
            double drop = r.has("maxDrop") ? EyeArgs.number(r.get("maxDrop"), "rules.maxDrop") : 3;
            if (step < 0 || step > 1.5) throw new ToolArgError("rules.stepHeight: must be in [0,1.5]");
            if (drop < 0 || drop > 32) throw new ToolArgError("rules.maxDrop: must be in [0,32]");
            String doors = ArgParse.optEnum(r, "doors", List.of("open-only", "any"), "open-only");
            return new Walker.Rules(ArgParse.optBoolean(r, "jump", false), step, ArgParse.optBoolean(r, "allowLadders", true),
                    ArgParse.optBoolean(r, "allowWater", false), doors.equals("any"), drop);
        }
    }

    static boolean contains(Region b, int[] p) {
        return p[0] >= b.minX() && p[0] <= b.maxX() && p[1] >= b.minY() && p[1] <= b.maxY() && p[2] >= b.minZ() && p[2] <= b.maxZ();
    }

    @Override
    public CompletableFuture<ToolResult> call(InvocationContext ctx, JsonObject args) {
        return ToolRunner.runText("mc_walk", () -> {
            Args a = Args.parse(args);
            long maxRead = config.get().limits().maxReadVolume();
            if (a.box().volume() > maxRead * READ_MULTIPLIER) {
                throw new ToolArgError("box: " + a.box().volume() + " blocks is over the walk limit of " + maxRead * READ_MULTIPLIER
                        + " (4 x limits.max-read-volume); use a tighter box around the walkable area");
            }
            return BoxRead.read(readRegionHandler, ctx, a.world(), a.box(), maxRead, compute).thenApplyAsync(c -> run(a, c), compute);
        });
    }

    static String run(Args a, BoxRead.Cells c) {
        Walker w = new Walker(a.box(), c.ids(), c.palette(), a.rules());
        int start = w.nodeAt(a.from()[0], a.from()[1], a.from()[2]);
        if (start < 0) {
            throw new ToolArgError("from " + pos(a.from()) + " is not a standing place (feet height within half a block of y "
                    + a.from()[1] + ", on " + w.state(a.from()[0], a.from()[1] - 1, a.from()[2]) + ")");
        }
        if (w.problem(start) != null) throw new ToolArgError("from " + pos(a.from()) + " is not a standing place: " + describe(w.problem(start)));
        StringBuilder sb = new StringBuilder();
        String where = c.world() != null ? " in " + c.world() : "";
        if (a.mode().equals("path")) {
            int goal = w.nodeAt(a.to()[0], a.to()[1], a.to()[2]);
            int[] parent = w.search(start, goal);
            List<Integer> path = Walker.path(parent, goal);
            sb.append("Walk from ").append(pos(a.from())).append(" to ").append(pos(a.to())).append(where).append(": ");
            if (!path.isEmpty()) {
                sb.append("reachable in ").append(path.size() - 1).append(" moves.\n");
                sb.append("Waypoints (feet): ").append(waypoints(w, path, a.samples())).append('\n');
            } else {
                sb.append("NOT reachable.\n");
                if (goal >= 0 && w.problem(goal) != null) {
                    sb.append("The destination is not a standing place: ").append(describe(w.problem(goal))).append('\n');
                }
                Walker.Diagnosis d = w.diagnose(parent, a.to()[0], a.to()[1], a.to()[2]);
                sb.append(diagnosis(w, d)).append('\n');
                sb.append(Walker.reachedCount(parent)).append(" standing places are reachable from the start inside the box.\n");
            }
        } else {
            int[] parent = w.search(start, -1);
            int reached = Walker.reachedCount(parent);
            int valid = 0;
            for (int i = 0; i < w.nodeCount(); i++) if (w.problem(i) == null) valid++;
            sb.append("Reach from ").append(pos(a.from())).append(where).append(": ").append(reached).append(" of ").append(valid)
                    .append(" standing places in the box are reachable").append(extent(w, parent)).append(".\n");
            if (a.targetKind() != null || a.targets() != null) sb.append(targets(w, parent, a)).append('\n');
        }
        sb.append("Rules: ").append(rules(a.rules())).append('.');
        return sb.toString();
    }

    private static String targets(Walker w, int[] parent, Args a) {
        List<String> bad = new ArrayList<>();
        int total = 0;
        boolean truncated = false;
        String label = a.targetKind() != null ? a.targetKind() : "targets";
        if (a.targets() != null) {
            for (int[] t : a.targets()) {
                total++;
                if (!w.reached(parent, t[0], t[2], t[1] - .5, t[1] + .5)) {
                    bad.add(pos(t) + "  " + diagnosis(w, w.diagnose(parent, t[0], t[1], t[2])));
                }
            }
        } else {
            Region b = w.box();
            scan:
            for (int y = b.minY(); y <= b.maxY(); y++) {
                for (int z = b.minZ(); z <= b.maxZ(); z++) {
                    for (int x = b.minX(); x <= b.maxX(); x++) {
                        String s = w.state(x, y, z);
                        String m = s.replaceFirst("\\[.*", "").replace("minecraft:", "");
                        String miss = switch (a.targetKind()) {
                            case "doors" -> m.endsWith("_door") && s.contains("half=lower") ? door(w, parent, x, y, z, s) : "-";
                            case "beds" -> m.endsWith("_bed") && s.contains("part=head") ? bed(w, parent, x, y, z, s) : "-";
                            default -> m.endsWith("_stairs") && !s.contains("half=top")
                                    ? (w.reached(parent, x, z, y + 1, y + 1) ? null : diagnosis(w, w.diagnose(parent, x, y + 1, z))) : "-";
                        };
                        if ("-".equals(miss)) continue;
                        total++;
                        if (miss != null) bad.add(pos(new int[]{x, y, z}) + " " + m + "  " + miss);
                        if (total >= MAX_FOUND_TARGETS) {
                            truncated = true;
                            break scan;
                        }
                    }
                }
            }
        }
        StringBuilder sb = new StringBuilder();
        sb.append(cap(label)).append(": ").append(total).append(" found, ").append(total - bad.size()).append(" reachable, ")
                .append(bad.size()).append(" not");
        if (truncated) sb.append(" (stopped after ").append(MAX_FOUND_TARGETS).append("; use a smaller box)");
        sb.append(bad.isEmpty() ? "." : ":");
        for (int i = 0; i < Math.min(bad.size(), a.samples()); i++) sb.append("\n").append(bad.get(i));
        if (bad.size() > a.samples()) sb.append("\n... and ").append(bad.size() - a.samples()).append(" more.");
        return sb.toString();
    }

    /** A door counts as reachable when both sides are; null when it is, else what is missing. */
    private static String door(Walker w, int[] parent, int x, int y, int z, String state) {
        boolean ns = state.contains("facing=north") || state.contains("facing=south");
        int[][] sides = ns ? new int[][]{{x, y, z - 1}, {x, y, z + 1}} : new int[][]{{x - 1, y, z}, {x + 1, y, z}};
        String[] names = ns ? new String[]{"north", "south"} : new String[]{"west", "east"};
        List<String> miss = new ArrayList<>();
        for (int i = 0; i < 2; i++) {
            int[] s = sides[i];
            if (!w.reached(parent, s[0], s[2], y - .5, y + .5)) miss.add(names[i] + " side unreachable: " + diagnosis(w, w.diagnose(parent, s[0], s[1], s[2])));
        }
        return miss.isEmpty() ? null : String.join("; ", miss);
    }

    /** A bed counts as reachable when a player can stand on it or next to it at its level. */
    private static String bed(Walker w, int[] parent, int x, int y, int z, String state) {
        int[] f = facing(state);
        int[][] halves = {{x, z}, {x - f[0], z - f[1]}};
        for (int[] h : halves) {
            if (w.reached(parent, h[0], h[1], y - 1, y + 1.5)) return null;
            for (int[] d : new int[][]{{1, 0}, {-1, 0}, {0, 1}, {0, -1}}) if (w.reached(parent, h[0] + d[0], h[1] + d[1], y - 1, y + 1)) return null;
        }
        return diagnosis(w, w.diagnose(parent, x, y, z));
    }

    private static int[] facing(String state) {
        if (state.contains("facing=north")) return new int[]{0, -1};
        if (state.contains("facing=south")) return new int[]{0, 1};
        if (state.contains("facing=east")) return new int[]{1, 0};
        return new int[]{-1, 0};
    }

    static String diagnosis(Walker w, Walker.Diagnosis d) {
        if (d.node() < 0) return describe(d.block());
        return "nearest reachable " + feet(w, d.node()) + "; " + describe(d.block());
    }

    static String describe(Walker.Block b) {
        StringBuilder sb = new StringBuilder(b.reason());
        if (b.at() != null) sb.append(" at ").append(b.at()[0]).append(',').append(b.at()[1]).append(',').append(b.at()[2]);
        if (b.state() != null) sb.append(" (").append(b.state().replace("minecraft:", "")).append(')');
        return sb.toString();
    }

    /** Turning points and level changes of a path, thinned to at most {@code max} points (ends kept). */
    static String waypoints(Walker w, List<Integer> path, int max) {
        List<Integer> keep = new ArrayList<>();
        for (int i = 0; i < path.size(); i++) {
            if (i == 0 || i == path.size() - 1) {
                keep.add(path.get(i));
                continue;
            }
            int p = path.get(i - 1), n = path.get(i), q = path.get(i + 1);
            boolean turn = w.x(n) - w.x(p) != w.x(q) - w.x(n) || w.z(n) - w.z(p) != w.z(q) - w.z(n);
            boolean level = Math.abs(w.h(n) - w.h(p)) > 1e-6 || Math.abs(w.h(q) - w.h(n)) > 1e-6;
            if (turn || level) keep.add(n);
        }
        List<String> out = new ArrayList<>();
        int m = Math.max(2, max);
        for (int i = 0; i < m && i < keep.size(); i++) {
            int k = keep.size() <= m ? i : (int) Math.round((double) i * (keep.size() - 1) / (m - 1));
            out.add(feet(w, keep.get(k)));
        }
        String s = String.join(" -> ", out);
        return keep.size() > m ? s + " (" + keep.size() + " turning points thinned to " + m + ")" : s;
    }

    static String feet(Walker w, int node) {
        return w.x(node) + "," + num(w.h(node)) + "," + w.z(node);
    }

    private static String extent(Walker w, int[] parent) {
        int x0 = Integer.MAX_VALUE, x1 = Integer.MIN_VALUE, z0 = Integer.MAX_VALUE, z1 = Integer.MIN_VALUE;
        double y0 = Double.MAX_VALUE, y1 = -Double.MAX_VALUE;
        for (int i = 0; i < parent.length; i++) {
            if (parent[i] == -1) continue;
            x0 = Math.min(x0, w.x(i));
            x1 = Math.max(x1, w.x(i));
            z0 = Math.min(z0, w.z(i));
            z1 = Math.max(z1, w.z(i));
            y0 = Math.min(y0, w.h(i));
            y1 = Math.max(y1, w.h(i));
        }
        return " (x " + x0 + ".." + x1 + ", feet y " + num(y0) + ".." + num(y1) + ", z " + z0 + ".." + z1 + ")";
    }

    static String rules(Walker.Rules r) {
        return String.format(Locale.ROOT, "step %s, jump %s, maxDrop %s, ladders %s, water %s, doors %s", num(r.stepHeight()),
                r.jump() ? "on (1.25)" : "off", num(r.maxDrop()), r.ladders() ? "yes" : "no", r.water() ? "yes" : "no",
                r.anyDoor() ? "any" : "open-only");
    }

    static String num(double d) {
        String s = String.format(Locale.ROOT, "%.3f", d).replaceFirst("0+$", "");
        return s.endsWith(".") ? s.substring(0, s.length() - 1) : s;
    }

    private static String pos(int[] p) {
        return "[" + p[0] + "," + p[1] + "," + p[2] + "]";
    }

    private static String cap(String s) {
        return Character.toUpperCase(s.charAt(0)) + s.substring(1);
    }
}
