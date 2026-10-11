// SPDX-License-Identifier: AGPL-3.0-or-later
package cc.wujm.ashlar.engine;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * One decorative entity (painting, item frame, glow item frame or armor stand) as plain data, used
 * by {@code mc_build entities}, snapshots and the journal (Step 17). Pure Java.
 *
 * <p>{@code cell} is the block the entity occupies: for a painting its lower-left cell as seen by
 * someone facing it ({@code fromRight}: the lower-right cell instead, after a mirrored transform);
 * for a frame the cell in front of its wall or floor; for an armor stand the cell of its feet.
 * {@code at} is an armor stand's exact feet position ({@code null}: centre of {@code cell}).
 * {@code facing} is the direction the painting or frame faces, away from its support.
 */
public record EntitySpec(String type, int[] cell, double[] at, String facing, boolean fromRight, String variant, String item,
        int rotation, boolean fixed, boolean invisible, float yaw, Map<String, String> equipment, Map<String, double[]> pose,
        boolean small, boolean noGravity, boolean placed) {

    public static final String PAINTING = "painting", ITEM_FRAME = "item_frame", GLOW_ITEM_FRAME = "glow_item_frame",
            ARMOR_STAND = "armor_stand";
    public static final List<String> TYPES = List.of(PAINTING, ITEM_FRAME, GLOW_ITEM_FRAME, ARMOR_STAND);
    public static final List<String> SLOTS = List.of("head", "chest", "legs", "feet", "mainHand", "offHand");
    public static final List<String> PARTS = List.of("head", "body", "leftArm", "rightArm", "leftLeg", "rightLeg");

    public EntitySpec {
        cell = cell.clone();
        at = at == null ? null : at.clone();
        equipment = equipment == null ? Map.of() : Map.copyOf(equipment);
        pose = pose == null ? Map.of() : Map.copyOf(pose);
    }

    public boolean frame() {
        return type.equals(ITEM_FRAME) || type.equals(GLOW_ITEM_FRAME);
    }

    /** Same entity for matching a snapshot or journal record to a live one: type, cell and facing. */
    public String key() {
        return type + "@" + cell[0] + "," + cell[1] + "," + cell[2] + (facing == null ? "" : "/" + facing);
    }

    public EntitySpec withPlaced(boolean p) {
        return new EntitySpec(type, cell, at, facing, fromRight, variant, item, rotation, fixed, invisible, yaw, equipment, pose, small,
                noGravity, p);
    }

    /** Equal decorative values (ignores {@code placed} and sub-block armor-stand drift below 1/1000). */
    public boolean sameAs(EntitySpec o) {
        if (o == null || !key().equals(o.key()) || fromRight != o.fromRight) return false;
        if (!java.util.Objects.equals(variant, o.variant) || !java.util.Objects.equals(item, o.item)) return false;
        if (rotation != o.rotation || fixed != o.fixed || invisible != o.invisible || small != o.small || noGravity != o.noGravity) return false;
        if (!type.equals(ARMOR_STAND)) return true;
        if (Math.abs(EntityGeometry.wrapYaw(yaw - o.yaw)) > 0.01 || !equipment.equals(o.equipment)) return false;
        double[] a = exact(), b = o.exact();
        for (int i = 0; i < 3; i++) if (Math.abs(a[i] - b[i]) > 1e-3) return false;
        if (!pose.keySet().equals(o.pose.keySet())) return false;
        for (var e : pose.entrySet()) {
            double[] p = e.getValue(), q = o.pose.get(e.getKey());
            for (int i = 0; i < 3; i++) if (Math.abs(EntityGeometry.wrapYaw(p[i] - q[i])) > 0.01) return false;
        }
        return true;
    }

    /** An armor stand's feet position: {@code at}, else the centre of {@code cell}. */
    public double[] exact() {
        return at != null ? at.clone() : new double[]{cell[0] + 0.5, cell[1], cell[2] + 0.5};
    }

    public JsonObject json() {
        JsonObject o = new JsonObject();
        o.addProperty("type", type);
        o.add("pos", ints(cell));
        if (at != null) {
            JsonArray a = new JsonArray();
            for (double d : at) a.add(d);
            o.add("at", a);
        }
        if (facing != null) o.addProperty("facing", facing);
        if (fromRight) o.addProperty("fromRight", true);
        if (variant != null) o.addProperty("variant", variant);
        if (item != null) o.addProperty("item", item);
        if (frame()) {
            o.addProperty("rotation", rotation);
            o.addProperty("fixed", fixed);
        }
        if (invisible) o.addProperty("invisible", true);
        if (type.equals(ARMOR_STAND)) {
            o.addProperty("yaw", yaw);
            if (!equipment.isEmpty()) {
                JsonObject e = new JsonObject();
                for (String s : SLOTS) if (equipment.containsKey(s)) e.addProperty(s, equipment.get(s));
                o.add("equipment", e);
            }
            if (!pose.isEmpty()) {
                JsonObject p = new JsonObject();
                for (String s : PARTS) {
                    if (!pose.containsKey(s)) continue;
                    JsonArray a = new JsonArray();
                    for (double d : pose.get(s)) a.add(d);
                    p.add(s, a);
                }
                o.add("pose", p);
            }
            if (small) o.addProperty("small", true);
            if (noGravity) o.addProperty("noGravity", true);
        }
        if (placed) o.addProperty("placed", true);
        return o;
    }

    /** Reads {@link #json()} output (stored snapshots and journal entries; already validated when written). */
    public static EntitySpec fromJson(JsonObject o) {
        JsonArray p = o.getAsJsonArray("pos");
        int[] cell = {p.get(0).getAsInt(), p.get(1).getAsInt(), p.get(2).getAsInt()};
        double[] at = null;
        if (o.has("at")) {
            JsonArray a = o.getAsJsonArray("at");
            at = new double[]{a.get(0).getAsDouble(), a.get(1).getAsDouble(), a.get(2).getAsDouble()};
        }
        Map<String, String> equipment = new LinkedHashMap<>();
        if (o.has("equipment")) for (var e : o.getAsJsonObject("equipment").entrySet()) equipment.put(e.getKey(), e.getValue().getAsString());
        Map<String, double[]> pose = new LinkedHashMap<>();
        if (o.has("pose")) {
            for (var e : o.getAsJsonObject("pose").entrySet()) {
                JsonArray a = e.getValue().getAsJsonArray();
                pose.put(e.getKey(), new double[]{a.get(0).getAsDouble(), a.get(1).getAsDouble(), a.get(2).getAsDouble()});
            }
        }
        return new EntitySpec(o.get("type").getAsString(), cell, at, str(o, "facing"), bool(o, "fromRight", false), str(o, "variant"),
                str(o, "item"), o.has("rotation") ? o.get("rotation").getAsInt() : 0, bool(o, "fixed", false), bool(o, "invisible", false),
                o.has("yaw") ? o.get("yaw").getAsFloat() : 0f, equipment, pose, bool(o, "small", false), bool(o, "noGravity", false),
                bool(o, "placed", false));
    }

    private static String str(JsonObject o, String k) {
        JsonElement e = o.get(k);
        return e == null || e.isJsonNull() ? null : e.getAsString();
    }

    private static boolean bool(JsonObject o, String k, boolean def) {
        JsonElement e = o.get(k);
        return e == null || e.isJsonNull() ? def : e.getAsBoolean();
    }

    private static JsonArray ints(int[] v) {
        JsonArray a = new JsonArray();
        for (int i : v) a.add(i);
        return a;
    }

    /** Short human-readable form for reports. */
    public String describe() {
        StringBuilder sb = new StringBuilder(type).append(" at ").append(cell[0]).append(',').append(cell[1]).append(',').append(cell[2]);
        if (facing != null) sb.append(" facing ").append(facing);
        if (variant != null) sb.append(' ').append(variant);
        if (item != null) sb.append(" holding ").append(item);
        if (type.equals(ARMOR_STAND)) sb.append(" yaw ").append(Math.round(yaw));
        return sb.toString();
    }
}
