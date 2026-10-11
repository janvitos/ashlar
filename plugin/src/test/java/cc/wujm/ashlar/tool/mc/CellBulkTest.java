// SPDX-License-Identifier: AGPL-3.0-or-later
package cc.wujm.ashlar.tool.mc;

import cc.wujm.ashlar.config.PluginConfig;
import cc.wujm.ashlar.engine.Region;
import cc.wujm.ashlar.tool.ToolArgError;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Random;
import java.util.TreeMap;
import java.util.function.UnaryOperator;

import static org.junit.jupiter.api.Assertions.*;

/** Step 16: cell sets, merging, part packing, staged/exported files and the cells build plan. */
class CellBulkTest {

    private static PluginConfig config(long maxBlocks, int maxChunks) {
        return new PluginConfig(
                new PluginConfig.ServerConfig(true, "0.0.0.0", 8765, "0123456789abcdef", List.of()),
                new PluginConfig.LimitsConfig(maxBlocks, 200_000, 20, 16, maxChunks, 2000, 2_000_000),
                new PluginConfig.WorldConfig("world", List.of("world"),
                        new PluginConfig.WorldConfig.BuildRegion(false, -1000, -1000, 1000, 1000)),
                new PluginConfig.SnapshotConfig(true, 20, 200_000),
                PluginConfig.JournalConfig.DEFAULT,
                new PluginConfig.LoggingConfig(true),
                new PluginConfig.RunCommandConfig(true),
                new PluginConfig.EngineConfig(true, true, ""),
                new PluginConfig.AgentConfig(PluginConfig.AgentConfig.Mode.OFF, 5, 500, true, false,
                        new PluginConfig.AgentConfig.ModelConfig("http://localhost", "", "test-model", 25, 120_000,
                                "high", "", false),
                        new PluginConfig.AgentConfig.LimitsConfig(40, 0, 0, 2, 6, 30),
                        new PluginConfig.AgentConfig.PricingConfig(0.30, 0.006, 1.20, "USD", "always", 0.5)),
                "en");
    }

    private static JsonObject json(String s) {
        return JsonParser.parseString(s).getAsJsonObject();
    }

    /** A small terrain patch: a stone/dirt mound with grass on top and snow layers on high columns. */
    private static List<Object[]> terrain() {
        List<Object[]> out = new ArrayList<>();
        for (int x = -5; x < 15; x++) {
            for (int z = 3; z < 21; z++) {
                int h = 60 + Math.floorMod(x * 7 + z * 3, 5);
                for (int y = 58; y <= h; y++) out.add(new Object[]{x, y, z, y == h ? "grass_block[snowy=false]" : y > h - 3 ? "dirt" : "stone"});
                if (h >= 63) out.add(new Object[]{x, h + 1, z, "minecraft:snow[layers=1]"});
                out.add(new Object[]{x, h + 2, z, "air"});
            }
        }
        return out;
    }

    private static CellSet set(List<Object[]> cells) {
        CellSet.Builder b = new CellSet.Builder(1_000_000, "cells");
        for (Object[] c : cells) b.add((int) c[0], (int) c[1], (int) c[2], (String) c[3]);
        return b.build();
    }

    private static List<String> states(CellSet s) {
        List<String> out = new ArrayList<>();
        for (int i = 0; i < s.size(); i++) out.add(CellSet.x(s.keys[i]) + "," + CellSet.y(s.keys[i]) + "," + CellSet.z(s.keys[i]) + "=" + s.state(i));
        return out;
    }

    @Test
    void cellSetIsOrderIndependentAndRejectsConflicts() {
        List<Object[]> cells = terrain();
        CellSet a = set(cells);
        List<Object[]> shuffled = new ArrayList<>(cells);
        Collections.shuffle(shuffled, new Random(16));
        shuffled.add(cells.get(5)); // equal duplicate collapses
        CellSet b = set(shuffled);
        assertEquals(states(a), states(b));
        assertEquals(a.palette, b.palette);
        assertArrayEquals(a.ids, b.ids);
        assertEquals(CellBuild.fingerprint(a, false, null, null, null), CellBuild.fingerprint(b, false, null, null, null));
        assertEquals(cells.size(), a.size());
        for (int i = 1; i < a.size(); i++) assertTrue(a.keys[i - 1] < a.keys[i]);
        assertEquals("minecraft:dirt", a.state(a.find(CellSet.key(-5, 63, 3))));
        assertTrue(a.palette.contains("minecraft:grass_block[snowy=false]"));
        long k = CellSet.key(-29_999_999, -2048, 29_999_999);
        assertEquals(-29_999_999, CellSet.x(k));
        assertEquals(-2048, CellSet.y(k));
        assertEquals(29_999_999, CellSet.z(k));

        CellSet.Builder c = new CellSet.Builder(10, "cells");
        c.add(1, 2, 3, "stone");
        c.add(1, 2, 3, "minecraft:dirt");
        ToolArgError e = assertThrows(ToolArgError.class, c::build);
        assertTrue(e.getMessage().contains("[1,2,3] is given twice"), e.getMessage());
        assertThrows(ToolArgError.class, () -> new CellSet.Builder(10, "cells").add(0, 0, 0, "Stone"));
        assertThrows(ToolArgError.class, () -> new CellSet.Builder(10, "cells").add(0, 0, 0, "stone[facing=]"));
        assertThrows(ToolArgError.class, () -> new CellSet.Builder(10, "cells").add(0, 5000, 0, "stone"));
        assertThrows(ToolArgError.class, () -> new CellSet.Builder(10, "cells").build());
        CellSet.Builder full = new CellSet.Builder(1, "cells");
        full.add(0, 0, 0, "stone");
        assertThrows(ToolArgError.class, () -> full.add(1, 0, 0, "stone"));
    }

    @Test
    void phasesSortSupportedBlocksLast() {
        assertEquals(CellMerge.Phase.SOLID, CellMerge.phase("minecraft:stone"));
        assertEquals(CellMerge.Phase.SOLID, CellMerge.phase("minecraft:oak_stairs[facing=east]"));
        assertEquals(CellMerge.Phase.SOLID, CellMerge.phase("minecraft:water"));
        assertEquals(CellMerge.Phase.AIR, CellMerge.phase("minecraft:cave_air"));
        for (String s : List.of("minecraft:snow[layers=2]", "minecraft:red_carpet", "minecraft:torch", "minecraft:oak_door[half=upper]",
                "minecraft:red_bed[part=head]", "minecraft:rail", "minecraft:oak_sign", "minecraft:poppy", "minecraft:oak_sapling",
                "minecraft:lantern[hanging=true]")) {
            assertEquals(CellMerge.Phase.LATE, CellMerge.phase(s), s);
        }
    }

    @Test
    void mergeCoversEveryCellExactlyAndIsDeterministic() {
        List<Object[]> cells = terrain();
        CellSet s = set(cells);
        for (long max : new long[]{Long.MAX_VALUE, 40, 7}) {
            List<CellMerge.Box> boxes = CellMerge.merge(s, max);
            boolean[] seen = new boolean[s.size()];
            long total = 0;
            for (CellMerge.Box b : boxes) {
                assertTrue(b.volume() <= max);
                assertTrue(b.x1() - b.x0() < CellMerge.MAX_SPAN && b.z1() - b.z0() < CellMerge.MAX_SPAN);
                for (int y = b.y0(); y <= b.y1(); y++) {
                    for (int z = b.z0(); z <= b.z1(); z++) {
                        for (int x = b.x0(); x <= b.x1(); x++) {
                            int i = s.find(CellSet.key(x, y, z));
                            assertTrue(i >= 0, "box covers a missing cell");
                            assertFalse(seen[i], "cell in two boxes");
                            assertEquals(b.id(), s.ids[i]);
                            seen[i] = true;
                        }
                    }
                }
                total += b.volume();
            }
            assertEquals(s.size(), total);
        }
        List<Object[]> shuffled = new ArrayList<>(cells);
        Collections.shuffle(shuffled, new Random(7));
        assertEquals(CellMerge.merge(s, 40), CellMerge.merge(set(shuffled), 40));

        // One solid cuboid becomes one box.
        List<Object[]> cube = new ArrayList<>();
        for (int x = 0; x < 10; x++) for (int y = 0; y < 3; y++) for (int z = 0; z < 4; z++) cube.add(new Object[]{x, y, z, "stone"});
        assertEquals(List.of(new CellMerge.Box(0, 0, 0, 9, 2, 3, 0)), CellMerge.merge(set(cube), Long.MAX_VALUE));
        // A 200 x 200 two-layer patch: x/z spans stop at 128, so 4 boxes per layer.
        List<Object[]> flat = new ArrayList<>();
        for (int x = 0; x < 200; x++) for (int z = 0; z < 200; z++) {
            flat.add(new Object[]{x, 64, z, "dirt"});
            flat.add(new Object[]{x, 65, z, "grass_block"});
        }
        assertEquals(8, CellMerge.merge(set(flat), Long.MAX_VALUE).size());
    }

    @Test
    void packRespectsLimitsAndPhaseOrder() {
        CellSet s = set(terrain());
        List<CellMerge.Box> boxes = CellMerge.merge(s, 50);
        List<CellMerge.Part> parts = CellMerge.pack(boxes, s.palette, new CellMerge.Limits(50, 1, Long.MAX_VALUE, Long.MAX_VALUE));
        int phase = 0, count = 0;
        for (CellMerge.Part p : parts) {
            assertTrue(p.volume() <= 50);
            Region e = p.envelope();
            // A single box wider than the chunk limit still forms its own part.
            assertTrue(p.boxes().size() == 1 || CellMerge.chunks(new int[]{e.minX(), e.minY(), e.minZ(), e.maxX(), e.maxY(), e.maxZ()}) == 1);
            assertTrue(p.phase().ordinal() >= phase, "phases out of order");
            phase = p.phase().ordinal();
            for (CellMerge.Box b : p.boxes()) assertEquals(p.phase(), CellMerge.phase(s.palette.get(b.id())));
            count += p.boxes().size();
        }
        assertEquals(boxes.size(), count);
        assertEquals(CellMerge.Phase.LATE, parts.get(parts.size() - 1).phase());
        // The patch spans x -5..14 and z 3..20: chunk columns -1..0 by 0..1, so a 1-chunk limit forces at least 4 parts per phase.
        assertTrue(parts.size() >= 12, "parts " + parts.size());
        assertEquals(parts, CellMerge.pack(boxes, s.palette, new CellMerge.Limits(50, 1, Long.MAX_VALUE, Long.MAX_VALUE)));

        // Envelope cap (snapshot volume) also splits.
        for (CellMerge.Part p : CellMerge.pack(boxes, s.palette, new CellMerge.Limits(1000, 64, Long.MAX_VALUE, 300))) {
            assertTrue(p.envelope().volume() <= 300 || p.boxes().size() == 1);
        }
    }

    @Test
    void stagedUploadRoundTrips(@TempDir Path dir) throws Exception {
        CellFiles files = new CellFiles(dir);
        List<Object[]> cells = terrain();
        JsonArray first = new JsonArray(), second = new JsonArray();
        for (int i = 0; i < cells.size(); i++) {
            Object[] c = cells.get(i);
            JsonArray a = new JsonArray();
            a.add((int) c[0]);
            a.add((int) c[1]);
            a.add((int) c[2]);
            a.add((String) c[3]);
            (i < 1000 ? first : second).add(a);
        }
        assertEquals(1000, files.stage("hill", first, true, 1_000_000).cells());
        CellFiles.Staged st = files.stage("hill", second, false, 1_000_000);
        assertEquals(cells.size(), st.cells());
        assertEquals(2, st.calls());
        assertNull(files.find("hill"), "unfinished uploads are not buildable");
        assertEquals(cells.size(), files.finalizeStage("hill", 1_000_000).cells());
        Path p = files.find("hill");
        assertEquals("plugins/Ashlar/staging/hill.json.gz", CellFiles.where(p));
        assertFalse(Files.exists(dir.resolve("staging/hill.stage.gz")));

        CellSet.Builder b = new CellSet.Builder(1_000_000, "file");
        CellFiles.read(p, "file", b::add);
        assertEquals(states(set(cells)), states(b.build()));

        // A reset starts over; the cap counts across calls.
        assertEquals(cells.size() - 1000, files.stage("again", second, true, 1_000_000).cells());
        assertThrows(ToolArgError.class, () -> files.stage("again", second, false, cells.size()));
        assertThrows(ToolArgError.class, () -> files.finalizeStage("missing", 10));
        assertThrows(ToolArgError.class, () -> files.stage("../x", first, true, 10));
        assertThrows(ToolArgError.class, () -> CellFiles.name("Hill", "name"));
        assertThrows(ToolArgError.class, () -> CellFiles.name("hill.json", "name"));
        JsonArray bad = new JsonArray();
        bad.add(JsonParser.parseString("[1,2,\"stone\"]"));
        assertThrows(ToolArgError.class, () -> files.stage("bad", bad, true, 10));
    }

    @Test
    void exportRoundTripsInBothFormats(@TempDir Path dir) {
        CellFiles files = new CellFiles(dir);
        Region box = new Region(10, 64, -3, 13, 65, -1); // 4 x 2 x 3
        List<String> palette = List.of("minecraft:air", "minecraft:stone", "minecraft:oak_stairs[facing=east,half=bottom,shape=straight,waterlogged=false]");
        int[] ids = new int[(int) box.volume()];
        for (int i = 0; i < ids.length; i++) ids[i] = i < 12 ? 1 : (i % 3 == 0 ? 2 : 0);
        BoxRead.Cells c = new BoxRead.Cells(ids, palette, "world");

        McExport.Summary s = McExport.write(files, new McExport.Args(box, null, "rle", "palette-rle", false, false, false), c, UnaryOperator.identity());
        assertEquals(24, s.volume());
        assertEquals(24, s.written());
        TreeMap<String, String> rle = new TreeMap<>();
        CellFiles.read(files.find("rle"), "rle", (x, y, z, st) -> rle.put(x + "," + y + "," + z, st));
        assertEquals(24, rle.size());
        assertEquals("minecraft:stone", rle.get("10,64,-3"));
        assertEquals("minecraft:stone", rle.get("13,64,-1"));
        assertEquals(palette.get(2), rle.get("10,65,-3")); // i = 12

        // canonical mapping merges palette entries; skipAir drops air.
        // Like StateCanon: defaults dropped and no namespace.
        UnaryOperator<String> canon = st -> st.startsWith("minecraft:oak_stairs") ? "oak_stairs[facing=east]" : st.substring("minecraft:".length());
        McExport.Summary t = McExport.write(files, new McExport.Args(box, null, "sparse", "cells", true, true, false), c, canon);
        TreeMap<String, String> sparse = new TreeMap<>();
        CellFiles.read(files.find("sparse"), "sparse", (x, y, z, st) -> sparse.put(x + "," + y + "," + z, st));
        assertEquals(t.written(), sparse.size());
        assertEquals(12 + 4, sparse.size());
        assertEquals("minecraft:oak_stairs[facing=east]", sparse.get("10,65,-3"));
        for (var e : sparse.entrySet()) assertEquals(e.getValue(), rle.get(e.getKey()).startsWith("minecraft:oak_stairs") ? "minecraft:oak_stairs[facing=east]" : rle.get(e.getKey()));

        assertTrue(files.exportExists("rle"));
        assertThrows(ToolArgError.class, () -> McExport.write(files, new McExport.Args(box, null, "rle", "cells", true, false, false), c, UnaryOperator.identity()));
        assertThrows(ToolArgError.class, () -> McExport.Args.parse(json("{\"from\":[0,0,0],\"to\":[1,1,1],\"name\":\"x\",\"format\":\"palette-rle\",\"skipAir\":true}")));
        assertThrows(ToolArgError.class, () -> McExport.Args.parse(json("{\"from\":[0,0,0],\"to\":[1,1,1],\"name\":\"x/y\"}")));
    }

    @Test
    void paletteRleRunsMustCoverTheBounds(@TempDir Path dir) throws Exception {
        Path f = dir.resolve("bad.json");
        Files.writeString(f, "{\"bounds\":{\"from\":[0,0,0],\"to\":[1,0,1]},\"palette\":[\"minecraft:stone\"],\"runs\":[[0,3]]}");
        ToolArgError e = assertThrows(ToolArgError.class, () -> CellFiles.read(f, "f", (x, y, z, s) -> {}));
        assertTrue(e.getMessage().contains("runs cover 3 cells but bounds hold 4"), e.getMessage());
        Files.writeString(f, "[[0,0,0,\"stone\"],[1,0,0,\"dirt\"]]");
        List<String> got = new ArrayList<>();
        CellFiles.read(f, "f", (x, y, z, s) -> got.add(x + ":" + s));
        assertEquals(List.of("0:stone", "1:dirt"), got);
        Files.writeString(f, "{\"other\":1}");
        assertThrows(ToolArgError.class, () -> CellFiles.read(f, "f", (x, y, z, s) -> {}));
    }

    @Test
    void planSplitsPartsAndChecksResumeTokens() {
        JsonArray cells = new JsonArray();
        for (Object[] c : terrain()) cells.add(JsonParser.parseString("[" + c[0] + "," + c[1] + "," + c[2] + ",\"" + c[3] + "\"]"));
        JsonObject args = new JsonObject();
        args.add("cells", cells);
        PluginConfig cfg = config(200, 1024);
        CellBuild.Plan plan = CellBuild.plan(args, cfg, null);
        assertTrue(plan.parts().size() > 1);
        assertEquals(plan.parts().size(), plan.args().size());
        assertEquals(1, plan.start());
        long cellsIn = 0;
        for (CellMerge.Part p : plan.parts()) {
            assertTrue(p.volume() <= 200);
            cellsIn += p.volume();
        }
        assertEquals(plan.cells().size(), cellsIn);
        assertTrue(plan.fingerprint().matches("[0-9a-f]{12}"));
        assertTrue(CellBuild.summary(plan).startsWith("Cells: " + plan.cells().size() + " ("), CellBuild.summary(plan));

        args.addProperty("resume", plan.fingerprint() + ":2");
        assertEquals(2, CellBuild.plan(args, cfg, null).start());
        args.addProperty("resume", "000000000000:2");
        assertThrows(ToolArgError.class, () -> CellBuild.plan(args, cfg, null));
        args.addProperty("resume", plan.fingerprint() + ":" + (plan.parts().size() + 1));
        assertThrows(ToolArgError.class, () -> CellBuild.plan(args, cfg, null));
        args.addProperty("resume", "nope");
        assertThrows(ToolArgError.class, () -> CellBuild.plan(args, cfg, null));
        args.remove("resume");
        // Other options change the fingerprint.
        args.addProperty("snapshot", true);
        assertNotEquals(plan.fingerprint(), CellBuild.plan(args, cfg, null).fingerprint());

        assertTrue(CellBuild.applies(json("{\"cellsFile\":\"x\"}")));
        assertFalse(CellBuild.applies(json("{\"fills\":[]}")));
        assertThrows(ToolArgError.class, () -> CellBuild.plan(json("{\"cells\":[[0,0,0,\"stone\"]],\"fills\":[]}"), cfg, null));
        assertThrows(ToolArgError.class, () -> CellBuild.plan(json("{\"cells\":[[0,0,0,\"stone\"]],\"cellsFile\":\"x\"}"), cfg, null));
        assertThrows(ToolArgError.class, () -> CellBuild.plan(json("{\"cellsFile\":\"x\"}"), cfg, null));
    }

    @Test
    void planReadsCellFiles(@TempDir Path dir) {
        CellFiles files = new CellFiles(dir);
        ToolArgError e = assertThrows(ToolArgError.class, () -> CellBuild.plan(json("{\"cellsFile\":\"none\"}"), config(500_000, 1024), files));
        assertTrue(e.getMessage().contains("no finished staged upload"), e.getMessage());
        JsonArray a = JsonParser.parseString("[[0,64,0,\"stone\"],[1,64,0,\"stone\"],[0,65,0,\"torch\"]]").getAsJsonArray();
        files.stage("tiny", a, true, 100);
        files.finalizeStage("tiny", 100);
        CellBuild.Plan p = CellBuild.plan(json("{\"cellsFile\":\"tiny\"}"), config(500_000, 1024), files);
        assertEquals(3, p.cells().size());
        assertEquals(2, p.parts().size()); // solids, then the torch
        assertEquals(CellMerge.Phase.LATE, p.parts().get(1).phase());
    }
}
