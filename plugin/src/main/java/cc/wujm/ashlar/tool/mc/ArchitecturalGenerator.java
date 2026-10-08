// SPDX-License-Identifier: AGPL-3.0-or-later
package cc.wujm.ashlar.tool.mc;

import cc.wujm.ashlar.tool.ArgParse;
import cc.wujm.ashlar.tool.ToolArgError;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import java.util.Set;

/** Pure deterministic local geometry. No world reads, clearing, persistence or block writes. */
public final class ArchitecturalGenerator {
    private ArchitecturalGenerator() {}
    public record Output(JsonObject document, JsonObject summary) {}
    private static final long MAX_VISITS = 200_000;

    public static Output generate(JsonObject spec) {
        String kind = ArgParse.requireEnum(spec, "kind", java.util.List.of("roof", "arch", "tower", "stairs"));
        Builder b = new Builder(kind, spec);
        switch (kind) {
            case "roof" -> roof(spec, b);
            case "arch" -> arch(spec, b);
            case "tower" -> tower(spec, b);
            case "stairs" -> stairs(spec, b);
            default -> throw new IllegalStateException();
        }
        return b.finish();
    }

    private static int integer(JsonObject s, String key, int fallback, int min, int max) {
        int n = s.has(key) ? BuildTransform.strictInt(s.get(key), "generator." + key) : fallback;
        if (n < min || n > max) throw new ToolArgError("generator." + key + ": must be " + min + "-" + max);
        return n;
    }
    private static String style(JsonObject s, String fallback, String... choices) {
        return ArgParse.has(s, "style") ? ArgParse.requireEnum(s, "style", java.util.List.of(choices)) : fallback;
    }
    private static void fields(JsonObject s, String... extra) {
        var allowed = new java.util.HashSet<>(java.util.List.of(extra));
        allowed.add("kind"); allowed.add("materials");
        BlueprintCompiler.fields(s, allowed, "generator");
    }

    private static void roof(JsonObject s, Builder b) {
        fields(s, "style", "width", "depth", "gableInfill");
        int w = integer(s, "width", 9, 3, 64), d = integer(s, "depth", 11, 3, 64);
        String style = style(s, "gable", "gable", "hip", "shed");
        boolean infill = ArgParse.optBoolean(s, "gableInfill", false);
        if (s.has("gableInfill") && !style.equals("gable")) throw new ToolArgError("gableInfill applies only to gable roofs");
        for (int x = 0; x < w; x++) {
            if (!style.equals("hip")) {
                int h = style.equals("shed") ? x : Math.min(x, w - 1 - x);
                String state = style.equals("gable") && x == w - 1 - x ? "$slab[type=bottom]"
                        : "$stairs[facing=" + (style.equals("shed") || x < w / 2 ? "east" : "west") + ",half=bottom,shape=straight]";
                b.fill(x, h, 0, x, h, d - 1, state);
                if (infill && h > 0) {
                    b.fill(x, 0, 0, x, h - 1, 0, "$full");
                    b.fill(x, 0, d - 1, x, h - 1, d - 1, "$full");
                }
                continue;
            }
            for (int z = 0; z < d; z++) {
                int west = x, east = w - 1 - x, north = z, south = d - 1 - z;
                int h = Math.min(Math.min(west, east), Math.min(north, south));
                boolean atW = west == h, atE = east == h, atN = north == h, atS = south == h;
                String state;
                if ((atW && atE) || (atN && atS)) state = "$slab[type=bottom]";
                else {
                    String facing = atW && atN ? "east" : atE && atN ? "south"
                            : atE && atS ? "west" : atW && atS ? "north"
                            : atW ? "east" : atE ? "west" : atN ? "south" : "north";
                    boolean corner = (atW || atE) && (atN || atS);
                    state = "$stairs[facing=" + facing + ",half=bottom,shape=" + (corner ? "outer_right" : "straight") + "]";
                }
                b.fill(x, h, z, x, h, z, state);
            }
        }
        b.assumptions = "Slope 1:1; width/depth include desired eaves. Ridge runs along Z for gable, shed rises east. Hip corner shapes are explicit; build connect:false preserves them. No implicit wall/foundation/clearing.";
    }

    private static void arch(JsonObject s, Builder b) {
        fields(s, "style", "width", "height", "depth", "thickness", "rise");
        int w = integer(s, "width", 9, 3, 63), h = integer(s, "height", 3, 1, 32);
        if (w % 2 == 0) throw new ToolArgError("arch width must be odd for a centered opening");
        int depth = integer(s, "depth", 1, 1, 16), t = integer(s, "thickness", 1, 1, Math.min(8, (w - 1) / 2));
        String style = style(s, "round", "round", "pointed");
        if (s.has("rise") && style.equals("round")) throw new ToolArgError("rise applies only to pointed arches");
        int rise = integer(s, "rise", (w + 1) / 2, 2, (w + 1) / 2);
        double radius = w / 2.0, inner = radius - t;
        for (int x = 0; x < w; x++) {
            double dx = Math.abs(x + .5 - radius);
            if (x < t || x >= w - t) b.fill(x, 0, 0, x, h - 1, depth - 1, "$full");
            int top, bottom;
            if (style.equals("round")) {
                top = (int) Math.floor(Math.sqrt(radius * radius - dx * dx));
                bottom = dx < inner ? (int) Math.floor(Math.sqrt(inner * inner - dx * dx)) + 1 : 0;
            } else {
                top = (int) Math.round(rise * (1 - dx / radius));
                bottom = Math.max(0, top - t + 1);
                // Connect the shoulders to the piers even for a tall pointed crown.
                if (x < t || x >= w - t) bottom = 0;
            }
            // Bridge profile steps so thin curved/pointed beams stay face-connected.
            for (int neighbor : new int[]{x - 1, x + 1}) if (neighbor >= 0 && neighbor < w) {
                double ndx = Math.abs(neighbor + .5 - radius);
                int neighborTop = style.equals("round") ? (int) Math.floor(Math.sqrt(radius * radius - ndx * ndx))
                        : (int) Math.round(rise * (1 - ndx / radius));
                bottom = Math.min(bottom, neighborTop);
            }
            if (bottom <= top) b.fill(x, h + bottom, 0, x, h + top, depth - 1, "$full");
        }
        b.assumptions = "Arch spans X in the XY plane and extrudes along Z. Height is pier/spring-line height. Stepped full-block profile, not a smooth curve. Opening is omitted, never cleared; place into air or explicitly clear it in the build.";
    }

    private static void tower(JsonObject s, Builder b) {
        fields(s, "diameter", "height", "thickness", "floor", "battlements", "merlons");
        int d = integer(s, "diameter", 13, 5, 64), h = integer(s, "height", 12, 3, 64);
        int t = integer(s, "thickness", 1, 1, Math.min(8, (d - 3) / 2));
        boolean floor = ArgParse.optBoolean(s, "floor", true), crenels = ArgParse.optBoolean(s, "battlements", true);
        int merlons = integer(s, "merlons", 8, 4, 32);
        if (!crenels && s.has("merlons")) throw new ToolArgError("merlons requires battlements:true");
        double r = d / 2.0, inner = r - t;
        for (int z = 0; z < d; z++) {
            int run = -1, previous = 0;
            for (int x = 0; x <= d; x++) {
                double dx = x + .5 - r, dz = z + .5 - r, distance = dx * dx + dz * dz;
                int mask = 0;
                if (x < d && distance <= r * r) {
                    boolean bridge = t == 1 && Math.pow(Math.abs(dx) + 1, 2) + Math.pow(Math.abs(dz) + 1, 2) > r * r;
                    mask = distance >= inner * inner || bridge ? 2 : floor ? 1 : 0;
                    if (mask == 2 && crenels) {
                        double angle = (Math.atan2(dz, dx) + Math.PI) / (2 * Math.PI);
                        if (((int) Math.floor(angle * merlons * 2)) % 2 == 0) mask = 3;
                    }
                }
                if (mask != previous) {
                    if (previous != 0) b.fill(run, 0, z, x - 1, previous == 1 ? 0 : h - 1 + (previous == 3 ? 1 : 0), z, "$full");
                    run = x; previous = mask;
                }
            }
        }
        b.assumptions = "Cell-center circular shell with face-connected corner bridges for thin walls; thickness is nominal radial thickness. Height excludes optional merlons. Floor at Y=0, walls Y=0..height-1, merlons at Y=height. No entrance/roof/interior clearing: compose a doorway or explicit clearing separately.";
    }

    private static void stairs(JsonObject s, Builder b) {
        fields(s, "style", "width", "steps", "landing", "gap", "supports");
        int w = integer(s, "width", 3, 1, 16), n = integer(s, "steps", 8, 1, 64), landing = integer(s, "landing", 2, 1, 16);
        String style = style(s, "straight", "straight", "switchback");
        if (style.equals("straight") && s.has("gap")) throw new ToolArgError("gap applies only to switchback stairs");
        int gap = integer(s, "gap", 1, 1, 8);
        boolean supports = ArgParse.optBoolean(s, "supports", true);
        int offset = style.equals("switchback") ? landing : 0;
        for (int i = 0; i < n; i++) {
            step(b, 0, w - 1, i, i + offset, "south", supports);
            if (style.equals("switchback")) step(b, w + gap, 2 * w + gap - 1, n + i, n - 1 - i + offset, "north", supports);
        }
        int totalWidth = style.equals("straight") ? w : 2 * w + gap;
        b.fill(0, n - 1, n + offset, totalWidth - 1, n - 1, n + offset + landing - 1, "$full");
        if (supports && n > 1) b.fill(0, 0, n + offset, totalWidth - 1, n - 2, n + offset + landing - 1, "$full");
        if (style.equals("switchback")) {
            b.fill(w + gap, 2 * n - 1, 0, totalWidth - 1, 2 * n - 1, landing - 1, "$full");
            if (supports) b.fill(w + gap, 0, 0, totalWidth - 1, 2 * n - 2, landing - 1, "$full");
        }
        b.assumptions = "First flight ascends south, switchback returns north on the east side. One block rise/run per stair, lower half; landing top meets first-flight top. Optional supports are solid plinths, not terrain-aware foundations. No railings or clearing.";
    }
    private static void step(Builder b, int x0, int x1, int y, int z, String facing, boolean supports) {
        if (supports && y > 0) b.fill(x0, 0, z, x1, y - 1, z, "$full");
        b.fill(x0, y, z, x1, y, z, "$stairs[facing=" + facing + ",half=bottom,shape=straight]");
    }

    private static final class Builder {
        final String kind;
        final JsonArray fills = new JsonArray();
        final JsonObject materials = new JsonObject();
        final int[] max = {0, 0, 0};
        long visits;
        String assumptions;
        Builder(String kind, JsonObject s) {
            this.kind = kind;
            materials.addProperty("full", kind.equals("roof") ? "minecraft:dark_oak_planks" : kind.equals("stairs") ? "minecraft:oak_planks" : "minecraft:stone_bricks");
            materials.addProperty("stairs", kind.equals("roof") ? "minecraft:dark_oak_stairs" : "minecraft:oak_stairs");
            materials.addProperty("slab", "minecraft:dark_oak_slab");
            if (s.has("materials")) {
                JsonObject m = ArgParse.requireObject(s.get("materials"), "generator.materials");
                BlueprintCompiler.fields(m, Set.of("full", "stairs", "slab"), "generator.materials");
                for (String role : m.keySet()) materials.addProperty(role, ArgParse.requireString(m, role));
            }
        }
        void fill(int x0, int y0, int z0, int x1, int y1, int z1, String state) {
            if (x0 < 0 || y0 < 0 || z0 < 0 || x1 < x0 || y1 < y0 || z1 < z0) throw new IllegalStateException("invalid generated bounds");
            visits += (long) (x1 - x0 + 1) * (y1 - y0 + 1) * (z1 - z0 + 1);
            if (visits > MAX_VISITS || fills.size() >= BlueprintCompiler.MAX_RAW_OPS) throw new ToolArgError("generated geometry exceeds operation/200000 requested-cell budget; reduce dimensions or supports");
            max[0] = Math.max(max[0], x1); max[1] = Math.max(max[1], y1); max[2] = Math.max(max[2], z1);
            JsonArray from = point(x0, y0, z0), to = point(x1, y1, z1);
            if (!fills.isEmpty()) {
                JsonObject last = fills.get(fills.size() - 1).getAsJsonObject();
                JsonArray a = last.getAsJsonArray("from"), z = last.getAsJsonArray("to");
                if (last.get("block").getAsString().equals(state)) for (int axis = 0; axis < 3; axis++) {
                    boolean same = z.get(axis).getAsInt() + 1 == from.get(axis).getAsInt();
                    for (int other = 0; other < 3; other++) if (other != axis)
                        same &= a.get(other).equals(from.get(other)) && z.get(other).equals(to.get(other));
                    if (same) { z.set(axis, to.get(axis)); return; }
                }
            }
            JsonObject f = new JsonObject(); f.add("from", from); f.add("to", to); f.addProperty("block", state); fills.add(f);
        }
        Output finish() {
            JsonObject doc = new JsonObject(); doc.addProperty("version", 1); doc.addProperty("description", "Generated " + kind + ". " + assumptions);
            doc.add("dimensions", point(max[0] + 1, max[1] + 1, max[2] + 1)); doc.add("palette", materials);
            JsonObject component = new JsonObject(); component.add("fills", fills); JsonObject components = new JsonObject(); components.add(kind, component); doc.add("components", components);
            JsonObject instance = new JsonObject(); instance.addProperty("component", kind); instance.add("pos", point(0, 0, 0)); JsonArray instances = new JsonArray(); instances.add(instance); doc.add("instances", instances);
            BlueprintCompiler.validate(doc);
            JsonObject summary = new JsonObject(); summary.addProperty("kind", kind); summary.add("from", point(0, 0, 0)); summary.add("to", point(max[0], max[1], max[2])); summary.addProperty("requestedCells", visits); summary.addProperty("operations", fills.size()); summary.addProperty("assumptions", assumptions); summary.add("palette", materials.deepCopy());
            return new Output(doc, summary);
        }
    }
    private static JsonArray point(int x, int y, int z) { JsonArray p = new JsonArray(); p.add(x); p.add(y); p.add(z); return p; }
}
