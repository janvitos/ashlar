// SPDX-License-Identifier: AGPL-3.0-or-later
package cc.wujm.ashlar.journal;

import cc.wujm.ashlar.engine.Region;
import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadLocalRandom;
import java.util.function.Supplier;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.regex.Pattern;

/**
 * Build journal entries on disk: {@code <id>.json} metadata plus {@code <id>.bin.gz} cells
 * ({@link JournalCodec}) under {@code plugins/Ashlar/journal/}. Metadata stays in memory in
 * creation order. {@link #put} evicts expired entries, then the oldest idle ones beyond the entry
 * or total-cell quota; an entry being undone ({@link #acquire}) is never evicted or deleted.
 * Every method may do file I/O: callers run them on {@link #io()}, never the main thread.
 */
public final class JournalStore {

    public record Limits(int maxEntries, long maxTotalCells, int maxAgeDays) {
    }

    /** Who is asking: a PLAYER sees only its own entries; MCP token, console and system see all. */
    public record Viewer(String owner, boolean all) {
        boolean sees(Entry e) {
            return all || owner.equals(e.owner());
        }
    }

    public record Entry(String id, String owner, String world, String tool, String label, Instant createdAt, long cells,
            long blockEntityCells, Region bounds, String undoOf, String undoneBy) {
        Entry withUndoneBy(String by) {
            return new Entry(id, owner, world, tool, label, createdAt, cells, blockEntityCells, bounds, undoOf, by);
        }
    }

    static final Pattern ID = Pattern.compile("jrn-\\d{8}-\\d{6}-[0-9a-f]{4}");
    private static final DateTimeFormatter STAMP = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss").withZone(ZoneOffset.UTC);
    private static final Gson GSON = new Gson();

    private final Path dir;
    private final Supplier<Limits> limits;
    private final Clock clock;
    private final Logger logger;
    private final LinkedHashMap<String, Entry> entries = new LinkedHashMap<>();
    private final Set<String> busy = new HashSet<>();
    private final ExecutorService io = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "ashlar-journal-io");
        t.setDaemon(true);
        return t;
    });

    public JournalStore(Path dir, Supplier<Limits> limits, Clock clock, Logger logger) {
        this.dir = dir;
        this.limits = limits;
        this.clock = clock;
        this.logger = logger;
    }

    public ExecutorService io() {
        return io;
    }

    public void shutdown() {
        io.shutdown();
    }

    /** Loads every entry's metadata; drops orphaned or unreadable files' entries from memory. */
    public synchronized void loadFromDisk() {
        try {
            Files.createDirectories(dir);
        } catch (IOException e) {
            logger.log(Level.SEVERE, "Failed to create journal directory", e);
            return;
        }
        List<Entry> loaded = new ArrayList<>();
        try (var files = Files.list(dir)) {
            for (Path p : files.filter(f -> f.getFileName().toString().endsWith(".json")).toList()) {
                String id = p.getFileName().toString().replaceFirst("\\.json$", "");
                if (!ID.matcher(id).matches() || !Files.exists(cellsPath(id))) continue;
                try {
                    loaded.add(fromJson(JsonParser.parseString(Files.readString(p, StandardCharsets.UTF_8)).getAsJsonObject()));
                } catch (RuntimeException | IOException e) {
                    logger.warning("Skipping unreadable journal entry " + id + ": " + e.getMessage());
                }
            }
        } catch (IOException e) {
            logger.log(Level.SEVERE, "Failed to list journal directory", e);
        }
        loaded.sort(Comparator.comparing(Entry::createdAt).thenComparing(Entry::id));
        entries.clear();
        for (Entry e : loaded) entries.put(e.id(), e);
        evict();
        logger.info("Loaded " + entries.size() + " journal entr" + (entries.size() == 1 ? "y" : "ies") + " from disk");
    }

    /** Stores a non-empty entry and applies the quotas; returns its metadata. */
    public synchronized Entry put(String owner, String world, String tool, String label, String undoOf, long blockEntityCells,
            JournalCells cells) throws IOException {
        Files.createDirectories(dir);
        String id;
        do {
            id = "jrn-" + STAMP.format(clock.instant()) + "-" + String.format("%04x", ThreadLocalRandom.current().nextInt(0x10000));
        } while (entries.containsKey(id));
        Entry e = new Entry(id, owner, world, tool, label, clock.instant(), cells.size(), blockEntityCells, cells.bounds(), undoOf, null);
        Path tmp = dir.resolve(id + ".bin.gz.tmp");
        try (OutputStream out = Files.newOutputStream(tmp)) {
            JournalCodec.write(cells, out);
        }
        Files.move(tmp, cellsPath(id), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        writeMeta(e);
        entries.put(id, e);
        busy.add(id);
        try {
            evict();
        } finally {
            busy.remove(id);
        }
        return e;
    }

    private void evict() {
        Limits l = limits.get();
        Instant cutoff = clock.instant().minus(Duration.ofDays(l.maxAgeDays()));
        long total = entries.values().stream().mapToLong(Entry::cells).sum();
        int count = entries.size();
        Iterator<Entry> it = entries.values().iterator();
        while (it.hasNext()) {
            Entry e = it.next();
            boolean expired = e.createdAt().isBefore(cutoff);
            if (!expired && count <= l.maxEntries() && total <= l.maxTotalCells()) break;
            if (busy.contains(e.id())) continue;
            it.remove();
            count--;
            total -= e.cells();
            deleteFiles(e.id());
        }
    }

    public synchronized Entry get(String id, Viewer viewer) {
        Entry e = entries.get(id);
        if (e == null || !viewer.sees(e)) {
            throw new IllegalArgumentException("journal entry " + id + " not found (evicted, deleted or not yours); list entries with mc_snapshot {\"action\":\"journal-list\"}");
        }
        return e;
    }

    public JournalCells cells(Entry e) throws IOException {
        try (InputStream in = Files.newInputStream(cellsPath(e.id()))) {
            return JournalCodec.read(in);
        }
    }

    /** Newest first. {@code touches} is exact: entries whose bounds intersect are confirmed against their cells. */
    public List<Entry> list(Viewer viewer, Instant since, String labelContains, String world, Region touches, int limit)
            throws IOException {
        List<Entry> candidates;
        synchronized (this) {
            candidates = new ArrayList<>(entries.values());
        }
        List<Entry> out = new ArrayList<>();
        String needle = labelContains == null ? null : labelContains.toLowerCase(Locale.ROOT);
        for (int i = candidates.size() - 1; i >= 0 && out.size() < limit; i--) {
            Entry e = candidates.get(i);
            if (!viewer.sees(e)) continue;
            if (since != null && e.createdAt().isBefore(since)) continue;
            if (needle != null && (e.label() == null || !e.label().toLowerCase(Locale.ROOT).contains(needle))) continue;
            if (world != null && !world.equals(e.world())) continue;
            if (touches != null) {
                if (!intersects(e.bounds(), touches)) continue;
                JournalCells c;
                try {
                    c = cells(e);
                } catch (IOException ex) {
                    continue; // evicted meanwhile
                }
                if (!c.touches(touches)) continue;
            }
            out.add(e);
        }
        return out;
    }

    static boolean intersects(Region a, Region b) {
        return a.minX() <= b.maxX() && a.maxX() >= b.minX() && a.minY() <= b.maxY() && a.maxY() >= b.minY()
                && a.minZ() <= b.maxZ() && a.maxZ() >= b.minZ();
    }

    public synchronized void acquire(Entry e) {
        if (entries.get(e.id()) == null) throw new IllegalArgumentException("journal entry " + e.id() + " was evicted or deleted");
        if (!busy.add(e.id())) throw new IllegalArgumentException("journal entry " + e.id() + " is already being undone");
    }

    public synchronized void release(Entry e) {
        busy.remove(e.id());
    }

    public synchronized void markUndone(String id, String undoneBy) throws IOException {
        Entry e = entries.get(id);
        if (e == null) return;
        Entry updated = e.withUndoneBy(undoneBy);
        writeMeta(updated);
        entries.put(id, updated);
    }

    public synchronized void delete(String id, Viewer viewer) {
        Entry e = get(id, viewer);
        if (busy.contains(e.id())) throw new IllegalArgumentException("journal entry " + id + " is being undone; retry afterwards");
        entries.remove(id);
        deleteFiles(id);
    }

    public synchronized int size() {
        return entries.size();
    }

    private Path cellsPath(String id) {
        return dir.resolve(id + ".bin.gz");
    }

    private void deleteFiles(String id) {
        try {
            Files.deleteIfExists(dir.resolve(id + ".json"));
            Files.deleteIfExists(cellsPath(id));
        } catch (IOException ex) {
            logger.warning("Failed to delete journal entry " + id + ": " + ex.getMessage());
        }
    }

    private void writeMeta(Entry e) throws IOException {
        Path tmp = dir.resolve(e.id() + ".json.tmp");
        Files.writeString(tmp, GSON.toJson(toJson(e)), StandardCharsets.UTF_8);
        Files.move(tmp, dir.resolve(e.id() + ".json"), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
    }

    static JsonObject toJson(Entry e) {
        JsonObject o = new JsonObject();
        o.addProperty("id", e.id());
        o.addProperty("owner", e.owner());
        o.addProperty("world", e.world());
        o.addProperty("tool", e.tool());
        if (e.label() != null) o.addProperty("label", e.label());
        o.addProperty("createdAt", e.createdAt().toString());
        o.addProperty("cells", e.cells());
        o.addProperty("blockEntityCells", e.blockEntityCells());
        Region b = e.bounds();
        JsonArray bounds = new JsonArray();
        for (int v : new int[]{b.minX(), b.minY(), b.minZ(), b.maxX(), b.maxY(), b.maxZ()}) bounds.add(v);
        o.add("bounds", bounds);
        if (e.undoOf() != null) o.addProperty("undoOf", e.undoOf());
        if (e.undoneBy() != null) o.addProperty("undoneBy", e.undoneBy());
        return o;
    }

    static Entry fromJson(JsonObject o) {
        JsonArray b = o.getAsJsonArray("bounds");
        Region bounds = new Region(b.get(0).getAsInt(), b.get(1).getAsInt(), b.get(2).getAsInt(), b.get(3).getAsInt(),
                b.get(4).getAsInt(), b.get(5).getAsInt());
        return new Entry(o.get("id").getAsString(), o.get("owner").getAsString(), o.get("world").getAsString(),
                o.get("tool").getAsString(), o.has("label") ? o.get("label").getAsString() : null,
                Instant.parse(o.get("createdAt").getAsString()), o.get("cells").getAsLong(), o.get("blockEntityCells").getAsLong(),
                bounds, o.has("undoOf") ? o.get("undoOf").getAsString() : null,
                o.has("undoneBy") ? o.get("undoneBy").getAsString() : null);
    }

    /** Test helper: entries in creation order. */
    synchronized List<Entry> all() {
        return List.copyOf(entries.values());
    }
}
