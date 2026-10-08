// SPDX-License-Identifier: AGPL-3.0-or-later
package cc.wujm.ashlar.tool.mc;

import cc.wujm.ashlar.engine.text.TextExpand;
import cc.wujm.ashlar.tool.ArgParse;
import cc.wujm.ashlar.tool.ToolArgError;
import com.google.gson.JsonObject;

import java.util.List;

/** Pure coordinate transform: mirror in local space, rotate clockwise, then translate. */
record BuildTransform(int originX, int originY, int originZ, int rotation, String mirror) {
    static final BuildTransform IDENTITY = new BuildTransform(0, 0, 0, 0, "none");

    static BuildTransform parse(JsonObject args) {
        if (!ArgParse.has(args, "transform")) {
            return IDENTITY;
        }
        JsonObject t = ArgParse.requireObject(args.get("transform"), "transform");
        for (String key : t.keySet()) {
            if (!List.of("origin", "rotation", "mirror").contains(key)) {
                throw new ToolArgError("transform: unknown field '" + key + "'");
            }
        }
        int[] origin = strictCoords(t, "origin");
        int rotation = ArgParse.has(t, "rotation") ? strictInt(t.get("rotation"), "transform.rotation") : 0;
        if (!List.of(0, 90, 180, 270).contains(rotation)) {
            throw new ToolArgError("transform.rotation: must be 0, 90, 180 or 270");
        }
        String mirror = ArgParse.optEnum(t, "mirror", List.of("none", "x", "z"), "none");
        return new BuildTransform(origin[0], origin[1], origin[2], rotation, mirror);
    }

    /** Reject fractional and overflowing values instead of silently truncating them. */
    static int strictInt(com.google.gson.JsonElement e, String field) {
        try {
            if (e == null || !e.isJsonPrimitive() || !e.getAsJsonPrimitive().isNumber()) {
                throw new ArithmeticException();
            }
            return e.getAsBigDecimal().intValueExact();
        } catch (ArithmeticException | NumberFormatException ex) {
            throw new ToolArgError(field + ": must be a signed 32-bit integer");
        }
    }

    static int[] strictCoords(JsonObject o, String field) {
        var arr = ArgParse.requireArray(o, field);
        if (arr.size() != 3) {
            throw new ToolArgError(field + ": must have exactly 3 integers");
        }
        return new int[] {strictInt(arr.get(0), field), strictInt(arr.get(1), field), strictInt(arr.get(2), field)};
    }

    boolean changesOrientation() {
        return rotation != 0 || !mirror.equals("none");
    }

    int[] position(int[] p) {
        return position(p[0], p[1], p[2]);
    }

    private int[] position(long px, long py, long pz) {
        long x = mirror.equals("x") ? -px : px;
        long z = mirror.equals("z") ? -pz : pz;
        long rx = switch (rotation) { case 90 -> -z; case 180 -> -x; case 270 -> z; default -> x; };
        long rz = switch (rotation) { case 90 -> x; case 180 -> -z; case 270 -> -x; default -> z; };
        try {
            return new int[] {Math.toIntExact(rx + originX), Math.toIntExact(py + originY),
                    Math.toIntExact(rz + originZ)};
        } catch (ArithmeticException ex) {
            throw new ToolArgError("transform: resulting coordinate exceeds signed 32-bit range");
        }
    }

    String direction(String direction) {
        int[] v = switch (direction) {
            case "north" -> new int[] {0, 0, -1};
            case "east" -> new int[] {1, 0, 0};
            case "south" -> new int[] {0, 0, 1};
            case "west" -> new int[] {-1, 0, 0};
            default -> null;
        };
        if (v == null) return direction;
        int[] p = new BuildTransform(0, 0, 0, rotation, mirror).position(v);
        if (p[0] > 0) return "east";
        if (p[0] < 0) return "west";
        return p[2] > 0 ? "south" : "north";
    }

    int[][] bounds(int[] from, int[] to) {
        return normalized(position(from), position(to));
    }

    private static int[][] normalized(int[] a, int[] b) {
        int[] min = new int[3], max = new int[3];
        for (int i = 0; i < 3; i++) {
            min[i] = Math.min(a[i], b[i]);
            max[i] = Math.max(a[i], b[i]);
        }
        return new int[][] {min, max};
    }

    /** Transform expanded glyph geometry, including floor text; mirrors intentionally mirror glyphs. */
    TextExpand.Result text(TextExpand.Result result) {
        return text(result, new int[] {0, 0, 0});
    }

    TextExpand.Result text(TextExpand.Result result, int[] anchor) {
        List<TextExpand.Run> runs = result.inkRuns().stream().map(run -> {
            int[][] b = textBounds(run.from(), run.to(), anchor);
            return new TextExpand.Run(b[0][0], b[0][1], b[0][2], b[1][0], b[1][1], b[1][2]);
        }).toList();
        int[][] box = textBounds(result.bboxMin(), result.bboxMax(), anchor);
        return new TextExpand.Result(runs, box[0], box[1], result.widthBlocks(), result.heightBlocks(),
                result.inkBlockCount());
    }

    private int[][] textBounds(int[] a, int[] b, int[] anchor) {
        return normalized(position((long) a[0] + anchor[0], (long) a[1] + anchor[1], (long) a[2] + anchor[2]),
                position((long) b[0] + anchor[0], (long) b[1] + anchor[1], (long) b[2] + anchor[2]));
    }
}
