// SPDX-License-Identifier: AGPL-3.0-or-later
package cc.wujm.ashlar.tool.mc;

import cc.wujm.ashlar.engine.Region;
import cc.wujm.ashlar.engine.RegionData;
import cc.wujm.ashlar.rpc.InvocationContext;
import cc.wujm.ashlar.rpc.RpcHandler;
import cc.wujm.ashlar.tool.ToolArgError;
import com.google.gson.JsonObject;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;

/**
 * Reads a box larger than {@code limits.max-read-volume} through the normal tick-budgeted
 * {@code read_region} path, one x/z tile at a time, into palette indexes in RegionData order
 * (y outer, z middle, x inner). Decoding runs on {@code compute}, never on the main thread.
 */
final class BoxRead {
    private BoxRead() {}

    /** Box cells as palette indexes, and the world name the reads resolved to. */
    record Cells(int[] ids, List<String> palette, String world) {}

    /** Splits the box into x/z tiles of at most {@code maxRead} cells each, full height. */
    static List<Region> tiles(Region box, long maxRead) {
        int sy = box.maxY() - box.minY() + 1, sz = box.maxZ() - box.minZ() + 1;
        if (sy > maxRead) throw new ToolArgError("box: too tall to read (" + sy + " blocks; max-read-volume is " + maxRead + ")");
        int wz = (int) Math.min(sz, Math.max(1, maxRead / sy));
        int wx = (int) Math.max(1, maxRead / ((long) sy * wz));
        List<Region> out = new ArrayList<>();
        for (int x = box.minX(); x <= box.maxX(); x += wx) {
            for (int z = box.minZ(); z <= box.maxZ(); z += wz) {
                out.add(new Region(x, box.minY(), z, Math.min(box.maxX(), x + wx - 1), box.maxY(), Math.min(box.maxZ(), z + wz - 1)));
            }
        }
        return out;
    }

    static CompletableFuture<Cells> read(RpcHandler readRegion, InvocationContext ctx, String world, Region box, long maxRead, Executor compute) {
        if (box.volume() > Integer.MAX_VALUE - 8) throw new ToolArgError("box: too large");
        List<Region> tiles = tiles(box, maxRead);
        int sx = box.maxX() - box.minX() + 1, sz = box.maxZ() - box.minZ() + 1;
        int[] ids = new int[(int) box.volume()];
        List<String> palette = new ArrayList<>();
        Map<String, Integer> index = new HashMap<>();
        String[] resolved = new String[1];
        CompletableFuture<Void> chain = CompletableFuture.completedFuture(null);
        for (Region t : tiles) {
            chain = chain.thenCompose(v -> {
                JsonObject params = new JsonObject();
                if (world != null) params.addProperty("world", world);
                params.add("from", JsonUtil.intArray(t.minX(), t.minY(), t.minZ()));
                params.add("to", JsonUtil.intArray(t.maxX(), t.maxY(), t.maxZ()));
                return readRegion.handle(ctx, params).thenAcceptAsync(el -> {
                    JsonObject r = el.getAsJsonObject();
                    if (r.has("world") && r.get("world").isJsonPrimitive()) resolved[0] = r.get("world").getAsString();
                    Iterator<String> it = RegionData.fromJson(r).iterator();
                    for (int y = t.minY(); y <= t.maxY(); y++) {
                        for (int z = t.minZ(); z <= t.maxZ(); z++) {
                            for (int x = t.minX(); x <= t.maxX(); x++) {
                                int id = index.computeIfAbsent(it.next(), k -> {
                                    palette.add(k);
                                    return palette.size() - 1;
                                });
                                ids[((y - box.minY()) * sz + (z - box.minZ())) * sx + (x - box.minX())] = id;
                            }
                        }
                    }
                }, compute);
            });
        }
        return chain.thenApply(v -> new Cells(ids, palette, resolved[0] != null ? resolved[0] : world));
    }
}
