// SPDX-License-Identifier: AGPL-3.0-or-later
package cc.wujm.ashlar.engine;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import org.bukkit.Chunk;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.BlockState;
import org.bukkit.block.Sign;
import org.bukkit.entity.Entity;

import java.util.HashMap;
import java.util.Map;

/**
 * Sign text and decorative entities of a snapshot box (Step 17). {@link #capture} reads them right
 * after the block read; {@link #apply} puts them back right after a block restore: every recorded
 * sign gets its text, recorded entities are respawned or reset, and Ashlar-placed entities that
 * were not there are removed (other entities are kept and counted). Changes are journalled.
 */
public final class SnapshotExtrasTask extends BuildTask {

    /** Upper bound on recorded signs plus entities per snapshot. */
    public static final int MAX_RECORDS = 20_000;

    private final World world;
    private int waits;
    private final JsonObject extras;
    private JsonObject captured;
    private int signs, spawned, reset, removed, kept, failed;
    private final JsonArray failures = new JsonArray();

    private SnapshotExtrasTask(Region region, World world, JsonObject extras) {
        super(region);
        this.world = world;
        this.extras = extras;
    }

    public static SnapshotExtrasTask capture(Region region, World world) {
        return new SnapshotExtrasTask(region, world, null);
    }

    public static SnapshotExtrasTask apply(Region region, World world, JsonObject extras) {
        return new SnapshotExtrasTask(region, world, extras);
    }

    /** The captured {@code {signs, entities}} object after a capture run. */
    public JsonObject captured() {
        return captured;
    }

    @Override
    public World world() {
        return world;
    }

    @Override
    public long volume() {
        return 1;
    }

    @Override
    public boolean step(long deadlineNanos) {
        if (!EntityAccess.entitiesLoaded(world, region()) && waits++ < EntityAccess.ENTITY_WAIT_TICKS) return false;
        if (extras == null) {
            captured = read();
        } else {
            write();
        }
        advance(1);
        return true;
    }

    private JsonObject read() {
        Region r = region();
        JsonArray signArr = new JsonArray();
        boolean truncated = false;
        for (int cx = r.minX() >> 4; cx <= r.maxX() >> 4; cx++) {
            for (int cz = r.minZ() >> 4; cz <= r.maxZ() >> 4; cz++) {
                Chunk chunk = world.getChunkAt(cx, cz);
                for (BlockState st : chunk.getTileEntities(b -> b.getY() >= r.minY() && b.getY() <= r.maxY() && b.getX() >= r.minX()
                        && b.getX() <= r.maxX() && b.getZ() >= r.minZ() && b.getZ() <= r.maxZ(), false)) {
                    if (!(st instanceof Sign)) continue;
                    if (signArr.size() >= MAX_RECORDS) {
                        truncated = true;
                        break;
                    }
                    SignSnapshot s = SignAccess.read(st.getBlock());
                    if (s == null) continue;
                    JsonObject o = new JsonObject();
                    o.add("pos", pos(st.getX(), st.getY(), st.getZ()));
                    o.add("sign", s.json());
                    signArr.add(o);
                }
            }
        }
        JsonArray ents = new JsonArray();
        for (Entity e : EntityAccess.in(world, r)) {
            if (signArr.size() + ents.size() >= MAX_RECORDS) {
                truncated = true;
                break;
            }
            ents.add(EntityAccess.read(e).json());
        }
        JsonObject o = new JsonObject();
        o.add("signs", signArr);
        o.add("entities", ents);
        if (truncated) o.addProperty("truncated", true);
        signs = signArr.size();
        spawned = ents.size();
        return o;
    }

    private void write() {
        JournalCapture j = journal();
        for (JsonElement el : extras.getAsJsonArray("signs")) {
            JsonObject o = el.getAsJsonObject();
            JsonArray p = o.getAsJsonArray("pos");
            Block b = world.getBlockAt(p.get(0).getAsInt(), p.get(1).getAsInt(), p.get(2).getAsInt());
            SignSnapshot want = SignSnapshot.fromJson(o.getAsJsonObject("sign"));
            SignSnapshot live = SignAccess.read(b);
            if (live == null || live.equals(want)) continue;
            if (j != null) j.signBefore(b);
            SignAccess.apply(b, want);
            signs++;
        }
        Map<String, Entity> live = new HashMap<>();
        for (Entity e : EntityAccess.in(world, region())) live.putIfAbsent(EntityAccess.read(e).key(), e);
        for (JsonElement el : extras.getAsJsonArray("entities")) {
            EntitySpec s = EntitySpec.fromJson(el.getAsJsonObject());
            Entity e = live.remove(s.key());
            try {
                if (e != null) {
                    EntitySpec now = EntityAccess.read(e);
                    if (now.sameAs(s)) continue;
                    EntityAccess.update(e, s);
                    if (j != null) j.entityChanged(e, now, s);
                    reset++;
                } else {
                    Entity n = EntityAccess.spawn(world, s, s.placed() ? "1" : null);
                    if (j != null) j.entitySpawned(n, EntityAccess.read(n));
                    spawned++;
                }
            } catch (EntityAccess.Failure | IllegalArgumentException ex) {
                failed++;
                if (failures.size() < 10) failures.add(s.describe() + ": " + ex.getMessage());
            }
        }
        for (Entity e : live.values()) {
            if (!EntityAccess.placed(e)) {
                kept++;
                continue;
            }
            if (j != null) j.entityRemoved(world, EntityAccess.read(e));
            e.remove();
            removed++;
        }
    }

    private static JsonArray pos(int x, int y, int z) {
        JsonArray a = new JsonArray();
        a.add(x);
        a.add(y);
        a.add(z);
        return a;
    }

    @Override
    public JsonElement buildResult(long queuedMs, long elapsedMs) {
        JsonObject r = new JsonObject();
        r.addProperty("signs", signs);
        r.addProperty("entitiesSpawned", spawned);
        r.addProperty("entitiesReset", reset);
        r.addProperty("entitiesRemoved", removed);
        r.addProperty("entitiesKept", kept);
        r.addProperty("entitiesFailed", failed);
        r.add("failures", failures);
        return r;
    }

    /** Short report line for a restore, or null when nothing happened. */
    public static String text(JsonObject r) {
        long s = r.get("signs").getAsLong(), sp = r.get("entitiesSpawned").getAsLong(), rs = r.get("entitiesReset").getAsLong(),
                rm = r.get("entitiesRemoved").getAsLong(), k = r.get("entitiesKept").getAsLong(), f = r.get("entitiesFailed").getAsLong();
        if (s + sp + rs + rm + k + f == 0) return null;
        StringBuilder sb = new StringBuilder("Signs and entities: " + s + " sign texts restored, " + sp + " entities respawned, " + rs
                + " reset, " + rm + " Ashlar-placed removed");
        if (k > 0) sb.append(", ").append(k).append(" not placed by Ashlar kept");
        if (f > 0) sb.append(", ").append(f).append(" could not be respawned");
        sb.append('.');
        for (JsonElement e : r.getAsJsonArray("failures")) sb.append("\n  ").append(e.getAsString());
        return sb.toString();
    }
}
