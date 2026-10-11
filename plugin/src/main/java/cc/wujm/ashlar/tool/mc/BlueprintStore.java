// SPDX-License-Identifier: AGPL-3.0-or-later
package cc.wujm.ashlar.tool.mc;

import cc.wujm.ashlar.tool.ToolArgError;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

/** Durable, bounded blueprint documents. No Bukkit access, cached mutable JSON or world changes. */
public final class BlueprintStore {
    public static final int MAX_DOCUMENT_BYTES = 2 * 1024 * 1024;
    public static final int MAX_BLUEPRINTS = 256;
    private static final Gson JSON = new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create();
    private final Path directory;

    public BlueprintStore(Path directory) {
        this.directory = directory;
        try { Files.createDirectories(directory); }
        catch (IOException e) { throw failure(e); }
    }

    public synchronized JsonObject save(String id, JsonObject document, boolean overwrite) {
        BlueprintCompiler.name(id);
        document = document.deepCopy();
        BlueprintCompiler.validate(document);
        byte[] bytes = JSON.toJson(document).getBytes(StandardCharsets.UTF_8);
        if (bytes.length > MAX_DOCUMENT_BYTES) throw new ToolArgError("blueprint: document exceeds 2 MiB");
        Path target = path(id);
        if (Files.exists(target, LinkOption.NOFOLLOW_LINKS) && !overwrite)
            throw new ToolArgError("blueprint '" + id + "' already exists; set overwrite:true explicitly");
        if (!Files.exists(target, LinkOption.NOFOLLOW_LINKS) && ids().size() >= MAX_BLUEPRINTS)
            throw new ToolArgError("blueprint store: maximum " + MAX_BLUEPRINTS + " documents");
        Path temporary = null;
        try {
            temporary = Files.createTempFile(directory, ".blueprint-", ".tmp");
            Files.write(temporary, bytes);
            // Atomic replacement avoids exposing partial JSON to another request or plugin reload.
            Files.move(temporary, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            writeBounds(id, document);
            return summary(id, document);
        } catch (IOException e) { throw failure(e); }
        finally {
            if (temporary != null) try { Files.deleteIfExists(temporary); } catch (IOException ignored) { }
        }
    }

    public synchronized JsonObject get(String id) {
        Path file = path(id);
        try (var input = Files.newInputStream(file, LinkOption.NOFOLLOW_LINKS)) {
            byte[] bytes = input.readNBytes(MAX_DOCUMENT_BYTES + 1);
            if (bytes.length > MAX_DOCUMENT_BYTES) throw new ToolArgError("blueprint '" + id + "': document exceeds 2 MiB");
            var value = JsonParser.parseString(new String(bytes, StandardCharsets.UTF_8));
            if (!value.isJsonObject()) throw new ToolArgError("blueprint '" + id + "': document must be an object");
            JsonObject doc = value.getAsJsonObject();
            BlueprintCompiler.validate(doc);
            return doc;
        } catch (java.nio.file.NoSuchFileException e) { throw new ToolArgError("blueprint '" + id + "' not found"); }
        catch (IOException | com.google.gson.JsonParseException e) { throw failure(e); }
    }

    public synchronized JsonObject list() {
        return list(new JsonObject());
    }

    /** Summaries of the documents whose tags equal every given tag value (Step 19). */
    public synchronized JsonObject list(JsonObject filter) {
        JsonArray entries = new JsonArray();
        for (String id : ids()) {
            JsonObject doc = get(id);
            JsonObject tags = doc.has("tags") ? doc.getAsJsonObject("tags") : new JsonObject();
            boolean match = true;
            for (var e : filter.entrySet()) match &= e.getValue().equals(tags.get(e.getKey()));
            if (match) entries.add(summary(id, doc));
        }
        JsonObject result = new JsonObject(); result.add("blueprints", entries);
        return result;
    }

    /** Ids and bounds of the documents whose cached bounds intersect {@code [min, max]}; documents without bounds are counted. */
    public synchronized JsonObject touches(int[] min, int[] max) {
        JsonArray entries = new JsonArray();
        int unknown = 0;
        for (String id : ids()) {
            JsonObject doc = get(id);
            JsonObject bounds = bounds(id, doc);
            if (bounds == null) { unknown++; continue; }
            int[] from = BuildTransform.strictCoords(bounds, "from"), to = BuildTransform.strictCoords(bounds, "to");
            boolean hit = true;
            for (int i = 0; i < 3; i++) hit &= from[i] <= max[i] && to[i] >= min[i];
            if (!hit) continue;
            JsonObject e = new JsonObject();
            e.addProperty("id", id);
            e.add("bounds", bounds);
            if (doc.has("tags")) e.add("tags", doc.get("tags").deepCopy());
            entries.add(e);
        }
        JsonObject result = new JsonObject();
        result.add("blueprints", entries);
        if (unknown > 0) result.addProperty("withoutBounds", unknown);
        return result;
    }

    /** Merges {@code changes} into the document's tags (a null value removes that tag) and saves it. */
    public synchronized JsonObject tag(String id, JsonObject changes) {
        JsonObject doc = get(id);
        JsonObject tags = doc.has("tags") ? doc.getAsJsonObject("tags").deepCopy() : new JsonObject();
        for (var e : changes.entrySet()) {
            if (e.getValue().isJsonNull()) tags.remove(e.getKey());
            else tags.add(e.getKey(), e.getValue().deepCopy());
        }
        BlueprintCompiler.tags(tags);
        if (tags.isEmpty()) doc.remove("tags");
        else doc.add("tags", tags);
        return save(id, doc, true);
    }

    /** The document's tags, or null; safe off the main thread. */
    public synchronized JsonObject tags(String id) {
        JsonObject doc = get(id);
        return doc.has("tags") ? doc.getAsJsonObject("tags").deepCopy() : null;
    }

    public synchronized void delete(String id) {
        try {
            if (!Files.deleteIfExists(path(id))) throw new ToolArgError("blueprint '" + id + "' not found");
            Files.deleteIfExists(boundsPath(id));
        } catch (IOException e) { throw failure(e); }
    }

    /**
     * Compiled bounds without a project transform, cached at save time in {@code <id>.bounds.json} next to the
     * document (keyed by revision, so the document itself round-trips unchanged); recomputed when missing or stale.
     */
    private JsonObject bounds(String id, JsonObject document) {
        String revision = revision(document);
        try {
            JsonObject cached = JsonParser.parseString(Files.readString(boundsPath(id), StandardCharsets.UTF_8)).getAsJsonObject();
            if (revision.equals(cached.get("revision").getAsString())) return box(cached);
        } catch (IOException | RuntimeException ignored) {
            // Missing or unreadable cache: recompute below.
        }
        return writeBounds(id, document);
    }

    private JsonObject writeBounds(String id, JsonObject document) {
        int[][] b = BlueprintCompiler.bounds(document);
        JsonObject cached = new JsonObject();
        cached.addProperty("revision", revision(document));
        if (b != null) {
            cached.add("from", JsonUtil.intArray(b[0]));
            cached.add("to", JsonUtil.intArray(b[1]));
        }
        try {
            Path temporary = Files.createTempFile(directory, ".bounds-", ".tmp");
            Files.writeString(temporary, cached.toString(), StandardCharsets.UTF_8);
            Files.move(temporary, boundsPath(id), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException e) { throw failure(e); }
        return box(cached);
    }

    /** {@code {from, to}} of a cache entry, or null when the document compiles to nothing. */
    private static JsonObject box(JsonObject cached) {
        if (!cached.has("from")) return null;
        JsonObject out = new JsonObject();
        out.add("from", cached.get("from").deepCopy());
        out.add("to", cached.get("to").deepCopy());
        return out;
    }

    private Path boundsPath(String id) {
        BlueprintCompiler.name(id);
        return directory.resolve(id + ".bounds.json");
    }

    private static String revision(JsonObject document) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(JSON.toJson(document).getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) { throw new IllegalStateException(e); }
    }

    private java.util.List<String> ids() {
        try (var files = Files.list(directory)) {
            return files.filter(p -> p.getFileName().toString().matches("[a-z][a-z0-9_-]{0,63}\\.json"))
                    .map(p -> p.getFileName().toString().replaceFirst("\\.json$", "")).sorted().toList();
        } catch (IOException e) { throw failure(e); }
    }

    private Path path(String id) {
        BlueprintCompiler.name(id);
        return directory.resolve(id + ".json");
    }

    private static JsonObject summary(String id, JsonObject document) {
        JsonObject result = new JsonObject();
        result.addProperty("id", id);
        result.addProperty("components", document.getAsJsonObject("components").size());
        result.addProperty("instances", document.getAsJsonArray("instances").size());
        for (String key : java.util.List.of("description", "dimensions", "tags"))
            if (document.has(key)) result.add(key, document.get(key).deepCopy());
        result.addProperty("revision", revision(document));
        return result;
    }

    private static ToolArgError failure(Exception e) {
        // Do not leak filesystem paths into agent/player-facing errors.
        return new ToolArgError("blueprint store: " + e.getClass().getSimpleName());
    }
}
