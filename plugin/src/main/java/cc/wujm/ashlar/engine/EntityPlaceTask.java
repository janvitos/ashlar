// SPDX-License-Identifier: AGPL-3.0-or-later
package cc.wujm.ashlar.engine;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import org.bukkit.World;
import org.bukkit.entity.Entity;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * Places {@code mc_build entities} after the call's blocks (Step 17). With a replace box, the
 * Ashlar-placed entities of the requested types inside it are removed first; mobs, players, other
 * entity types and entities placed by hand are never touched. Each entity is checked against its
 * support and clearance where it lands; one that cannot be placed is reported, the rest still are.
 * Spawns and removals are journalled through the attached capture.
 */
public final class EntityPlaceTask extends BuildTask {

    private final World world;
    private int waits;
    private final List<EntitySpec> specs;
    private final Region replaceBox;
    private final Set<String> replaceTypes;
    private final List<String> failures = new ArrayList<>();
    private int placed, unchanged, removed;

    public EntityPlaceTask(World world, List<EntitySpec> specs, Region replaceBox, Region bounds) {
        super(bounds);
        this.world = world;
        this.specs = List.copyOf(specs);
        this.replaceBox = replaceBox;
        this.replaceTypes = Set.copyOf(specs.stream().map(EntitySpec::type).toList());
    }

    @Override
    public World world() {
        return world;
    }

    @Override
    public long volume() {
        return specs.size();
    }

    @Override
    public boolean step(long deadlineNanos) {
        if (!EntityAccess.entitiesLoaded(world, region()) && waits++ < EntityAccess.ENTITY_WAIT_TICKS) return false;
        JournalCapture j = journal();
        if (replaceBox != null) {
            for (Entity e : EntityAccess.in(world, replaceBox)) {
                if (!EntityAccess.placed(e)) continue;
                EntitySpec live = EntityAccess.read(e);
                if (!replaceTypes.contains(live.type())) continue;
                if (j != null) j.entityRemoved(world, live);
                e.remove();
                removed++;
            }
        }
        for (int i = 0; i < specs.size(); i++) {
            EntitySpec s = specs.get(i);
            try {
                EntitySpec want = EntityAccess.resolve(s);
                Entity existing = find(want);
                if (existing != null) {
                    EntitySpec live = EntityAccess.read(existing);
                    if (live.sameAs(want)) {
                        unchanged++;
                    } else {
                        failures.add("entities[" + i + "] " + s.describe() + ": another " + live.describe() + (live.placed() ? "" : " (not placed by Ashlar)")
                                + " is already there; entityMode \"replace-in-box\" replaces Ashlar-placed ones");
                    }
                    continue;
                }
                Entity e = EntityAccess.spawn(world, s, "1");
                if (j != null) j.entitySpawned(e, EntityAccess.read(e));
                placed++;
                addChanged(1);
            } catch (EntityAccess.Failure | IllegalArgumentException ex) {
                failures.add("entities[" + i + "] " + s.describe() + ": " + ex.getMessage());
            }
            advance(1);
        }
        return true;
    }

    private Entity find(EntitySpec want) {
        int[] c = want.cell();
        for (Entity e : EntityAccess.in(world, new Region(c[0], c[1], c[2], c[0], c[1], c[2]))) {
            if (EntityAccess.read(e).key().equals(want.key())) return e;
        }
        return null;
    }

    @Override
    public JsonElement buildResult(long queuedMs, long elapsedMs) {
        JsonObject r = new JsonObject();
        r.addProperty("requested", specs.size());
        r.addProperty("placed", placed);
        r.addProperty("unchanged", unchanged);
        r.addProperty("removed", removed);
        JsonArray f = new JsonArray();
        failures.forEach(f::add);
        r.add("failures", f);
        r.addProperty("elapsedMs", elapsedMs);
        return r;
    }
}
