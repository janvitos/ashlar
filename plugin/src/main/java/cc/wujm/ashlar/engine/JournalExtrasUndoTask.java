// SPDX-License-Identifier: AGPL-3.0-or-later
package cc.wujm.ashlar.engine;

import cc.wujm.ashlar.journal.JournalExtras;
import cc.wujm.ashlar.journal.UndoRules;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.entity.Entity;

/**
 * Undoes the sign and entity part of one journal entry (Step 17), right after its cells. Safe mode
 * only reverts what still looks the way the journalled call left it: spawned entities are removed,
 * removed ones respawned, changed ones reset, and sign text written back. Force mode reverts
 * regardless. Everything it does is journalled through the attached capture.
 */
public final class JournalExtrasUndoTask extends BuildTask {

    private final World world;
    private int waits;
    private final JournalExtras extras;
    private final UndoRules.Mode mode;
    private final boolean dryRun;
    private int removed, respawned, reset, signs, conflicts, gone, present, failed;
    private final JsonArray notes = new JsonArray();

    public JournalExtrasUndoTask(Region bounds, World world, JournalExtras extras, UndoRules.Mode mode, boolean dryRun) {
        super(bounds);
        this.world = world;
        this.extras = extras;
        this.mode = mode;
        this.dryRun = dryRun;
    }

    @Override
    public World world() {
        return world;
    }

    @Override
    public long volume() {
        return Math.max(1, extras.size());
    }

    private boolean force() {
        return mode == UndoRules.Mode.FORCE;
    }

    @Override
    public boolean step(long deadlineNanos) {
        if (!EntityAccess.entitiesLoaded(world, region()) && waits++ < EntityAccess.ENTITY_WAIT_TICKS) return false;
        JournalCapture j = journal();
        for (JournalExtras.Changed c : extras.changedList()) {
            Entity e = world.getEntity(c.id());
            if (e == null || e.isDead()) {
                gone++;
                continue;
            }
            EntitySpec live = EntityAccess.read(e);
            if (!live.sameAs(c.after()) && !force()) {
                conflict("changed since: " + live.describe());
                continue;
            }
            reset++;
            if (dryRun) continue;
            EntityAccess.update(e, c.before());
            if (j != null) j.entityChanged(e, live, c.before());
        }
        for (JournalExtras.Spawned s : extras.spawnedList()) {
            Entity e = world.getEntity(s.id());
            if (e == null || e.isDead()) {
                gone++;
                continue;
            }
            EntitySpec live = EntityAccess.read(e);
            if (!live.sameAs(s.spec()) && !force()) {
                conflict("changed since: " + live.describe());
                continue;
            }
            removed++;
            if (dryRun) continue;
            if (j != null) j.entityRemoved(world, live);
            e.remove();
        }
        for (EntitySpec s : extras.removedList()) {
            int[] c = s.cell();
            boolean there = false;
            for (Entity e : EntityAccess.in(world, new Region(c[0], c[1], c[2], c[0], c[1], c[2]))) {
                if (EntityAccess.read(e).key().equals(s.key())) there = true;
            }
            if (there) {
                present++;
                continue;
            }
            if (dryRun) {
                respawned++;
                continue;
            }
            try {
                Entity n = EntityAccess.spawn(world, s, s.placed() ? "1" : null);
                if (j != null) j.entitySpawned(n, EntityAccess.read(n));
                respawned++;
            } catch (EntityAccess.Failure | IllegalArgumentException ex) {
                failed++;
                note(s.describe() + ": " + ex.getMessage());
            }
        }
        for (JournalExtras.Sign s : extras.signs()) {
            Block b = world.getBlockAt(s.x(), s.y(), s.z());
            SignSnapshot live = SignAccess.read(b);
            if (live == null) {
                conflict("no sign at " + s.x() + "," + s.y() + "," + s.z() + " to put the text back on");
                continue;
            }
            JsonObject now = live.json();
            boolean asLeft = s.after() == null ? live.equals(SignSnapshot.empty()) : now.equals(s.after());
            if (!asLeft && !force()) {
                conflict("sign text at " + s.x() + "," + s.y() + "," + s.z() + " changed since");
                continue;
            }
            signs++;
            if (dryRun) continue;
            if (j != null) j.signBefore(b);
            SignAccess.apply(b, SignSnapshot.fromJson(s.before()));
        }
        advance(volume());
        return true;
    }

    private void conflict(String why) {
        conflicts++;
        note(why);
    }

    private void note(String s) {
        if (notes.size() < 10) notes.add(s);
    }

    @Override
    public JsonElement buildResult(long queuedMs, long elapsedMs) {
        JsonObject r = new JsonObject();
        r.addProperty("entitiesRemoved", removed);
        r.addProperty("entitiesRespawned", respawned);
        r.addProperty("entitiesReset", reset);
        r.addProperty("signs", signs);
        r.addProperty("conflicts", conflicts);
        r.addProperty("gone", gone);
        r.addProperty("present", present);
        r.addProperty("failed", failed);
        r.add("notes", notes);
        return r;
    }

    /** Report line for an undo, or null when the entry had no sign or entity records. */
    public static String text(JsonObject r, boolean dryRun, boolean safe) {
        String verb = dryRun ? "would be " : "";
        StringBuilder sb = new StringBuilder("Signs and entities: " + r.get("signs").getAsLong() + " sign texts " + verb + "restored, "
                + r.get("entitiesRemoved").getAsLong() + " placed entities " + verb + "removed, " + r.get("entitiesRespawned").getAsLong()
                + " removed entities " + verb + "respawned, " + r.get("entitiesReset").getAsLong() + " " + verb + "reset");
        long c = r.get("conflicts").getAsLong(), g = r.get("gone").getAsLong(), p = r.get("present").getAsLong(), f = r.get("failed").getAsLong();
        if (g > 0) sb.append(", ").append(g).append(" already gone");
        if (p > 0) sb.append(", ").append(p).append(" already back");
        if (f > 0) sb.append(", ").append(f).append(" could not be respawned");
        if (c > 0) sb.append(", ").append(c).append(" kept because they changed since").append(safe ? " (mode \"force\" reverts them too)" : "");
        sb.append('.');
        for (JsonElement e : r.getAsJsonArray("notes")) sb.append("\n  ").append(e.getAsString());
        return sb.toString();
    }
}
