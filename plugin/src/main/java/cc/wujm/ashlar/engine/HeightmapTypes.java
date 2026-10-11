// SPDX-License-Identifier: AGPL-3.0-or-later
package cc.wujm.ashlar.engine;

import cc.wujm.ashlar.rpc.ErrorCode;
import cc.wujm.ashlar.rpc.RpcError;
import org.bukkit.HeightMap;

import java.util.Locale;

/**
 * Spec &sect;3.2 heightmap type name &rarr; Bukkit {@link HeightMap}, shared by
 * {@code HeightmapHandler} (the {@code heightmap} RPC) and {@code
 * RenderHandler}'s {@code view: "heightmap"} path. Also accepts the Bukkit
 * enum names directly, but never the deprecated/removed {@code
 * com.destroystokyo.paper.HeightmapType} and never the internal {@code *_WG}
 * variants (worldgen heightmaps, not meant for post-generation queries).
 */
public final class HeightmapTypes {

    /** Spec &sect;3.2 default, used by both the {@code heightmap} RPC and the heightmap render view. */
    public static final String DEFAULT_TYPE = "SOLID_OR_LIQUID_NO_LEAVES";

    private HeightmapTypes() {
    }

    /** Step 18: the default surface, then down through decoration (see {@link SurfaceRules#skip}). */
    public static final String IGNORE_DECOR = "TOP_SOLID_IGNORE_DECOR";

    public static boolean ignoresDecor(String raw) {
        return raw != null && raw.trim().toUpperCase(Locale.ROOT).equals(IGNORE_DECOR);
    }

    public static HeightMap resolve(String raw) {
        String key = raw.trim().toUpperCase(Locale.ROOT);
        return switch (key) {
            case IGNORE_DECOR -> HeightMap.MOTION_BLOCKING_NO_LEAVES;
            case "SOLID", "OCEAN_FLOOR" -> HeightMap.OCEAN_FLOOR;
            case "SOLID_OR_LIQUID", "MOTION_BLOCKING" -> HeightMap.MOTION_BLOCKING;
            case "SOLID_OR_LIQUID_NO_LEAVES", "MOTION_BLOCKING_NO_LEAVES" -> HeightMap.MOTION_BLOCKING_NO_LEAVES;
            case "ANY", "WORLD_SURFACE" -> HeightMap.WORLD_SURFACE;
            default -> throw new RpcError(ErrorCode.BAD_REQUEST, "unknown heightmap type: '" + raw + "'");
        };
    }
}
