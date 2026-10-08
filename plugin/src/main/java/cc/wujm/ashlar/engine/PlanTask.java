// SPDX-License-Identifier: AGPL-3.0-or-later
package cc.wujm.ashlar.engine;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import org.bukkit.World;
import org.bukkit.block.data.BlockData;
import org.bukkit.block.data.Bisected;
import org.bukkit.block.data.type.Bed;
import org.bukkit.block.data.type.Door;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Tick-budgeted readonly virtual build. Never writes blocks or runs connection/fluid passes. */
public final class PlanTask extends BuildTask {
    record Cell(int x, int y, int z) {
        int[] array() { return new int[]{x,y,z}; }
    }
    private final World world;
    private final List<FillOp> fills;
    private final List<SparseOp> blocks;
    private final Region bounds;
    private final int minY, maxY;
    private final boolean preview;
    private final Region previewBounds;
    private final long requested;
    private final int neighbourCap;
    private final Map<Cell,BlockData> overlay = new HashMap<>();
    private final Map<Cell,BlockData> original = new HashMap<>();
    private final Set<Cell> overlaps = new HashSet<>();
    private final List<int[]> support = new ArrayList<>(), neighbours = new ArrayList<>();
    private final Set<Cell> neighbourSeen = new HashSet<>();
    private final JsonArray issues = new JsonArray(), collisions = new JsonArray(), overlapSamples = new JsonArray();
    private final Map<String,Long> materials = new java.util.TreeMap<>();
    private final RegionData.Encoder encoder = new RegionData.Encoder();
    private BlockData air;
    private SupportCheck supportCheck;
    private Iterator<Map.Entry<Cell,BlockData>> finalCursor;
    private int fillIndex, sparseIndex, x, y, z, paletteIndex;
    private boolean cursorReady, finalDone, supportsDone, previewDone, neighboursTruncated;
    private long accepted, skipped, changedCells, existingChanged, cleared, pairErrors;
    private RegionData previewData;
    private int[] paletteArgb;
    private boolean capture;
    private final List<BuildExpectation.Cell> expected = new ArrayList<>();
    private final Map<Cell,SignSnapshot> expectedSigns = new HashMap<>();
    private final Map<BlockData,String> expectedStates = new HashMap<>();
    public PlanTask captureExpectation() { capture=true; return this; }
    public BuildExpectation expectation(String worldName,boolean connected,boolean flowing) {
        return new BuildExpectation(worldName,bounds,expected,connected,flowing);
    }

    public PlanTask(Region ticketRegion, Region bounds, World world, List<FillOp> fills,
            List<SparseOp> blocks, int minY, int maxY, Region previewBounds, long requested, int neighbourCap) {
        super(ticketRegion);
        this.bounds = bounds; this.world = world; this.fills = fills; this.blocks = blocks;
        this.minY = minY; this.maxY = maxY; this.preview = previewBounds != null; this.previewBounds = previewBounds; this.requested = requested;
        this.neighbourCap = neighbourCap;
    }
    @Override public World world() { return world; }
    @Override public long volume() { return requested; }

    @Override public boolean step(long deadline) {
        if (air == null) air = BlockDataParser.parse("minecraft:air");
        int batch = 0;
        while (fillIndex < fills.size()) {
            FillOp op = fills.get(fillIndex); Region r = op.region();
            if (!cursorReady) init(r);
            int kind = PlanGeometry.target(op.mode(),r,x,y,z);
            if (kind != 0) apply(new Cell(x,y,z),kind == 2 ? air : op.block(),op.mode() == FillMode.KEEP,op.filter());
            advance(1);
            if (next(r)) { fillIndex++; cursorReady = false; }
            if (++batch % 128 == 0 && System.nanoTime() >= deadline) return false;
        }
        while (sparseIndex < blocks.size()) {
            SparseOp op = blocks.get(sparseIndex++);
            Cell c=new Cell(op.x(),op.y(),op.z());
            apply(c,op.block(),false,null);
            if (capture && op.sign()!=null) expectedSigns.put(c,expectedSigns.get(c).patch(op.sign()));
            advance(1);
            if (++batch % 128 == 0 && System.nanoTime() >= deadline) return false;
        }
        if (finalCursor == null) finalCursor = overlay.entrySet().iterator();
        if (!finalDone) {
            while (finalCursor.hasNext()) {
                var entry = finalCursor.next(); Cell c = entry.getKey(); BlockData data = entry.getValue();
                BlockData before = original.get(c);
                if (!before.equals(data)) {
                    changedCells++;
                    if (!before.getMaterial().isAir()) {
                        existingChanged++;
                        if (collisions.size() < 50) {
                            JsonObject collision = new JsonObject(); collision.add("pos",coords(c.array()));
                            collision.addProperty("existing",before.getAsString()); collision.addProperty("target",data.getAsString());
                            collision.addProperty("kind",data.getMaterial().isAir() ? "clear" : "replace"); collisions.add(collision);
                        }
                    }
                    if (data.getMaterial().isAir() && !before.getMaterial().isAir()) cleared++;
                    if (before.getMaterial().isSolid() && !data.getMaterial().isSolid()) {
                        addNeighbour(c.x+1,c.y,c.z); addNeighbour(c.x-1,c.y,c.z);
                        addNeighbour(c.x,c.y+1,c.z); addNeighbour(c.x,c.y-1,c.z);
                        addNeighbour(c.x,c.y,c.z+1); addNeighbour(c.x,c.y,c.z-1);
                    }
                }
                if (capture) expected.add(new BuildExpectation.Cell(new BuildExpectation.Pos(c.x,c.y,c.z),expectedStates.computeIfAbsent(data,BlockData::getAsString),expectedSigns.get(c)));
                materials.merge(data.getMaterial().getKey().toString(),1L,Long::sum);
                if (SupportCheck.needsCheck(data)) support.add(c.array());
                checkPair(c,data);
                if (++batch % 128 == 0 && System.nanoTime() >= deadline) return false;
            }
            finalDone = true;
            supportCheck = new SupportCheck(this::virtual,support,neighbours,true,true);
        }
        if (!supportsDone) {
            if (!supportCheck.step(deadline)) return false;
            supportsDone = true;
        }
        if (!previewDone) {
            if (!preview) { previewDone = true; return true; }
            if (!cursorReady) init(previewBounds);
            do {
                encoder.add(virtual(x,y,z).getAsString());
                if (next(previewBounds)) { previewData = encoder.finish(previewBounds); paletteArgb = new int[previewData.palette().size()]; previewDone = true; break; }
                if (++batch % 128 == 0 && System.nanoTime() >= deadline) return false;
            } while (true);
        }
        if (preview) {
            while (paletteIndex < paletteArgb.length) {
                paletteArgb[paletteIndex] = MapColorResolver.resolve(previewData.palette().get(paletteIndex));
                paletteIndex++;
                if (++batch % 64 == 0 && System.nanoTime() >= deadline) return false;
            }
        }
        return true;
    }
    private void init(Region r) { x = r.minX(); y = r.minY(); z = r.minZ(); cursorReady = true; }
    private boolean next(Region r) {
        if (x < r.maxX()) { x++; return false; }
        x = r.minX(); if (z < r.maxZ()) { z++; return false; }
        z = r.minZ(); if (y < r.maxY()) { y++; return false; }
        return true;
    }
    private BlockData virtual(int x, int y, int z) {
        if (y < minY || y >= maxY || Math.abs((long)x) >= 30_000_000 || Math.abs((long)z) >= 30_000_000) return air;
        Cell c = new Cell(x,y,z); BlockData data = overlay.get(c);
        if (data != null) return data;
        data = original.get(c);
        return data != null ? data : world.getBlockAt(x,y,z).getBlockData();
    }
    private void apply(Cell c, BlockData target, boolean keep, BlockData filter) {
        BlockData current = overlay.get(c);
        if (current == null) current = original.computeIfAbsent(c,k -> world.getBlockAt(k.x,k.y,k.z).getBlockData());
        if ((keep && !current.getMaterial().isAir()) || (filter != null && !current.matches(filter))) { skipped++; return; }
        if (overlay.containsKey(c) && overlaps.add(c) && overlapSamples.size() < 50) overlapSamples.add(coords(c.array()));
        if (capture) {
            if (target.getMaterial().name().endsWith("_SIGN")) {
                SignSnapshot sign=expectedSigns.get(c);
                if (sign==null && current.getMaterial()==target.getMaterial()) sign=SignAccess.read(world.getBlockAt(c.x,c.y,c.z));
                expectedSigns.put(c,current.getMaterial()==target.getMaterial() && sign!=null ? sign : SignSnapshot.empty());
            } else expectedSigns.remove(c);
        }
        overlay.put(c,target); accepted++;
    }
    private void addNeighbour(int x,int y,int z) {
        Cell c = new Cell(x,y,z);
        if (overlay.containsKey(c) || neighbourSeen.contains(c) || y < minY || y >= maxY) return;
        if (neighbours.size() >= neighbourCap) { neighboursTruncated = true; return; }
        neighbourSeen.add(c); neighbours.add(c.array());
    }
    private void checkPair(Cell c, BlockData data) {
        if (data instanceof Door d) {
            int dy = d.getHalf() == Bisected.Half.BOTTOM ? 1 : -1;
            BlockData other = virtual(c.x,c.y+dy,c.z);
            if (!(other instanceof Door o) || o.getMaterial() != d.getMaterial() || o.getHalf() == d.getHalf()
                    || o.getFacing() != d.getFacing() || o.getHinge() != d.getHinge()
                    || o.isOpen() != d.isOpen() || o.isPowered() != d.isPowered()) pairIssue(c,data,"missing or incompatible door half");
        } else if (data instanceof Bed b) {
            int s = b.getPart() == Bed.Part.FOOT ? 1 : -1;
            BlockData other = virtual(c.x+s*b.getFacing().getModX(),c.y,c.z+s*b.getFacing().getModZ());
            if (!(other instanceof Bed o) || o.getMaterial() != b.getMaterial() || o.getPart() == b.getPart()
                    || o.getFacing() != b.getFacing()) pairIssue(c,data,"missing or incompatible bed part");
        }
    }
    private void pairIssue(Cell c,BlockData data,String reason) {
        pairErrors++;
        if (issues.size() < 50) {
            JsonObject issue = new JsonObject(); issue.addProperty("severity","error"); issue.addProperty("kind","paired_block");
            issue.add("pos",coords(c.array())); issue.addProperty("block",data.getAsString()); issue.addProperty("reason",reason); issues.add(issue);
        }
    }
    private static JsonArray coords(int[] p) { JsonArray a = new JsonArray(); for (int v:p) a.add(v); return a; }
    @Override public JsonElement buildResult(long queuedMs,long elapsedMs) {
        JsonObject r = new JsonObject(); r.addProperty("world",world.getName());
        r.addProperty("dryRun",true); r.addProperty("valid",pairErrors == 0);
        r.addProperty("requestedVolume",requested); r.addProperty("acceptedVisits",accepted); r.addProperty("skippedVisits",skipped);
        r.addProperty("uniquePlannedCells",overlay.size()); r.addProperty("blockStateChanges",changedCells);
        r.addProperty("existingNonAirChanged",existingChanged); r.addProperty("clearedCells",cleared);
        r.addProperty("overlappingCells",overlaps.size()); r.addProperty("pairErrors",pairErrors);
        r.add("collisionSamples",collisions); r.addProperty("collisionSamplesTruncated",existingChanged > 50);
        r.add("overlapSamples",overlapSamples); r.addProperty("overlapSamplesTruncated",overlaps.size() > 50);
        r.addProperty("signEdits",blocks.stream().filter(b -> b.sign() != null).count());
        r.addProperty("supportWarnings",supportCheck.warnings().size());
        r.addProperty("supportWarningsTruncated",supportCheck.truncated()); r.addProperty("neighbourChecksTruncated",neighboursTruncated);
        r.addProperty("strictSitePass",pairErrors == 0 && supportCheck.warnings().isEmpty() && !supportCheck.truncated() && !neighboursTruncated);
        r.addProperty("pairIssuesTruncated",pairErrors > 50);
        JsonArray dimensions = new JsonArray(); dimensions.add((long)bounds.maxX()-bounds.minX()+1);
        dimensions.add((long)bounds.maxY()-bounds.minY()+1); dimensions.add((long)bounds.maxZ()-bounds.minZ()+1); r.add("dimensions",dimensions);
        r.add("from",coords(new int[]{bounds.minX(),bounds.minY(),bounds.minZ()})); r.add("to",coords(new int[]{bounds.maxX(),bounds.maxY(),bounds.maxZ()}));
        for (var w:supportCheck.warnings()) {
            JsonObject i = new JsonObject(); i.addProperty("severity","warning"); i.addProperty("kind","support");
            i.add("pos",coords(new int[]{w.x(),w.y(),w.z()})); i.addProperty("block",w.block()); i.addProperty("reason",w.reason()); issues.add(i);
        }
        r.add("issues",issues); JsonObject counts = new JsonObject(); materials.forEach(counts::addProperty); r.add("finalPlannedMaterials",counts);
        r.addProperty("elapsedMs",elapsedMs);
        r.addProperty("assumptions","Read-only observation, not a reservation. Final block states before automatic connections/chest pairing. No fluid spreading, entities, or concurrent-edit simulation. Support rules are advisory. Collision counts cannot distinguish terrain from structures. Changes exclude block-entity/NBT edits.");
        return r;
    }
    public RegionData previewData() { return previewData; }
    public int[] paletteArgb() { return paletteArgb; }
}
