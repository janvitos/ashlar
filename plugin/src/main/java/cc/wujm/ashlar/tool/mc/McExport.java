// SPDX-License-Identifier: AGPL-3.0-or-later
package cc.wujm.ashlar.tool.mc;

import cc.wujm.ashlar.config.ConfigHolder;
import cc.wujm.ashlar.engine.BukkitStateDefaults;
import cc.wujm.ashlar.engine.Region;
import cc.wujm.ashlar.rpc.InvocationContext;
import cc.wujm.ashlar.rpc.RpcHandler;
import cc.wujm.ashlar.tool.ArgParse;
import cc.wujm.ashlar.tool.Tool;
import cc.wujm.ashlar.tool.ToolArgError;
import cc.wujm.ashlar.tool.ToolResult;
import cc.wujm.ashlar.tool.ToolRunner;
import cc.wujm.ashlar.tool.ToolSpec;
import com.google.gson.JsonObject;
import com.google.gson.stream.JsonWriter;

import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.function.UnaryOperator;

/** {@code mc_export}: writes a box's blocks to {@code plugins/Ashlar/exports/<name>.json.gz} (Step 16). */
public final class McExport implements Tool {

    private static final Set<String> KEYS = Set.of("from", "to", "world", "name", "format", "canonical", "skipAir", "overwrite");

    private final ToolSpec spec = ToolSpec.load("mc_export");
    private final RpcHandler readRegionHandler;
    private final ConfigHolder config;
    private final CellFiles files;
    private final Executor compute;

    public McExport(RpcHandler readRegionHandler, ConfigHolder config, CellFiles files, Executor compute) {
        this.readRegionHandler = readRegionHandler;
        this.config = config;
        this.files = files;
        this.compute = compute;
    }

    @Override
    public ToolSpec spec() {
        return spec;
    }

    record Args(Region box, String world, String name, String format, boolean canonical, boolean skipAir, boolean overwrite) {
        static Args parse(JsonObject o) {
            for (String k : o.keySet()) if (!KEYS.contains(k)) throw new ToolArgError("unknown parameter: " + k);
            Region box = Region.of(ArgParse.requireCoords3(o, "from"), ArgParse.requireCoords3(o, "to"));
            String name = CellFiles.name(ArgParse.requireString(o, "name"), "name");
            String format = ArgParse.optEnum(o, "format", List.of("cells", "palette-rle"), "cells");
            boolean skipAir = ArgParse.optBoolean(o, "skipAir", false);
            if (skipAir && format.equals("palette-rle")) throw new ToolArgError("skipAir: only for format \"cells\" (palette-rle always covers the whole box)");
            return new Args(box, ArgParse.optString(o, "world"), name, format, ArgParse.optBoolean(o, "canonical", true), skipAir,
                    ArgParse.optBoolean(o, "overwrite", false));
        }
    }

    record Summary(long volume, long written, int palette, long bytes) {}

    @Override
    public CompletableFuture<ToolResult> call(InvocationContext ctx, JsonObject args) {
        return ToolRunner.runText("mc_export", () -> {
            Args a = Args.parse(args);
            long cap = config.get().limits().maxExportVolume();
            if (a.box().volume() > cap) {
                throw new ToolArgError("box: " + a.box().volume() + " blocks is over limits.max-export-volume " + cap + "; export it in several boxes");
            }
            if (!a.overwrite() && files.exportExists(a.name())) {
                throw new ToolArgError("export '" + a.name() + "' already exists; set overwrite:true explicitly");
            }
            long maxRead = config.get().limits().maxReadVolume();
            return BoxRead.read(readRegionHandler, ctx, a.world(), a.box(), maxRead, compute).thenApplyAsync(c -> {
                Summary s = write(files, a, c, a.canonical() ? s1 -> BukkitStateDefaults.CANON.canon(s1) : UnaryOperator.identity());
                return text(a, c.world(), s);
            }, compute);
        });
    }

    static Summary write(CellFiles files, Args a, BoxRead.Cells c, UnaryOperator<String> canon) {
        // Canonical states can merge palette entries: remap ids first.
        List<String> palette = new ArrayList<>();
        Map<String, Integer> index = new HashMap<>();
        int[] remap = new int[c.palette().size()];
        boolean[] air = new boolean[c.palette().size()];
        for (int i = 0; i < remap.length; i++) {
            String s = canon.apply(c.palette().get(i));
            if (s.indexOf(':') < 0) s = "minecraft:" + s; // canonical states drop the namespace
            remap[i] = index.computeIfAbsent(s, k -> {
                palette.add(k);
                return palette.size() - 1;
            });
            air[i] = CellMerge.phase(s) == CellMerge.Phase.AIR;
        }
        Region b = a.box();
        int sx = b.maxX() - b.minX() + 1, sz = b.maxZ() - b.minZ() + 1;
        long[] written = {0};
        long bytes = files.writeExport(a.name(), a.overwrite(), w -> {
            w.name("format").value(a.format());
            if (c.world() != null) w.name("world").value(c.world());
            w.name("bounds").beginObject();
            w.name("from").beginArray().value(b.minX()).value(b.minY()).value(b.minZ()).endArray();
            w.name("to").beginArray().value(b.maxX()).value(b.maxY()).value(b.maxZ()).endArray();
            w.endObject();
            w.name("canonical").value(a.canonical());
            w.name("volume").value(b.volume());
            if (a.format().equals("cells")) {
                w.name("skipAir").value(a.skipAir());
                w.name("cells").beginArray();
                int[] ids = c.ids();
                for (int i = 0; i < ids.length; i++) {
                    if (a.skipAir() && air[ids[i]]) continue;
                    int x = b.minX() + i % sx, z = b.minZ() + (i / sx) % sz, y = b.minY() + i / (sx * sz);
                    w.beginArray().value(x).value(y).value(z).value(palette.get(remap[ids[i]])).endArray();
                    written[0]++;
                }
                w.endArray();
            } else {
                w.name("order").value("y,z,x");
                w.name("palette").beginArray();
                for (String s : palette) w.value(s);
                w.endArray();
                runs(w, c.ids(), remap);
                written[0] = b.volume();
            }
        });
        return new Summary(b.volume(), written[0], palette.size(), bytes);
    }

    private static void runs(JsonWriter w, int[] ids, int[] remap) throws IOException {
        w.name("runs").beginArray();
        int i = 0;
        while (i < ids.length) {
            int id = remap[ids[i]], j = i + 1;
            while (j < ids.length && remap[ids[j]] == id) j++;
            w.beginArray().value(id).value(j - i).endArray();
            i = j;
        }
        w.endArray();
    }

    static String text(Args a, String world, Summary s) {
        Region b = a.box();
        return "Exported [" + b.minX() + "," + b.minY() + "," + b.minZ() + "]..[" + b.maxX() + "," + b.maxY() + "," + b.maxZ() + "]"
                + (world != null ? " in " + world : "") + " to plugins/Ashlar/exports/" + a.name() + ".json.gz: format " + a.format()
                + ", " + s.volume() + " cells read, " + s.written() + " written" + (a.skipAir() ? " (air skipped)" : "")
                + ", palette " + s.palette() + (a.canonical() ? " (canonical states)" : "") + ", " + s.bytes() + " bytes.\n"
                + "Use it with mc_build {cellsFile:\"" + a.name() + "\"} to rebuild, or mc_diff {against:{expectedFile:\"" + a.name()
                + "\"}} to compare later. Block states only: no sign text, inventories or entities.";
    }
}
