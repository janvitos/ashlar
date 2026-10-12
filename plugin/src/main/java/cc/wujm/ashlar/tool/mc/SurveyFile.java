// SPDX-License-Identifier: AGPL-3.0-or-later
package cc.wujm.ashlar.tool.mc;

import cc.wujm.ashlar.engine.BukkitStateDefaults;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.stream.JsonWriter;

import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.UnaryOperator;

/**
 * {@code mc_survey saveAs}: writes the per-column ground and top readings of a heightmap read to
 * {@code exports/<name>.json.gz} (format "surface"), so a script can read terrain, trees, water and
 * structures over a large area without decoding region files or paying for them in the reply.
 */
final class SurveyFile {
    private SurveyFile() {}

    /** Writes the file and returns the reply line naming it. Not on the main thread. */
    static String write(CellFiles files, String name, boolean overwrite, JsonObject heightmap, int x1, int z1, int x2, int z2) {
        return write(files, name, overwrite, heightmap, x1, z1, x2, z2, BukkitStateDefaults.CANON::canon);
    }

    static String write(CellFiles files, String name, boolean overwrite, JsonObject heightmap, int x1, int z1, int x2, int z2,
            UnaryOperator<String> canon) {
        JsonObject c = heightmap.getAsJsonObject("columns");
        int[] top = ints(c, "top"), ground = ints(c, "ground"), topY = ints(c, "topY"), groundY = ints(c, "groundY");
        // The raw palette also holds blocks the ground scan only passed through; keep the ones a column
        // names, and remap ids because canonical states can merge entries.
        JsonArray raw = c.getAsJsonArray("palette");
        boolean[] used = new boolean[raw.size()];
        for (int i = 0; i < top.length; i++) used[top[i]] = used[ground[i]] = true;
        List<String> palette = new ArrayList<>();
        Map<String, Integer> index = new HashMap<>();
        int[] remap = new int[raw.size()];
        for (int i = 0; i < remap.length; i++) {
            if (!used[i]) continue;
            String s = canon.apply(raw.get(i).getAsString());
            if (s.indexOf(':') < 0) s = "minecraft:" + s; // canonical states drop the namespace
            remap[i] = index.computeIfAbsent(s, k -> {
                palette.add(k);
                return palette.size() - 1;
            });
        }
        int width = x2 - x1 + 1, depth = z2 - z1 + 1;
        long differ = 0;
        for (int i = 0; i < top.length; i++) if (topY[i] != groundY[i]) differ++;
        long bytes = files.writeExport(name, overwrite, w -> {
            w.name("format").value("surface");
            if (heightmap.has("world")) w.name("world").value(heightmap.get("world").getAsString());
            w.name("from").beginArray().value(x1).value(z1).endArray();
            w.name("to").beginArray().value(x2).value(z2).endArray();
            w.name("width").value(width);
            w.name("depth").value(depth);
            w.name("order").value("z,x");
            w.name("palette").beginArray();
            for (String s : palette) w.value(s);
            w.endArray();
            array(w, "topY", topY, null);
            array(w, "top", top, remap);
            array(w, "groundY", groundY, null);
            array(w, "ground", ground, remap);
        });
        return String.format(Locale.ROOT, "Saved %d columns (%dx%d) to plugins/Ashlar/exports/%s.json.gz (format surface, %,d bytes,"
                + " palette %d). Arrays topY/top (first non-air block) and groundY/ground (below plants, trees and liquids), row-major"
                + " from [x0,z0] = [%d,%d]: index = (z - z0) * %d + (x - x0); top/ground index the palette. The top block sits above the"
                + " ground in %d columns (plants, snow, trees or water).", top.length, width, depth, name, bytes, palette.size(), x1, z1,
                width, differ);
    }

    private static int[] ints(JsonObject o, String key) {
        JsonArray a = o.getAsJsonArray(key);
        int[] out = new int[a.size()];
        for (int i = 0; i < out.length; i++) out[i] = a.get(i).getAsInt();
        return out;
    }

    private static void array(JsonWriter w, String key, int[] values, int[] remap) throws IOException {
        w.name(key).beginArray();
        for (int v : values) w.value(remap == null ? v : remap[v]);
        w.endArray();
    }
}
