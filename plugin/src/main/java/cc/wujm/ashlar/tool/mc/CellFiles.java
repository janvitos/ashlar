// SPDX-License-Identifier: AGPL-3.0-or-later
package cc.wujm.ashlar.tool.mc;

import cc.wujm.ashlar.tool.ToolArgError;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.stream.JsonReader;
import com.google.gson.stream.JsonToken;
import com.google.gson.stream.JsonWriter;
import com.google.gson.stream.MalformedJsonException;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.FileTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.regex.Pattern;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;

/**
 * Cell files under {@code plugins/Ashlar}: {@code staging/} (uploads assembled by
 * {@code mc_blueprint stage}) and {@code exports/} (written by {@code mc_export}). All files are
 * gzip JSON, read and written as streams. Names are plain ({@link #NAME}); symlinks are never
 * followed or written through. Each directory keeps at most {@link #MAX_FILES} files, oldest removed
 * first. No Bukkit access.
 */
public final class CellFiles {

    public static final Pattern NAME = Pattern.compile("[a-z0-9][a-z0-9_-]{0,63}");
    public static final int MAX_FILES = 20;
    public static final long MAX_FILE_BYTES = 256L * 1024 * 1024;
    public static final int MAX_STAGE_CALL = 50_000;
    static final String STAGE_SUFFIX = ".stage.gz";
    static final String FINAL_SUFFIX = ".json.gz";

    /** Receives one cell while a file streams in. */
    public interface Sink {
        void accept(int x, int y, int z, String state);
    }

    /** Writes the body of a file to an open JSON writer. */
    public interface Body {
        void write(JsonWriter w) throws IOException;
    }

    private final Path staging;
    private final Path exports;

    public CellFiles(Path dataFolder) {
        this.staging = dataFolder.resolve("staging");
        this.exports = dataFolder.resolve("exports");
        try {
            Files.createDirectories(staging);
            Files.createDirectories(exports);
        } catch (IOException e) {
            throw failure("cell files", e);
        }
    }

    public static String name(String name, String what) {
        if (name == null || !NAME.matcher(name).matches()) {
            throw new ToolArgError(what + ": must be a plain name matching [a-z0-9][a-z0-9_-]{0,63} (no path or extension)");
        }
        return name;
    }

    // ------------------------------------------------------------------ staging

    public record Staged(String name, long cells, long calls) {}

    /** Appends up to {@link #MAX_STAGE_CALL} cells to an unfinished upload; {@code reset} starts it over. */
    public synchronized Staged stage(String name, JsonArray cells, boolean reset, long maxCells) {
        name(name, "name");
        if (cells.isEmpty() || cells.size() > MAX_STAGE_CALL) throw new ToolArgError("cells: 1-" + MAX_STAGE_CALL + " per stage call");
        Path part = staging.resolve(name + STAGE_SUFFIX), count = staging.resolve(name + ".stage.count");
        noLink(part);
        noLink(count);
        long[] state = reset ? new long[]{0, 0} : readCount(count);
        if (state[0] + cells.size() > maxCells) {
            throw new ToolArgError("staged upload '" + name + "' would hold " + (state[0] + cells.size()) + " cells, more than " + maxCells);
        }
        StringBuilder sb = new StringBuilder(cells.size() * 32);
        for (int i = 0; i < cells.size(); i++) {
            int[] xyz = new int[3];
            String s = cell(cells.get(i), "cells[" + i + "]", xyz);
            sb.append('[').append(xyz[0]).append(',').append(xyz[1]).append(',').append(xyz[2]).append(',');
            sb.append(quote(s)).append("]\n");
        }
        try {
            if (reset || !Files.exists(part, LinkOption.NOFOLLOW_LINKS)) {
                pruneStale();
                Files.deleteIfExists(part);
            }
            if (Files.exists(part, LinkOption.NOFOLLOW_LINKS) && Files.size(part) > MAX_FILE_BYTES) {
                throw new ToolArgError("staged upload '" + name + "' is larger than 256 MiB");
            }
            // Each call appends one gzip member; GZIPInputStream reads concatenated members as one stream.
            try (OutputStream out = new GZIPOutputStream(new BufferedOutputStream(Files.newOutputStream(part,
                    StandardOpenOption.CREATE, StandardOpenOption.APPEND, StandardOpenOption.WRITE, LinkOption.NOFOLLOW_LINKS)))) {
                out.write(sb.toString().getBytes(StandardCharsets.UTF_8));
            }
            long[] next = {state[0] + cells.size(), state[1] + 1};
            Files.writeString(count, next[0] + " " + next[1], StandardCharsets.UTF_8);
            return new Staged(name, next[0], next[1]);
        } catch (IOException e) {
            throw failure("staging", e);
        }
    }

    /** Turns an unfinished upload into {@code staging/<name>.json.gz}, checking every cell. */
    public synchronized Staged finalizeStage(String name, long maxCells) {
        name(name, "name");
        Path part = staging.resolve(name + STAGE_SUFFIX), count = staging.resolve(name + ".stage.count");
        noLink(part);
        if (!Files.isRegularFile(part, LinkOption.NOFOLLOW_LINKS)) throw new ToolArgError("no unfinished staged upload named '" + name + "'");
        long[] n = {0};
        try {
            Path target = staging.resolve(name + FINAL_SUFFIX);
            noLink(target);
            write(staging, target, w -> {
                w.name("format").value("cells");
                w.name("cells").beginArray();
                try (JsonReader r = reader(part)) {
                    r.setLenient(true);
                    while (r.peek() != JsonToken.END_DOCUMENT) {
                        int[] xyz = new int[3];
                        String s = readCell(r, "staged cell " + n[0], xyz);
                        if (++n[0] > maxCells) throw new ToolArgError("staged upload '" + name + "' holds more than " + maxCells + " cells");
                        w.beginArray().value(xyz[0]).value(xyz[1]).value(xyz[2]).value(s).endArray();
                    }
                }
                w.endArray();
            });
            Files.deleteIfExists(part);
            Files.deleteIfExists(count);
            prune(staging);
            return new Staged(name, n[0], 0);
        } catch (IOException e) {
            throw failure("staging", e);
        }
    }

    private long[] readCount(Path count) {
        try {
            if (!Files.isRegularFile(count, LinkOption.NOFOLLOW_LINKS)) return new long[]{0, 0};
            String[] p = Files.readString(count, StandardCharsets.UTF_8).trim().split(" ");
            return new long[]{Long.parseLong(p[0]), Long.parseLong(p[1])};
        } catch (IOException | RuntimeException e) {
            return new long[]{0, 0};
        }
    }

    /** Unfinished uploads untouched for a day are dropped. */
    private void pruneStale() throws IOException {
        long cutoff = System.currentTimeMillis() - 24L * 3600 * 1000;
        for (Path p : list(staging, STAGE_SUFFIX)) {
            if (Files.getLastModifiedTime(p, LinkOption.NOFOLLOW_LINKS).toMillis() < cutoff) {
                Files.deleteIfExists(p);
                Files.deleteIfExists(staging.resolve(p.getFileName().toString().replace(STAGE_SUFFIX, ".stage.count")));
            }
        }
    }

    // ------------------------------------------------------------------ exports

    /** Atomically writes {@code exports/<name>.json.gz}; returns its size in bytes. */
    public synchronized long writeExport(String name, boolean overwrite, Body body) {
        name(name, "name");
        Path target = exports.resolve(name + FINAL_SUFFIX);
        noLink(target);
        if (Files.exists(target, LinkOption.NOFOLLOW_LINKS) && !overwrite) {
            throw new ToolArgError("export '" + name + "' already exists; set overwrite:true explicitly");
        }
        try {
            write(exports, target, body);
            prune(exports, target);
            return Files.size(target);
        } catch (IOException e) {
            throw failure("export", e);
        }
    }

    public boolean exportExists(String name) {
        return Files.isRegularFile(exports.resolve(name(name, "name") + FINAL_SUFFIX), LinkOption.NOFOLLOW_LINKS);
    }

    // ------------------------------------------------------------------ reading

    /** The finished file for {@code name}: a staged upload first, then an export; null when neither exists. */
    public Path find(String name) {
        name(name, "name");
        for (Path dir : List.of(staging, exports)) {
            Path p = dir.resolve(name + FINAL_SUFFIX);
            if (Files.isRegularFile(p, LinkOption.NOFOLLOW_LINKS)) return p;
        }
        return null;
    }

    public static String where(Path p) {
        return "plugins/Ashlar/" + p.getParent().getFileName() + "/" + p.getFileName();
    }

    /**
     * Streams every cell of a file: a JSON array of {@code [x,y,z,"state"]}, an object with
     * {@code cells}, or a palette-rle export ({@code bounds}, {@code palette}, {@code runs}).
     */
    public static void read(Path file, String what, Sink sink) {
        try {
            if (Files.size(file) > MAX_FILE_BYTES) throw new ToolArgError(what + " is larger than 256 MiB");
            try (JsonReader r = reader(file)) {
                if (r.peek() == JsonToken.BEGIN_ARRAY) {
                    cells(r, what, sink);
                    return;
                }
                r.beginObject();
                int[] from = null, to = null;
                List<String> palette = null;
                int[] runs = null;
                int runCount = 0;
                boolean sawCells = false;
                while (r.hasNext()) {
                    switch (r.nextName()) {
                        case "format" -> {
                            if (r.peek() != JsonToken.STRING) r.skipValue();
                            else if (r.nextString().equals("surface")) {
                                throw new ToolArgError(what + " is an mc_survey surface file (heights and top blocks per column), not cells");
                            }
                        }
                        case "cells" -> {
                            cells(r, what, sink);
                            sawCells = true;
                        }
                        case "bounds" -> {
                            r.beginObject();
                            while (r.hasNext()) {
                                String k = r.nextName();
                                if (k.equals("from")) from = triple(r, what);
                                else if (k.equals("to")) to = triple(r, what);
                                else r.skipValue();
                            }
                            r.endObject();
                        }
                        case "palette" -> {
                            palette = new ArrayList<>();
                            r.beginArray();
                            while (r.hasNext()) palette.add(r.nextString());
                            r.endArray();
                        }
                        case "runs" -> {
                            runs = new int[1024];
                            r.beginArray();
                            while (r.hasNext()) {
                                if (runCount + 2 > runs.length) runs = Arrays.copyOf(runs, runs.length * 2);
                                r.beginArray();
                                runs[runCount++] = r.nextInt();
                                runs[runCount++] = r.nextInt();
                                r.endArray();
                            }
                            r.endArray();
                        }
                        default -> r.skipValue();
                    }
                }
                r.endObject();
                if (sawCells) return;
                if (from == null || to == null || palette == null || runs == null) {
                    throw new ToolArgError(what + " must be a JSON array of [x,y,z,\"state\"], {\"cells\":[...]} or a palette-rle export");
                }
                decode(from, to, palette, runs, runCount, what, sink);
            }
        } catch (MalformedJsonException | IllegalStateException | NumberFormatException e) {
            throw new ToolArgError(what + " is not valid cell JSON: " + e.getClass().getSimpleName());
        } catch (IOException e) {
            throw new ToolArgError(what + " could not be read: " + e.getClass().getSimpleName());
        }
    }

    private static void decode(int[] from, int[] to, List<String> palette, int[] runs, int runCount, String what, Sink sink) {
        int x0 = Math.min(from[0], to[0]), y0 = Math.min(from[1], to[1]), z0 = Math.min(from[2], to[2]);
        int x1 = Math.max(from[0], to[0]), y1 = Math.max(from[1], to[1]), z1 = Math.max(from[2], to[2]);
        long volume = (long) (x1 - x0 + 1) * (y1 - y0 + 1) * (z1 - z0 + 1), total = 0;
        for (int i = 1; i < runCount; i += 2) total += runs[i];
        if (total != volume) throw new ToolArgError(what + ": runs cover " + total + " cells but bounds hold " + volume);
        int x = x0, y = y0, z = z0;
        for (int i = 0; i < runCount; i += 2) {
            int id = runs[i];
            if (id < 0 || id >= palette.size() || runs[i + 1] < 1) throw new ToolArgError(what + ": bad run " + (i / 2));
            String s = palette.get(id);
            for (int k = 0; k < runs[i + 1]; k++) {
                sink.accept(x, y, z, s);
                if (++x > x1) {
                    x = x0;
                    if (++z > z1) {
                        z = z0;
                        y++;
                    }
                }
            }
        }
    }

    private static void cells(JsonReader r, String what, Sink sink) throws IOException {
        r.beginArray();
        long i = 0;
        int[] xyz = new int[3];
        while (r.hasNext()) {
            String s = readCell(r, what + "[" + i++ + "]", xyz);
            sink.accept(xyz[0], xyz[1], xyz[2], s);
        }
        r.endArray();
    }

    private static String readCell(JsonReader r, String what, int[] xyz) throws IOException {
        if (r.peek() != JsonToken.BEGIN_ARRAY) throw new ToolArgError(what + " must be [x,y,z,\"block_state\"]");
        r.beginArray();
        for (int k = 0; k < 3; k++) {
            if (r.peek() != JsonToken.NUMBER) throw new ToolArgError(what + " must be [x,y,z,\"block_state\"]");
            xyz[k] = r.nextInt();
        }
        if (r.peek() != JsonToken.STRING) throw new ToolArgError(what + " must be [x,y,z,\"block_state\"]");
        String s = r.nextString();
        if (r.hasNext()) throw new ToolArgError(what + " must be [x,y,z,\"block_state\"]");
        r.endArray();
        return s;
    }

    private static int[] triple(JsonReader r, String what) throws IOException {
        int[] t = new int[3];
        r.beginArray();
        for (int k = 0; k < 3; k++) t[k] = r.nextInt();
        if (r.hasNext()) throw new ToolArgError(what + ": bounds must be [x,y,z]");
        r.endArray();
        return t;
    }

    /** Validates one inline cell {@code [x,y,z,"state"]} and returns its state. */
    static String cell(JsonElement el, String what, int[] xyz) {
        if (!el.isJsonArray() || el.getAsJsonArray().size() != 4) throw new ToolArgError(what + " must be [x,y,z,\"block_state\"]");
        JsonArray c = el.getAsJsonArray();
        for (int k = 0; k < 3; k++) xyz[k] = BuildTransform.strictInt(c.get(k), what + "[" + k + "]");
        JsonElement s = c.get(3);
        if (!s.isJsonPrimitive() || !s.getAsJsonPrimitive().isString() || s.getAsString().isBlank()) {
            throw new ToolArgError(what + "[3] must be a block state string");
        }
        return s.getAsString();
    }

    // ------------------------------------------------------------------ files

    private static JsonReader reader(Path file) throws IOException {
        InputStream in = Files.newInputStream(file, LinkOption.NOFOLLOW_LINKS);
        try {
            BufferedInputStream b = new BufferedInputStream(in, 1 << 16);
            b.mark(2);
            int m1 = b.read(), m2 = b.read();
            b.reset();
            InputStream body = m1 == 0x1f && m2 == 0x8b ? new GZIPInputStream(b, 1 << 16) : b;
            return new JsonReader(new InputStreamReader(body, StandardCharsets.UTF_8));
        } catch (IOException e) {
            in.close();
            throw e;
        }
    }

    private static void write(Path dir, Path target, Body body) throws IOException {
        Path tmp = Files.createTempFile(dir, ".cells-", ".tmp");
        try {
            try (Writer out = new OutputStreamWriter(new GZIPOutputStream(new BufferedOutputStream(Files.newOutputStream(tmp), 1 << 16)),
                    StandardCharsets.UTF_8); JsonWriter w = new JsonWriter(out)) {
                w.beginObject();
                body.write(w);
                w.endObject();
            }
            Files.move(tmp, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } finally {
            Files.deleteIfExists(tmp);
        }
    }

    private static void noLink(Path p) {
        if (Files.isSymbolicLink(p)) throw new ToolArgError("'" + p.getFileName() + "' is a symbolic link; refusing to use it");
    }

    private void prune(Path dir) throws IOException {
        prune(dir, null);
    }

    private void prune(Path dir, Path keep) throws IOException {
        List<Path> files = new ArrayList<>(list(dir, FINAL_SUFFIX));
        if (files.size() <= MAX_FILES) return;
        files.sort(Comparator.comparing(p -> {
            try {
                return Files.getLastModifiedTime(p, LinkOption.NOFOLLOW_LINKS);
            } catch (IOException e) {
                return FileTime.fromMillis(0);
            }
        }));
        for (int i = 0; i < files.size() - MAX_FILES; i++) if (!files.get(i).equals(keep)) Files.deleteIfExists(files.get(i));
    }

    private static List<Path> list(Path dir, String suffix) throws IOException {
        try (var s = Files.list(dir)) {
            return s.filter(p -> p.getFileName().toString().endsWith(suffix) && Files.isRegularFile(p, LinkOption.NOFOLLOW_LINKS)).toList();
        }
    }

    private static String quote(String s) {
        return new com.google.gson.JsonPrimitive(s).toString();
    }

    private static ToolArgError failure(String what, Exception e) {
        // Do not leak filesystem paths into agent/player-facing errors.
        return new ToolArgError(what + ": " + e.getClass().getSimpleName());
    }
}
