// SPDX-License-Identifier: AGPL-3.0-or-later
package cc.wujm.ashlar.tool.mc;

import cc.wujm.ashlar.engine.EntityGeometry;
import cc.wujm.ashlar.engine.EntitySpec;
import cc.wujm.ashlar.engine.Region;
import cc.wujm.ashlar.tool.ArgParse;
import cc.wujm.ashlar.tool.ToolArgError;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * {@code mc_build entities}, {@code entityMode} and {@code entityBox} (Step 17), already in world
 * coordinates: a {@code transform} moves positions, turns facings and yaws, and a mirror makes a
 * painting's position its lower-right cell (the viewer's sides swap). Pure Java.
 */
record EntityArgs(List<EntitySpec> entities, Region replaceBox) {

    static final int MAX_ENTITIES = 256;
    static final List<String> MODES = List.of("add", "replace-in-box");
    private static final Pattern ID = Pattern.compile("[a-z0-9_.-]+(:[a-z0-9_./-]+)?");
    private static final Set<String> PAINTING = Set.of("type", "pos", "facing", "variant");
    private static final Set<String> FRAME = Set.of("type", "pos", "facing", "item", "rotation", "fixed", "invisible");
    private static final Set<String> STAND = Set.of("type", "pos", "yaw", "equipment", "pose", "small", "invisible", "noGravity");

    static boolean applies(JsonObject o) {
        return ArgParse.has(o, "entities") || ArgParse.has(o, "entityMode") || ArgParse.has(o, "entityBox");
    }

    /** Parses the entity part of an {@code mc_build} call; {@code null} when it has none. */
    static EntityArgs parse(JsonObject o) {
        if (!applies(o)) return null;
        BuildTransform t = BuildTransform.parse(o);
        boolean local = ArgParse.has(o, "transform");
        JsonArray arr = ArgParse.requireArray(o, "entities");
        if (arr.isEmpty() || arr.size() > MAX_ENTITIES) throw new ToolArgError("entities: 1-" + MAX_ENTITIES + " entries");
        List<EntitySpec> out = new ArrayList<>();
        for (int i = 0; i < arr.size(); i++) out.add(entry(ArgParse.requireObject(arr.get(i), "entities[" + i + "]"), "entities[" + i + "]", t, local));
        String mode = ArgParse.optEnum(o, "entityMode", MODES, "add");
        Region box = null;
        if (mode.equals("replace-in-box")) {
            if (!ArgParse.has(o, "entityBox")) throw new ToolArgError("entityBox: required with entityMode \"replace-in-box\"");
            JsonObject b = ArgParse.requireObject(o.get("entityBox"), "entityBox");
            int[] from = BuildTransform.strictCoords(b, "from"), to = BuildTransform.strictCoords(b, "to");
            if (local) {
                int[][] bounds = t.bounds(from, to);
                from = bounds[0];
                to = bounds[1];
            }
            box = Region.of(from, to);
            if (box.volume() > 1_000_000) throw new ToolArgError("entityBox: at most 1,000,000 blocks");
        } else if (ArgParse.has(o, "entityBox")) {
            throw new ToolArgError("entityBox: only with entityMode \"replace-in-box\"");
        }
        return new EntityArgs(out, box);
    }

    private static EntitySpec entry(JsonObject e, String what, BuildTransform t, boolean local) {
        String type = ArgParse.requireEnum(e, "type", EntitySpec.TYPES);
        Set<String> keys = switch (type) {
            case EntitySpec.PAINTING -> PAINTING;
            case EntitySpec.ARMOR_STAND -> STAND;
            default -> FRAME;
        };
        for (String k : e.keySet()) {
            if (!keys.contains(k)) throw new ToolArgError(what + "." + k + ": not a " + type + " parameter (" + String.join(", ", keys.stream().sorted().toList()) + ")");
        }
        int[] pos = BuildTransform.strictCoords(e, "pos");
        if (local) pos = t.position(pos);
        boolean mirrored = local && !t.mirror().equals("none");
        switch (type) {
            case EntitySpec.PAINTING -> {
                String facing = turn(t, local, ArgParse.requireEnum(e, "facing", EntityGeometry.HORIZONTAL));
                String variant = id(ArgParse.requireString(e, "variant"), what + ".variant");
                return new EntitySpec(type, pos, null, facing, mirrored, variant, null, 0, false, false, 0f, null, null, false, false, true);
            }
            case EntitySpec.ARMOR_STAND -> {
                float yaw = (float) number(e, "yaw", what, -360, 360, 0);
                if (local) yaw = EntityGeometry.transformYaw(yaw, t.rotation(), t.mirror());
                Map<String, String> eq = new LinkedHashMap<>();
                if (ArgParse.has(e, "equipment")) {
                    JsonObject q = ArgParse.requireObject(e.get("equipment"), what + ".equipment");
                    for (var x : q.entrySet()) {
                        if (!EntitySpec.SLOTS.contains(x.getKey())) throw new ToolArgError(what + ".equipment." + x.getKey() + ": slots are " + String.join(", ", EntitySpec.SLOTS));
                        eq.put(x.getKey(), id(string(x.getValue(), what + ".equipment." + x.getKey()), what + ".equipment." + x.getKey()));
                    }
                }
                Map<String, double[]> pose = new LinkedHashMap<>();
                if (ArgParse.has(e, "pose")) {
                    JsonObject q = ArgParse.requireObject(e.get("pose"), what + ".pose");
                    for (var x : q.entrySet()) {
                        if (!EntitySpec.PARTS.contains(x.getKey())) throw new ToolArgError(what + ".pose." + x.getKey() + ": parts are " + String.join(", ", EntitySpec.PARTS));
                        pose.put(x.getKey(), angles(x.getValue(), what + ".pose." + x.getKey()));
                    }
                }
                return new EntitySpec(type, pos, null, null, false, null, null, 0, false, ArgParse.optBoolean(e, "invisible", false), yaw, eq, pose,
                        ArgParse.optBoolean(e, "small", false), ArgParse.optBoolean(e, "noGravity", false), true);
            }
            default -> {
                String facing = turn(t, local, ArgParse.requireEnum(e, "facing", EntityGeometry.ALL));
                String item = ArgParse.has(e, "item") ? id(ArgParse.requireString(e, "item"), what + ".item") : null;
                int rotation = (int) number(e, "rotation", what, 0, 7, 0);
                if (ArgParse.has(e, "rotation") && rotation != e.get("rotation").getAsDouble()) throw new ToolArgError(what + ".rotation: an integer 0-7");
                return new EntitySpec(type, pos, null, facing, false, null, item, rotation, ArgParse.optBoolean(e, "fixed", true),
                        ArgParse.optBoolean(e, "invisible", false), 0f, null, null, false, false, true);
            }
        }
    }

    /** Horizontal facings turn with the transform; up and down stay. */
    private static String turn(BuildTransform t, boolean local, String facing) {
        return local ? t.direction(facing) : facing;
    }

    static String id(String s, String what) {
        String v = s.trim();
        if (v.isEmpty() || v.length() > 128 || !ID.matcher(v).matches()) throw new ToolArgError(what + ": \"" + s + "\" is not an id like minecraft:clock");
        return v.contains(":") ? v : "minecraft:" + v;
    }

    private static String string(JsonElement el, String what) {
        if (!el.isJsonPrimitive() || !el.getAsJsonPrimitive().isString()) throw new ToolArgError(what + ": must be a string");
        return el.getAsString();
    }

    private static double number(JsonObject o, String field, String what, double min, double max, double def) {
        if (!ArgParse.has(o, field)) return def;
        JsonElement el = o.get(field);
        if (!el.isJsonPrimitive() || !el.getAsJsonPrimitive().isNumber()) throw new ToolArgError(what + "." + field + ": must be a number");
        double d = el.getAsDouble();
        if (!Double.isFinite(d) || d < min || d > max) throw new ToolArgError(what + "." + field + ": must be between " + (long) min + " and " + (long) max);
        return d;
    }

    private static double[] angles(JsonElement el, String what) {
        if (!el.isJsonArray() || el.getAsJsonArray().size() != 3) throw new ToolArgError(what + ": must be [x, y, z] degrees");
        double[] d = new double[3];
        for (int i = 0; i < 3; i++) {
            JsonElement v = el.getAsJsonArray().get(i);
            if (!v.isJsonPrimitive() || !v.getAsJsonPrimitive().isNumber() || !Double.isFinite(v.getAsDouble()) || Math.abs(v.getAsDouble()) > 360) {
                throw new ToolArgError(what + ": angles must be numbers between -360 and 360");
            }
            d[i] = v.getAsDouble();
        }
        return d;
    }

    /** Cells every entity occupies or hangs on (paintings: their named cell; sizes are known only on the server). */
    List<int[]> cells() {
        List<int[]> out = new ArrayList<>();
        for (EntitySpec s : entities) out.add(s.cell());
        return out;
    }

    /** A box around every entity and the replace box, padded for painting sizes (at most 4 wide and high). */
    Region bounds() {
        int[] min = {Integer.MAX_VALUE, Integer.MAX_VALUE, Integer.MAX_VALUE}, max = {Integer.MIN_VALUE, Integer.MIN_VALUE, Integer.MIN_VALUE};
        for (EntitySpec s : entities) {
            int pad = s.type().equals(EntitySpec.PAINTING) ? 4 : 1;
            for (int i = 0; i < 3; i++) {
                min[i] = Math.min(min[i], s.cell()[i] - pad);
                max[i] = Math.max(max[i], s.cell()[i] + pad);
            }
        }
        if (replaceBox != null) {
            min = new int[]{Math.min(min[0], replaceBox.minX()), Math.min(min[1], replaceBox.minY()), Math.min(min[2], replaceBox.minZ())};
            max = new int[]{Math.max(max[0], replaceBox.maxX()), Math.max(max[1], replaceBox.maxY()), Math.max(max[2], replaceBox.maxZ())};
        }
        return Region.of(min, max);
    }
}
