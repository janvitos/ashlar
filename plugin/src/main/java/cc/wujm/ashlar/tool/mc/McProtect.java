// SPDX-License-Identifier: AGPL-3.0-or-later
package cc.wujm.ashlar.tool.mc;

import cc.wujm.ashlar.config.ConfigHolder;
import cc.wujm.ashlar.engine.Region;
import cc.wujm.ashlar.engine.RequestValidator;
import cc.wujm.ashlar.protect.ProtectedRegion;
import cc.wujm.ashlar.protect.ProtectedRegions;
import cc.wujm.ashlar.rpc.InvocationContext;
import cc.wujm.ashlar.rpc.MainThread;
import cc.wujm.ashlar.tool.ArgParse;
import cc.wujm.ashlar.tool.Tool;
import cc.wujm.ashlar.tool.ToolArgError;
import cc.wujm.ashlar.tool.ToolResult;
import cc.wujm.ashlar.tool.ToolRunner;
import cc.wujm.ashlar.tool.ToolSpec;
import com.google.gson.JsonObject;
import org.bukkit.Bukkit;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

/** {@code mc_protect}: add, list and remove protected regions (Step 13). Never writes the world. */
public final class McProtect implements Tool {

    private static final List<String> ACTIONS = List.of("add", "list", "remove");

    private final ToolSpec spec = ToolSpec.load("mc_protect");
    private final ProtectedRegions regions;
    private final ConfigHolder config;

    public McProtect(ProtectedRegions regions, ConfigHolder config) {
        this.regions = regions;
        this.config = config;
    }

    @Override
    public ToolSpec spec() {
        return spec;
    }

    record AddArgs(String name, String world, Region box, ProtectedRegion.Mode mode, String note) {
        static AddArgs parse(JsonObject o) {
            String name = ArgParse.requireString(o, "name");
            if (!ProtectedRegion.NAME.matcher(name).matches()) {
                throw new ToolArgError("name: 1-64 lowercase letters, digits, '-' or '_', starting with a letter or digit");
            }
            int[] from = BuildTransform.strictCoords(o, "from");
            int[] to = BuildTransform.strictCoords(o, "to");
            Region box = Region.of(from, to);
            BuildPreflight.checkHorizontal(box);
            String note = ArgParse.optString(o, "note");
            if (note != null && (note.isBlank() || note.length() > ProtectedRegion.MAX_NOTE)) {
                throw new ToolArgError("note: must be 1-" + ProtectedRegion.MAX_NOTE + " characters");
            }
            ProtectedRegion.Mode mode = ProtectedRegion.Mode.parse(ArgParse.optEnum(o, "mode", List.of("deny", "warn"), "deny"));
            return new AddArgs(name, ArgParse.optString(o, "world"), box, mode, note == null ? null : note.trim());
        }
    }

    static Region parseTouches(JsonObject o) {
        if (!ArgParse.has(o, "touches")) return null;
        JsonObject t = ArgParse.requireObject(o.get("touches"), "touches");
        return Region.of(BuildTransform.strictCoords(t, "from"), BuildTransform.strictCoords(t, "to"));
    }

    static void allowedKeys(JsonObject o, Set<String> allowed, String action) {
        for (String k : o.keySet()) {
            if (!allowed.contains(k)) throw new ToolArgError(k + " does not apply to action \"" + action + "\"");
        }
    }

    @Override
    public CompletableFuture<ToolResult> call(InvocationContext ctx, JsonObject args) {
        return ToolRunner.runText("mc_protect", () -> {
            String action = ArgParse.requireEnum(args, "action", ACTIONS);
            return switch (action) {
                case "add" -> {
                    allowedKeys(args, Set.of("action", "name", "world", "from", "to", "note", "mode"), action);
                    yield CompletableFuture.completedFuture(add(ctx, AddArgs.parse(args)));
                }
                case "list" -> {
                    allowedKeys(args, Set.of("action", "world", "touches"), action);
                    yield CompletableFuture.completedFuture(list(ArgParse.optString(args, "world"), parseTouches(args)));
                }
                default -> {
                    allowedKeys(args, Set.of("action", "name"), action);
                    yield remove(ctx, ArgParse.requireString(args, "name"));
                }
            };
        });
    }

    private String add(InvocationContext ctx, AddArgs a) {
        // Resolves the default world and applies the world allow-list; a plain registry lookup, safe off the main thread.
        JsonObject w = new JsonObject();
        if (a.world() != null) w.addProperty("world", a.world());
        String world = new RequestValidator(config.get()).resolveWorld(w).getName();
        ProtectedRegion r;
        try {
            r = regions.add(a.name(), world, a.box(), a.mode(), a.note(), VerificationService.owner(ctx));
        } catch (IllegalArgumentException e) {
            throw new ToolArgError(e.getMessage());
        } catch (IOException e) {
            throw new ToolArgError("protected regions could not be saved: " + e.getClass().getSimpleName());
        }
        return "Protected region '" + r.name() + "' (" + r.mode().id() + ") added: " + r.world() + " " + ProtectionGuard.span(r.box())
                + ", " + r.box().volume() + " cells. mc_build, mc_repair and mc_restore " + (r.mode() == ProtectedRegion.Mode.DENY
                ? "now refuse to write there unless a call passes override:[\"" + r.name() + "\"]."
                : "now warn when they write there.") + " mc_command is not guarded.";
    }

    String list(String world, Region touches) {
        String filterWorld = world != null ? world : touches != null ? config.get().world().defaultWorld() : null;
        List<String> rows = new ArrayList<>();
        for (ProtectedRegion r : regions.all()) {
            if (filterWorld != null && !r.world().equals(filterWorld)) continue;
            if (touches != null && !r.intersects(touches)) continue;
            rows.add(r.name() + "  " + r.mode().id() + "  " + r.world() + " " + ProtectionGuard.span(r.box()) + "  " + r.box().volume()
                    + " cells  by " + r.owner() + "  " + r.createdAt() + (r.note() != null ? "  \"" + r.note() + "\"" : ""));
        }
        if (rows.isEmpty()) return filterWorld != null || touches != null ? "No protected regions match." : "No protected regions.";
        rows.addFirst(rows.size() + " protected region" + (rows.size() == 1 ? "" : "s") + ":");
        return String.join("\n", rows);
    }

    private CompletableFuture<String> remove(InvocationContext ctx, String name) {
        ProtectedRegion existing = regions.get(name);
        if (existing == null) throw new ToolArgError("protected region '" + name + "' not found");
        String owner = VerificationService.owner(ctx);
        return admin(ctx, owner, existing).thenApply(admin -> {
            try {
                ProtectedRegion r = regions.remove(name, owner, admin);
                return "Removed protected region '" + r.name() + "' (" + r.world() + " " + ProtectionGuard.span(r.box()) + ").";
            } catch (IllegalArgumentException e) {
                throw new ToolArgError(e.getMessage());
            } catch (IOException e) {
                throw new ToolArgError("protected regions could not be saved: " + e.getClass().getSimpleName());
            }
        });
    }

    /** MCP token, console and system callers may remove any region; a player needs ashlar.admin (ops have it by default). */
    private static CompletableFuture<Boolean> admin(InvocationContext ctx, String owner, ProtectedRegion r) {
        if (ctx.principal().kind() != InvocationContext.Kind.PLAYER) return CompletableFuture.completedFuture(true);
        if (r.owner().equals(owner)) return CompletableFuture.completedFuture(false);
        UUID uuid;
        try {
            uuid = UUID.fromString(ctx.principal().id());
        } catch (IllegalArgumentException e) {
            return CompletableFuture.completedFuture(false);
        }
        return MainThread.call(() -> {
            var player = Bukkit.getPlayer(uuid);
            return player != null && (player.isOp() || player.hasPermission("ashlar.admin"));
        });
    }
}
