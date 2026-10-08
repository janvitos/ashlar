// SPDX-License-Identifier: AGPL-3.0-or-later
package cc.wujm.ashlar.tool.mc;

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

/** Pure, bounded expansion of flat named components into existing mc_build operations. */
public final class BlueprintCompiler {
    public static final int MAX_COMPONENTS = 128;
    public static final int MAX_INSTANCES = 4096;
    public static final int MAX_RAW_OPS = 10000;
    public static final int MAX_EXPANDED_OPS = 100000;
    private static final Pattern NAME = Pattern.compile("[a-z][a-z0-9_-]{0,63}");
    private static final Pattern ROLE = Pattern.compile("\\$([a-z][a-z0-9_-]{0,63})(\\[[^\\[\\]]*\\])?");
    private static final List<String> KINDS = List.of("fills", "blocks", "text");

    private BlueprintCompiler() {}

    public record Limits(long blocks, long chunks, long flowingLiquids) {
        public static final Limits DEFAULT = new Limits(500000, 1024, 2000);
    }

    public static void name(String name) {
        if (!NAME.matcher(name).matches()) throw new ToolArgError("blueprint: invalid name '" + name + "'; use lowercase letters, digits, _ or - (1-64 characters, starting with a letter)");
    }

    static void fields(JsonObject object, Set<String> allowed, String path) {
        for (String key : object.keySet()) if (!allowed.contains(key))
            throw new ToolArgError(path + ": unknown field '" + key + "'");
    }

    /** Validate storage syntax even for unused components; material-role bindings may be supplied at build time. */
    public static void validate(JsonObject doc) {
        fields(doc, Set.of("version", "description", "dimensions", "palette", "constraints", "components", "instances"), "blueprint");
        if (!doc.has("version") || BuildTransform.strictInt(doc.get("version"), "version") != 1)
            throw new ToolArgError("blueprint.version: must be 1");
        String description = ArgParse.optString(doc, "description");
        if (description != null && description.length() > 4096) throw new ToolArgError("blueprint.description: maximum 4096 characters");
        if (ArgParse.has(doc, "dimensions")) for (int dimension : BuildTransform.strictCoords(doc, "dimensions"))
            if (dimension < 1) throw new ToolArgError("blueprint.dimensions: must be positive");
        if (ArgParse.has(doc, "constraints")) ArgParse.requireObject(doc.get("constraints"), "constraints");
        palette(doc, "palette");
        JsonObject components = ArgParse.requireObject(doc.get("components"), "components");
        if (components.size() < 1 || components.size() > MAX_COMPONENTS) throw new ToolArgError("blueprint.components: must contain 1-" + MAX_COMPONENTS + " components");
        int rawOps = 0;
        for (var entry : components.entrySet()) {
            name(entry.getKey());
            JsonObject component = ArgParse.requireObject(entry.getValue(), "component " + entry.getKey());
            fields(component, Set.of("palette", "fills", "blocks", "text"), "component " + entry.getKey());
            palette(component, "palette");
            for (String kind : KINDS) if (ArgParse.has(component, kind)) rawOps += ArgParse.requireArray(component, kind).size();
            if (rawOps > MAX_RAW_OPS) throw new ToolArgError("blueprint: maximum " + MAX_RAW_OPS + " raw operations");
            JsonObject syntactic = materialize(component, Map.of(), true);
            syntactic.add("transform", transformJson(BuildTransform.IDENTITY));
            McBuild.Args.parse(syntactic);
        }
        JsonArray instances = ArgParse.requireArray(doc, "instances");
        if (instances.isEmpty() || instances.size() > MAX_INSTANCES) throw new ToolArgError("blueprint.instances: must contain 1-" + MAX_INSTANCES + " entries");
        long count = 0;
        for (JsonElement element : instances) {
            JsonObject instance = ArgParse.requireObject(element, "instance");
            fields(instance, Set.of("component", "pos", "rotation", "mirror", "palette", "repeat"), "instance");
            String component = ArgParse.requireString(instance, "component");
            if (!components.has(component)) throw new ToolArgError("blueprint: unknown component '" + component + "'");
            instanceTransform(instance, new int[] {0, 0, 0});
            palette(instance, "palette");
            count += repeatCount(instance);
            if (count > MAX_INSTANCES) throw new ToolArgError("blueprint: maximum " + MAX_INSTANCES + " expanded instances");
        }
    }

    static List<McBuild.Part> compile(JsonObject doc, JsonObject request, Limits limits) {
        validate(doc);
        JsonObject selector = ArgParse.requireObject(request.get("blueprint"), "blueprint selector");
        fields(selector, Set.of("id", "palette"), "blueprint selector");
        name(ArgParse.requireString(selector, "id"));
        BuildTransform project = BuildTransform.parse(request);
        Map<String, String> projectPalette = palette(doc, "palette");
        projectPalette.putAll(palette(selector, "palette"));
        JsonObject components = doc.getAsJsonObject("components");
        List<McBuild.Part> result = new ArrayList<>();
        long volume = 0, flow = 0, operations = 0;
        int[] min = {Integer.MAX_VALUE, Integer.MAX_VALUE, Integer.MAX_VALUE};
        int[] max = {Integer.MIN_VALUE, Integer.MIN_VALUE, Integer.MIN_VALUE};
        for (JsonElement element : doc.getAsJsonArray("instances")) {
            JsonObject instance = element.getAsJsonObject();
            JsonObject component = components.getAsJsonObject(instance.get("component").getAsString());
            Map<String, String> materials = palette(component, "palette");
            materials.putAll(projectPalette);
            materials.putAll(palette(instance, "palette"));
            int count = repeatCount(instance);
            int[] step = count == 1 && !ArgParse.has(instance, "repeat") ? new int[] {0, 0, 0}
                    : BuildTransform.strictCoords(instance.getAsJsonObject("repeat"), "step");
            for (int i = 0; i < count; i++) {
                int[] offset = new int[3];
                try { for (int a = 0; a < 3; a++) offset[a] = Math.toIntExact((long) step[a] * i); }
                catch (ArithmeticException e) { throw new ToolArgError("blueprint.repeat: offset exceeds signed 32-bit range"); }
                BuildTransform transform = project.compose(instanceTransform(instance, offset));
                JsonObject leaf = materialize(component, materials, false);
                leaf.add("transform", transformJson(transform));
                for (String option : List.of("world", "snapshot", "connect", "liquids"))
                    if (request.has(option)) leaf.add(option, request.get(option).deepCopy());
                McBuild.Args args = McBuild.Args.parse(leaf);
                try {
                    for (McBuild.FillOpArg f : args.fills()) {
                        long v = volume(f.from(), f.to());
                        volume = Math.addExact(volume, v);
                        if (liquid(f.block())) flow = Math.addExact(flow, v);
                        consider(min, max, f.from()); consider(min, max, f.to());
                    }
                    for (McBuild.SparseOpArg b : args.blocks()) {
                        volume = Math.addExact(volume, 1);
                        if (liquid(b.block())) flow++;
                        consider(min, max, b.pos());
                    }
                    operations = Math.addExact(operations, args.fills().size() + args.blocks().size());
                    for (McBuild.TextArg t : args.text()) {
                        long v = t.expanded().inkBlockCount();
                        if (t.background() != null) v = Math.addExact(v, volume(t.expanded().bboxMin(), t.expanded().bboxMax()));
                        volume = Math.addExact(volume, v);
                        if (liquid(t.block())) flow = Math.addExact(flow, t.expanded().inkBlockCount());
                        if (liquid(t.background())) flow = Math.addExact(flow, volume(t.expanded().bboxMin(), t.expanded().bboxMax()));
                        operations = Math.addExact(operations, t.expanded().inkRuns().size() + (t.background() != null ? 1 : 0));
                        consider(min, max, t.expanded().bboxMin()); consider(min, max, t.expanded().bboxMax());
                    }
                } catch (ArithmeticException e) { throw new ToolArgError("blueprint: expanded volume overflow"); }
                if (volume > limits.blocks()) throw new ToolArgError("blueprint: expanded requested volume " + volume + " exceeds limit " + limits.blocks());
                if (operations > MAX_EXPANDED_OPS) throw new ToolArgError("blueprint: expanded operations exceed " + MAX_EXPANDED_OPS);
                if ("flow".equals(args.liquids()) && flow > limits.flowingLiquids()) throw new ToolArgError("blueprint: flowing liquid volume exceeds limit " + limits.flowingLiquids());
                long chunks = ((long) Math.floorDiv(max[0], 16) - Math.floorDiv(min[0], 16) + 1)
                        * ((long) Math.floorDiv(max[2], 16) - Math.floorDiv(min[2], 16) + 1);
                if (chunks > limits.chunks()) throw new ToolArgError("blueprint: union chunk footprint " + chunks + " exceeds limit " + limits.chunks());
                result.add(new McBuild.Part(args, transform));
            }
        }
        return List.copyOf(result);
    }

    private static boolean liquid(String block) {
        return block != null && (block.matches("(?:minecraft:)?water(?:\\[.*\\])?") || block.matches("(?:minecraft:)?lava(?:\\[.*\\])?"));
    }

    private static long volume(int[] a, int[] b) {
        long v = 1;
        for (int i = 0; i < 3; i++) v = Math.multiplyExact(v, Math.abs((long) a[i] - b[i]) + 1);
        return v;
    }

    private static void consider(int[] min, int[] max, int[] p) {
        for (int i = 0; i < 3; i++) { min[i] = Math.min(min[i], p[i]); max[i] = Math.max(max[i], p[i]); }
    }

    private static int repeatCount(JsonObject instance) {
        if (!ArgParse.has(instance, "repeat")) return 1;
        JsonObject repeat = ArgParse.requireObject(instance.get("repeat"), "repeat");
        fields(repeat, Set.of("count", "step"), "repeat");
        int count = BuildTransform.strictInt(repeat.get("count"), "repeat.count");
        if (count < 1 || count > MAX_INSTANCES) throw new ToolArgError("repeat.count: must be 1-" + MAX_INSTANCES);
        BuildTransform.strictCoords(repeat, "step");
        return count;
    }

    private static BuildTransform instanceTransform(JsonObject instance, int[] offset) {
        JsonObject t = new JsonObject();
        int[] pos = BuildTransform.strictCoords(instance, "pos");
        try { for (int a = 0; a < 3; a++) pos[a] = Math.addExact(pos[a], offset[a]); }
        catch (ArithmeticException e) { throw new ToolArgError("instance: repeated position overflow"); }
        t.add("origin", JsonUtil.intArray(pos));
        for (String field : List.of("rotation", "mirror")) if (instance.has(field)) t.add(field, instance.get(field));
        JsonObject wrapper = new JsonObject(); wrapper.add("transform", t);
        return BuildTransform.parse(wrapper);
    }

    static JsonObject transformJson(BuildTransform t) {
        JsonObject o = new JsonObject();
        o.add("origin", JsonUtil.intArray(t.originX(), t.originY(), t.originZ()));
        o.addProperty("rotation", t.rotation()); o.addProperty("mirror", t.mirror());
        return o;
    }

    private static Map<String, String> palette(JsonObject object, String key) {
        Map<String, String> result = new LinkedHashMap<>();
        if (!ArgParse.has(object, key)) return result;
        JsonObject palette = ArgParse.requireObject(object.get(key), key);
        if (palette.size() > 128) throw new ToolArgError("palette: maximum 128 roles");
        for (var entry : palette.entrySet()) {
            name(entry.getKey());
            String value = ArgParse.requireString(palette, entry.getKey());
            if (value.isBlank() || value.startsWith("$")) throw new ToolArgError("palette: values must be literal block states, not role references");
            result.put(entry.getKey(), value);
        }
        return result;
    }

    private static JsonObject materialize(JsonObject component, Map<String, String> palette, boolean syntaxOnly) {
        JsonObject result = new JsonObject();
        for (String kind : KINDS) if (ArgParse.has(component, kind)) {
            JsonArray ops = component.getAsJsonArray(kind).deepCopy();
            for (JsonElement element : ops) {
                JsonObject op = ArgParse.requireObject(element, kind);
                fields(op, switch (kind) {
                    case "fills" -> Set.of("from", "to", "block", "mode", "filter");
                    case "blocks" -> Set.of("pos", "block", "sign");
                    default -> Set.of("text", "pos", "block", "background", "facing", "align", "scale", "spacing");
                }, "component." + kind);
                if (kind.equals("text")) for (String number : List.of("scale", "spacing"))
                    if (ArgParse.has(op, number)) BuildTransform.strictInt(op.get(number), number);
                if (ArgParse.has(op, "sign")) {
                    JsonObject sign = ArgParse.requireObject(op.get("sign"), "sign");
                    fields(sign, Set.of("front", "back", "color", "glowing", "waxed"), "sign");
                    if (!ArgParse.has(sign, "front") && !ArgParse.has(sign, "back"))
                        throw new ToolArgError("sign: front and/or back lines required");
                }
                for (String field : List.of("block", "filter", "background")) if (ArgParse.has(op, field)) {
                    String raw = ArgParse.requireString(op, field);
                    if (raw.startsWith("$")) {
                        var match = ROLE.matcher(raw);
                        if (!match.matches()) throw new ToolArgError("palette: malformed role reference '" + raw + "'");
                        String material = syntaxOnly ? "minecraft:stone" : palette.get(match.group(1));
                        if (material == null) throw new ToolArgError("palette: unbound role '" + match.group(1) + "'");
                        op.addProperty(field, mergeProperties(material, match.group(2)));
                    }
                }
            }
            result.add(kind, ops);
        }
        return result;
    }

    /** Inline properties override palette properties; never substitute sign strings or lettering content. */
    static String mergeProperties(String block, String suffix) {
        if (suffix == null) return block;
        int bracket = block.indexOf('[');
        String material = bracket < 0 ? block : block.substring(0, bracket);
        Map<String, String> props = new LinkedHashMap<>();
        if (bracket >= 0) properties(props, block.substring(bracket), false);
        properties(props, suffix, true);
        return props.isEmpty() ? material : material + "[" + String.join(",", props.entrySet().stream().map(e -> e.getKey() + "=" + e.getValue()).toList()) + "]";
    }

    private static void properties(Map<String, String> result, String bracketed, boolean override) {
        if (!bracketed.endsWith("]")) throw new ToolArgError("palette: malformed block properties");
        String body = bracketed.substring(1, bracketed.length() - 1);
        if (body.isEmpty()) return;
        Set<String> seen = new java.util.HashSet<>();
        for (String property : body.split(",", -1)) {
            if (!property.matches("[a-z_]+=[a-z0-9_]+")) throw new ToolArgError("palette: malformed block property '" + property + "'");
            String[] pair = property.split("=", 2);
            if (!seen.add(pair[0])) throw new ToolArgError("palette: duplicate block property '" + pair[0] + "'");
            if (override || !result.containsKey(pair[0])) result.put(pair[0], pair[1]);
        }
    }
}
