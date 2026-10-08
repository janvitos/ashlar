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
        byte[] bytes = JSON.toJson(document).getBytes(StandardCharsets.UTF_8);
        if (bytes.length > MAX_DOCUMENT_BYTES) throw new ToolArgError("blueprint: document exceeds 2 MiB");
        BlueprintCompiler.validate(document);
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
        JsonArray entries = new JsonArray();
        for (String id : ids()) entries.add(summary(id, get(id)));
        JsonObject result = new JsonObject(); result.add("blueprints", entries);
        return result;
    }

    public synchronized void delete(String id) {
        try {
            if (!Files.deleteIfExists(path(id))) throw new ToolArgError("blueprint '" + id + "' not found");
        } catch (IOException e) { throw failure(e); }
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
        for (String key : java.util.List.of("description", "dimensions"))
            if (document.has(key)) result.add(key, document.get(key).deepCopy());
        try {
            result.addProperty("revision", HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(JSON.toJson(document).getBytes(StandardCharsets.UTF_8))));
        } catch (NoSuchAlgorithmException e) { throw new IllegalStateException(e); }
        return result;
    }

    private static ToolArgError failure(Exception e) {
        // Do not leak filesystem paths into agent/player-facing errors.
        return new ToolArgError("blueprint store: " + e.getClass().getSimpleName());
    }
}
