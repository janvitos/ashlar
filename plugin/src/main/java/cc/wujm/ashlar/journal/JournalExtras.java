// SPDX-License-Identifier: AGPL-3.0-or-later
package cc.wujm.ashlar.journal;

import cc.wujm.ashlar.engine.EntitySpec;
import cc.wujm.ashlar.engine.Region;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * What one journalled call did besides block states (Step 17): sign text before and after, and
 * decorative entities it spawned, removed or changed. Pure Java; stored next to the cells as
 * {@code <id>.extra.json.gz}. Sign values are {@code SignSnapshot#json()} objects.
 */
public final class JournalExtras {

    public record Sign(int x, int y, int z, JsonObject before, JsonObject after) {}

    public record Spawned(UUID id, EntitySpec spec) {}

    public record Changed(UUID id, EntitySpec before, EntitySpec after) {}

    private final Map<Long, JsonObject[]> signs = new LinkedHashMap<>();
    private final List<Spawned> spawned = new ArrayList<>();
    private final List<EntitySpec> removed = new ArrayList<>();
    private final List<Changed> changed = new ArrayList<>();

    /** Records a sign's text the first time the call touches it. Returns false when already recorded. */
    public boolean signBefore(int x, int y, int z, JsonObject before) {
        return signs.putIfAbsent(JournalRecorder.pack(x, y, z), new JsonObject[]{before, null}) == null;
    }

    /** Positions whose final sign text is still to be read. */
    public List<Long> signKeys() {
        return List.copyOf(signs.keySet());
    }

    public void signAfter(long key, JsonObject after) {
        JsonObject[] v = signs.get(key);
        if (v != null) v[1] = after;
    }

    public void spawned(UUID id, EntitySpec spec) {
        spawned.add(new Spawned(id, spec));
    }

    public void removed(EntitySpec spec) {
        removed.add(spec);
    }

    public void changed(UUID id, EntitySpec before, EntitySpec after) {
        changed.add(new Changed(id, before, after));
    }

    /** Signs whose text actually changed (or whose sign was replaced by another block). */
    public List<Sign> signs() {
        List<Sign> out = new ArrayList<>();
        for (var e : signs.entrySet()) {
            JsonObject[] v = e.getValue();
            if (v[0] == null || v[0].equals(v[1])) continue;
            long k = e.getKey();
            out.add(new Sign(JournalRecorder.x(k), JournalRecorder.y(k), JournalRecorder.z(k), v[0], v[1]));
        }
        return out;
    }

    public List<Spawned> spawnedList() {
        return List.copyOf(spawned);
    }

    public List<EntitySpec> removedList() {
        return List.copyOf(removed);
    }

    public List<Changed> changedList() {
        return List.copyOf(changed);
    }

    public int size() {
        return signs().size() + spawned.size() + removed.size() + changed.size();
    }

    public boolean isEmpty() {
        return size() == 0;
    }

    /** Every cell these records refer to (for bounds and protected-region checks). */
    public List<int[]> cells() {
        List<int[]> out = new ArrayList<>();
        for (Sign s : signs()) out.add(new int[]{s.x(), s.y(), s.z()});
        for (Spawned s : spawned) out.add(s.spec().cell());
        for (EntitySpec s : removed) out.add(s.cell());
        for (Changed c : changed) out.add(c.before().cell());
        return out;
    }

    /** {@code base} grown to include {@link #cells()}; {@code base} may be null. */
    public Region bounds(Region base) {
        Region r = base;
        for (int[] c : cells()) {
            r = r == null ? new Region(c[0], c[1], c[2], c[0], c[1], c[2]) : new Region(Math.min(r.minX(), c[0]), Math.min(r.minY(), c[1]),
                    Math.min(r.minZ(), c[2]), Math.max(r.maxX(), c[0]), Math.max(r.maxY(), c[1]), Math.max(r.maxZ(), c[2]));
        }
        return r;
    }

    public JsonObject toJson() {
        JsonObject o = new JsonObject();
        JsonArray s = new JsonArray();
        for (Sign g : signs()) {
            JsonObject e = new JsonObject();
            JsonArray p = new JsonArray();
            p.add(g.x());
            p.add(g.y());
            p.add(g.z());
            e.add("pos", p);
            e.add("before", g.before());
            if (g.after() != null) e.add("after", g.after());
            s.add(e);
        }
        o.add("signs", s);
        JsonArray sp = new JsonArray();
        for (Spawned x : spawned) {
            JsonObject e = new JsonObject();
            e.addProperty("uuid", x.id().toString());
            e.add("spec", x.spec().json());
            sp.add(e);
        }
        o.add("spawned", sp);
        JsonArray rm = new JsonArray();
        for (EntitySpec x : removed) rm.add(x.json());
        o.add("removed", rm);
        JsonArray ch = new JsonArray();
        for (Changed x : changed) {
            JsonObject e = new JsonObject();
            e.addProperty("uuid", x.id().toString());
            e.add("before", x.before().json());
            e.add("after", x.after().json());
            ch.add(e);
        }
        o.add("changed", ch);
        return o;
    }

    public static JournalExtras fromJson(JsonObject o) {
        JournalExtras x = new JournalExtras();
        for (JsonElement el : o.getAsJsonArray("signs")) {
            JsonObject e = el.getAsJsonObject();
            JsonArray p = e.getAsJsonArray("pos");
            long k = JournalRecorder.pack(p.get(0).getAsInt(), p.get(1).getAsInt(), p.get(2).getAsInt());
            x.signs.put(k, new JsonObject[]{e.getAsJsonObject("before"), e.has("after") ? e.getAsJsonObject("after") : null});
        }
        for (JsonElement el : o.getAsJsonArray("spawned")) {
            JsonObject e = el.getAsJsonObject();
            x.spawned.add(new Spawned(UUID.fromString(e.get("uuid").getAsString()), EntitySpec.fromJson(e.getAsJsonObject("spec"))));
        }
        for (JsonElement el : o.getAsJsonArray("removed")) x.removed.add(EntitySpec.fromJson(el.getAsJsonObject()));
        for (JsonElement el : o.getAsJsonArray("changed")) {
            JsonObject e = el.getAsJsonObject();
            x.changed.add(new Changed(UUID.fromString(e.get("uuid").getAsString()), EntitySpec.fromJson(e.getAsJsonObject("before")),
                    EntitySpec.fromJson(e.getAsJsonObject("after"))));
        }
        return x;
    }
}
