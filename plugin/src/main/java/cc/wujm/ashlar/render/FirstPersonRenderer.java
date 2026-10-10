// SPDX-License-Identifier: AGPL-3.0-or-later
package cc.wujm.ashlar.render;

import com.google.gson.JsonObject;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.function.IntUnaryOperator;

/**
 * Pure off-main first-person renderer: one {@link VoxelRay} per pixel from a {@link FirstPersonCamera},
 * shaded like the angled schematic views, with light distance haze. Cells in chunks that were not
 * loaded are drawn in a flat "unknown" color and counted, never treated as air.
 */
public final class FirstPersonRenderer {
    private FirstPersonRenderer() {}

    public static final int UNKNOWN_COLOR = 0xFF6B4E71;
    private static final int MAX_LAYERS = 8;

    public record Output(ImageRenderer.Output image, JsonObject details) {}

    /** {@code colors} maps a block id to its ARGB map color (0 = none); called off-main, so it must be pure. */
    public static Output render(VoxelRay.Voxels voxels, IntUnaryOperator colors, FirstPersonCamera cam) {
        int w = cam.width(), h = cam.height();
        int[] pixels = new int[w * h];
        double[][] basis = cam.basis();
        long unknownPixels = 0, skyPixels = 0, steps = 0;
        java.util.Map<Integer, long[]> visible = new java.util.HashMap<>();
        Trace tr = new Trace(voxels, colors, cam.distance());
        for (int py = 0; py < h; py++) {
            for (int px = 0; px < w; px++) {
                double[] d = cam.ray(basis, px, py);
                int sky = sky(d[1]);
                tr.reset();
                steps += VoxelRay.trace(voxels, cam.x(), cam.y(), cam.z(), d[0], d[1], d[2], cam.distance(), tr);
                if (tr.firstId >= 0) visible.computeIfAbsent(tr.firstId, k -> new long[1])[0]++;
                int bg = tr.unknown ? UNKNOWN_COLOR : sky;
                if (tr.unknown && tr.layers == 0) unknownPixels++;
                if (!tr.unknown && tr.layers == 0) skyPixels++;
                double haze = tr.layers == 0 ? 0 : Math.min(.65, Math.pow(tr.firstT / cam.distance(), 2) * .65);
                double r = tr.r + ((bg >> 16) & 255) * tr.remaining, g = tr.g + ((bg >> 8) & 255) * tr.remaining, b = tr.b + (bg & 255) * tr.remaining;
                r = r * (1 - haze) + ((sky >> 16) & 255) * haze;
                g = g * (1 - haze) + ((sky >> 8) & 255) * haze;
                b = b * (1 - haze) + (sky & 255) * haze;
                pixels[py * w + px] = 0xFF000000 | (clamp(r) << 16) | (clamp(g) << 8) | clamp(b);
            }
        }
        List<ImageRenderer.LegendEntry> legend = new ArrayList<>();
        for (var e : visible.entrySet()) {
            int c = colors.applyAsInt(e.getKey());
            legend.add(new ImageRenderer.LegendEntry(voxels.state(e.getKey()), String.format(Locale.ROOT, "#%06X", (c == 0 ? fallback(voxels.state(e.getKey())) : c) & 0xFFFFFF), e.getValue()[0]));
        }
        legend.sort(Comparator.comparingLong(ImageRenderer.LegendEntry::pixels).reversed());
        JsonObject info = new JsonObject();
        info.addProperty("projection", "first-person");
        info.addProperty("pixels", (long) w * h);
        info.addProperty("skyPixels", skyPixels);
        info.addProperty("unknownPixels", unknownPixels);
        info.addProperty("cellsTraversed", steps);
        info.addProperty("assumptions", "Schematic state-derived visual cuboids and map colors from the eye, not textures, lighting, entities or fog. Unloaded chunks are drawn in "
                + String.format(Locale.ROOT, "#%06X", UNKNOWN_COLOR & 0xFFFFFF) + " and counted as unknownPixels, never as air. Beyond distance is sky.");
        return new Output(new ImageRenderer.Output(w, h, 1, "camera screen-right", "camera screen-down", 0, 0, 0, List.copyOf(legend), pixels), info);
    }

    private static final class Trace implements VoxelRay.Visitor {
        final VoxelRay.Voxels voxels;
        final IntUnaryOperator colors;
        final double distance;
        double r, g, b, remaining, firstT;
        int layers, firstId;
        boolean unknown;

        Trace(VoxelRay.Voxels voxels, IntUnaryOperator colors, double distance) {
            this.voxels = voxels;
            this.colors = colors;
            this.distance = distance;
        }

        void reset() {
            r = g = b = 0;
            remaining = 1;
            layers = 0;
            firstId = -1;
            firstT = 0;
            unknown = false;
        }

        @Override
        public boolean surface(int x, int y, int z, int id, double t, int axis, int sign) {
            BlockShapes.Shape shape = voxels.shape(id);
            int c = colors.applyAsInt(id);
            if (c == 0) c = fallback(voxels.state(id));
            if (layers == 0) {
                firstId = id;
                firstT = t;
            }
            // Same directional light as the angled views: tops bright, east-west medium, north-south darker.
            double light = .58 + .42 * Math.max(0, sign * (axis == 0 ? .4 : axis == 1 ? .85 : -.3));
            if (axis == 1 && sign < 0) light = .5;
            double a = shape.opacity() * remaining;
            r += ((c >> 16) & 255) * light * a;
            g += ((c >> 8) & 255) * light * a;
            b += (c & 255) * light * a;
            remaining *= 1 - shape.opacity();
            layers++;
            return remaining >= .02 && layers < MAX_LAYERS;
        }

        @Override
        public void unknown(int x, int y, int z, double t) {
            unknown = true;
            if (layers == 0) firstT = t;
        }
    }

    static int fallback(String state) {
        return BlockShapes.material(state).contains("glass") ? 0xFFB4DBE8 : 0xFFA2A2A2;
    }

    /** Sky gradient by ray elevation: pale at the horizon, deeper blue overhead, grey-brown below. */
    static int sky(double dy) {
        if (dy < 0) return 0xFF8C8478;
        double t = Math.min(1, dy * 1.4);
        int r = (int) (186 - 76 * t), g = (int) (214 - 64 * t), bl = (int) (240 - 15 * t);
        return 0xFF000000 | (r << 16) | (g << 8) | bl;
    }

    private static int clamp(double v) {
        return (int) Math.max(0, Math.min(255, Math.round(v)));
    }
}
