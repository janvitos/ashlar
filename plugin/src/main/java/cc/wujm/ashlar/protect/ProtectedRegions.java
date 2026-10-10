// SPDX-License-Identifier: AGPL-3.0-or-later
package cc.wujm.ashlar.protect;

import cc.wujm.ashlar.engine.Region;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Protected regions on disk ({@code plugins/Ashlar/protected.json}), written atomically on every
 * change and read at enable and on {@code /ashlar reload}. Readers get an immutable view that is
 * swapped as a whole, so a check never sees a half-applied change. No Bukkit access.
 */
public final class ProtectedRegions {

    public static final int MAX_REGIONS = 1000;
    private static final Gson JSON = new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create();

    private final Path file;
    private final Clock clock;
    private volatile Map<String, ProtectedRegion> regions = Map.of();

    public ProtectedRegions(Path file, Clock clock) {
        this.file = file;
        this.clock = clock;
    }

    public ProtectedRegions(Path file) {
        this(file, Clock.systemUTC());
    }

    /** Replaces the in-memory set with the file's content; a missing file means no regions. */
    public synchronized void load() throws IOException {
        String text;
        try {
            text = Files.readString(file, StandardCharsets.UTF_8);
        } catch (NoSuchFileException e) {
            regions = Map.of();
            return;
        }
        Map<String, ProtectedRegion> loaded = new LinkedHashMap<>();
        try {
            JsonElement root = JsonParser.parseString(text);
            JsonArray arr = root.getAsJsonObject().getAsJsonArray("regions");
            for (JsonElement el : arr) {
                ProtectedRegion r = fromJson(el.getAsJsonObject());
                if (loaded.put(r.name(), r) != null) throw new IOException("duplicate region name '" + r.name() + "'");
            }
        } catch (RuntimeException e) {
            throw new IOException("protected.json is invalid: " + e.getMessage(), e);
        }
        regions = Map.copyOf(loaded);
    }

    public List<ProtectedRegion> all() {
        return regions.values().stream().sorted(Comparator.comparing(ProtectedRegion::name)).toList();
    }

    public List<ProtectedRegion> inWorld(String world) {
        List<ProtectedRegion> out = new ArrayList<>();
        for (ProtectedRegion r : regions.values()) if (r.world().equals(world)) out.add(r);
        return out;
    }

    public ProtectedRegion get(String name) {
        return regions.get(name);
    }

    /** Adds a region and persists the set; an existing name is an error (remove it first). */
    public synchronized ProtectedRegion add(String name, String world, Region box, ProtectedRegion.Mode mode, String note, String owner)
            throws IOException {
        if (regions.containsKey(name)) throw new IllegalArgumentException("protected region '" + name + "' already exists; remove it first");
        if (regions.size() >= MAX_REGIONS) throw new IllegalArgumentException("at most " + MAX_REGIONS + " protected regions");
        ProtectedRegion r = new ProtectedRegion(name, world, box, mode, note, owner, clock.instant());
        Map<String, ProtectedRegion> next = new LinkedHashMap<>(regions);
        next.put(name, r);
        write(next);
        regions = Map.copyOf(next);
        return r;
    }

    /** Removes {@code name} when {@code requester} created it or {@code admin} is set. */
    public synchronized ProtectedRegion remove(String name, String requester, boolean admin) throws IOException {
        ProtectedRegion r = regions.get(name);
        if (r == null) throw new IllegalArgumentException("protected region '" + name + "' not found");
        if (!admin && !r.owner().equals(requester)) {
            throw new IllegalArgumentException("protected region '" + name + "' was created by someone else; only its creator, an operator or the console can remove it");
        }
        Map<String, ProtectedRegion> next = new LinkedHashMap<>(regions);
        next.remove(name);
        write(next);
        regions = Map.copyOf(next);
        return r;
    }

    private void write(Map<String, ProtectedRegion> set) throws IOException {
        JsonArray arr = new JsonArray();
        set.values().stream().sorted(Comparator.comparing(ProtectedRegion::name)).forEach(r -> arr.add(toJson(r)));
        JsonObject root = new JsonObject();
        root.addProperty("version", 1);
        root.add("regions", arr);
        Path dir = file.toAbsolutePath().getParent();
        Files.createDirectories(dir);
        Path temporary = Files.createTempFile(dir, ".protected-", ".tmp");
        try {
            Files.writeString(temporary, JSON.toJson(root), StandardCharsets.UTF_8);
            // Atomic replacement: a reload or crash never sees a partial file.
            Files.move(temporary, file, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } finally {
            Files.deleteIfExists(temporary);
        }
    }

    public static JsonObject toJson(ProtectedRegion r) {
        JsonObject o = new JsonObject();
        o.addProperty("name", r.name());
        o.addProperty("world", r.world());
        o.add("from", point(r.box().minX(), r.box().minY(), r.box().minZ()));
        o.add("to", point(r.box().maxX(), r.box().maxY(), r.box().maxZ()));
        o.addProperty("mode", r.mode().id());
        if (r.note() != null) o.addProperty("note", r.note());
        o.addProperty("owner", r.owner());
        o.addProperty("createdAt", r.createdAt().toString());
        return o;
    }

    static ProtectedRegion fromJson(JsonObject o) {
        Region box = Region.of(coords(o.getAsJsonArray("from")), coords(o.getAsJsonArray("to")));
        return new ProtectedRegion(o.get("name").getAsString(), o.get("world").getAsString(), box,
                ProtectedRegion.Mode.parse(o.get("mode").getAsString()), o.has("note") ? o.get("note").getAsString() : null,
                o.get("owner").getAsString(), Instant.parse(o.get("createdAt").getAsString()));
    }

    private static int[] coords(JsonArray a) {
        if (a.size() != 3) throw new IllegalArgumentException("coordinates must have 3 elements");
        return new int[]{a.get(0).getAsInt(), a.get(1).getAsInt(), a.get(2).getAsInt()};
    }

    public static JsonArray point(int x, int y, int z) {
        JsonArray a = new JsonArray();
        a.add(x);
        a.add(y);
        a.add(z);
        return a;
    }
}
