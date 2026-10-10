// SPDX-License-Identifier: AGPL-3.0-or-later
package cc.wujm.ashlar.engine;

import cc.wujm.ashlar.config.ConfigHolder;
import cc.wujm.ashlar.render.FirstPersonCamera;
import cc.wujm.ashlar.render.FirstPersonRenderer;
import cc.wujm.ashlar.render.Sightline;
import cc.wujm.ashlar.rpc.ErrorCode;
import cc.wujm.ashlar.rpc.InvocationContext;
import cc.wujm.ashlar.rpc.MainThread;
import cc.wujm.ashlar.rpc.RpcError;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;

/**
 * First-person renders and sightlines (Step 14). The main thread only copies already-loaded chunk
 * columns ({@link ViewSnapshotTask}, tick-budgeted, no chunk loads or tickets); every ray and the
 * PNG encoding run on the render executor.
 */
public final class ViewService {

    /** A resolved eye: exact position, look direction and world. {@code player} is null for explicit eyes. */
    public record Eye(World world, double x, double y, double z, double yaw, double pitch, String player) {}

    public record Snapshot(SnapshotVoxels voxels, int requested, int loaded) {}

    private final ConfigHolder config;
    private final TickBudgetExecutor executor;
    private final ExecutorService renderExecutor;

    public ViewService(ConfigHolder config, TickBudgetExecutor executor, ExecutorService renderExecutor) {
        this.config = config;
        this.executor = executor;
        this.renderExecutor = renderExecutor;
    }

    /** A player's live eye position and look direction, read on the main thread. */
    public CompletableFuture<Eye> playerEye(String name) {
        return MainThread.call(() -> {
            Player p = Bukkit.getPlayerExact(name);
            if (p == null) throw new RpcError(ErrorCode.BAD_REQUEST, "player '" + name + "' is not online");
            Location l = p.getEyeLocation();
            String world = l.getWorld().getName();
            if (!config.get().world().allowedWorlds().contains(world)) {
                throw new RpcError(ErrorCode.WORLD_NOT_ALLOWED, "world not allowed: '" + world + "'");
            }
            return new Eye(l.getWorld(), l.getX(), l.getY(), l.getZ(), l.getYaw(), l.getPitch(), p.getName());
        });
    }

    /** Copies the loaded columns among {@code chunks}; rejects more than {@code limits.max-chunks-per-operation}. */
    public CompletableFuture<Snapshot> snapshot(InvocationContext ctx, World world, Set<Long> chunks) {
        MainThread.assertNotPrimary("ViewService.snapshot");
        long max = config.get().limits().maxChunksPerOperation();
        if (chunks.size() > max) {
            throw new RpcError(ErrorCode.VOLUME_EXCEEDED, "the view spans " + chunks.size() + " chunks, exceeding limit " + max
                    + "; use a smaller distance or narrower fov");
        }
        ViewSnapshotTask task = new ViewSnapshotTask(world, new ArrayList<>(chunks));
        return executor.submit(task, ctx).thenApplyAsync(ignored -> new Snapshot(task.voxels(), task.requested(), task.loaded()), renderExecutor);
    }

    public record Render(FirstPersonRenderer.Output output, byte[] png, Snapshot snapshot) {}

    public CompletableFuture<Render> render(InvocationContext ctx, World world, FirstPersonCamera cam) {
        return snapshot(ctx, world, cam.chunks()).thenApplyAsync(s -> {
            FirstPersonRenderer.Output out = FirstPersonRenderer.render(s.voxels(), s.voxels()::color, cam);
            try {
                return new Render(out, RenderService.encodePng(out.image().pixels(), out.image().width(), out.image().height()), s);
            } catch (java.io.IOException e) {
                throw new RpcError(ErrorCode.INTERNAL, "PNG encoding failed: " + e.getMessage());
            }
        }, renderExecutor);
    }

    public record Lines(List<Sightline.Result> results, Snapshot snapshot) {}

    public CompletableFuture<Lines> sightlines(InvocationContext ctx, Eye eye, List<int[]> targets, List<Sightline.Ignore> ignore) {
        Set<Long> chunks = new LinkedHashSet<>();
        for (int[] t : targets) segmentChunks(eye.x(), eye.z(), t[0] + .5, t[2] + .5, chunks);
        return snapshot(ctx, eye.world(), chunks).thenApplyAsync(s -> {
            List<Sightline.Result> out = new ArrayList<>();
            for (int[] t : targets) out.add(Sightline.target(s.voxels(), eye.x(), eye.y(), eye.z(), t, ignore));
            return new Lines(out, s);
        }, renderExecutor);
    }

    public record Cone(Sightline.ConeHit[][] grid, Snapshot snapshot) {}

    public CompletableFuture<Cone> cone(InvocationContext ctx, Eye eye, double fov, int rays, int distance, List<Sightline.Ignore> ignore) {
        FirstPersonCamera cam = new FirstPersonCamera(eye.x(), eye.y(), eye.z(), eye.yaw(), eye.pitch(), fov, distance, 64, 64);
        return snapshot(ctx, eye.world(), cam.chunks()).thenApplyAsync(s -> new Cone(
                Sightline.cone(s.voxels(), eye.x(), eye.y(), eye.z(), eye.yaw(), eye.pitch(), fov, rays, distance, ignore), s), renderExecutor);
    }

    /** Chunk columns an x/z segment passes through, sampled every half block with a one-block margin. */
    static void segmentChunks(double x0, double z0, double x1, double z1, Set<Long> out) {
        double len = Math.hypot(x1 - x0, z1 - z0);
        int n = Math.max(1, (int) Math.ceil(len * 2));
        for (int i = 0; i <= n; i++) {
            double x = x0 + (x1 - x0) * i / n, z = z0 + (z1 - z0) * i / n;
            for (int dx = -1; dx <= 1; dx += 2) {
                for (int dz = -1; dz <= 1; dz += 2) {
                    out.add(FirstPersonCamera.pack(Math.floorDiv((int) Math.floor(x + dx), 16), Math.floorDiv((int) Math.floor(z + dz), 16)));
                }
            }
        }
    }
}
