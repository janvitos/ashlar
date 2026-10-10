// SPDX-License-Identifier: AGPL-3.0-or-later
package cc.wujm.ashlar.engine;

/**
 * Pure placement rules behind {@code mc_diff} anomalies {@code floating} and {@code stacked}.
 * {@link DiffScanTask} classifies each palette entry once from its BlockData on the main thread;
 * these rules then only look at the classification of a cell and of the cell below it. They follow
 * vanilla survival checks closely: snow needs a full sturdy top (or a full snow block, honey or
 * soul sand) and never survives on ice or barriers, carpets only need a non-air block, pressure
 * plates need rigid or center support, rails rigid support, standing torches and floor lanterns
 * center support. Plants use the simpler "solid block below" rule that {@link SupportCheck} uses.
 */
public final class AnomalyRules {

    private AnomalyRules() {
    }

    /** Support-sensitive placement families; NONE for everything else. */
    public enum Kind { NONE, SNOW, CARPET, PLATE, RAIL, TORCH, LANTERN, PLANT }

    /** What the block below offers on its top face. {@code snowLayers} is 1-8 for a snow layer, else 0. */
    public record Below(boolean air, boolean solid, boolean full, boolean rigid, boolean center,
            int snowLayers, boolean snowAlways, boolean snowNever) {
        public static final Below AIR = new Below(true, false, false, false, false, 0, false, false);
    }

    public enum Result { OK, FLOATING, STACKED }

    public static Result evaluate(Kind kind, Below below) {
        return switch (kind) {
            case NONE -> Result.OK;
            case SNOW -> snow(below);
            case CARPET -> below.air() ? Result.FLOATING : Result.OK;
            case PLATE -> below.rigid() || below.center() ? Result.OK : Result.FLOATING;
            case RAIL -> below.rigid() ? Result.OK : Result.FLOATING;
            case TORCH, LANTERN -> below.center() ? Result.OK : Result.FLOATING;
            case PLANT -> below.solid() ? Result.OK : Result.FLOATING;
        };
    }

    private static Result snow(Below below) {
        if (below.snowNever()) return Result.FLOATING;
        if (below.snowAlways()) return Result.OK;
        if (below.snowLayers() > 0) return below.snowLayers() >= 8 ? Result.OK : Result.STACKED;
        return below.full() ? Result.OK : Result.FLOATING;
    }

    /** Short human reason for a non-OK result. */
    public static String reason(Kind kind, Result result, Below below) {
        if (result == Result.STACKED) return "snow layer on a partial snow layer (layers=" + below.snowLayers() + ")";
        if (below.air()) return "nothing below";
        return switch (kind) {
            case SNOW -> below.snowNever() ? "snow cannot rest on ice or barrier" : "block below has no full top face";
            case PLATE, RAIL -> "block below has no rigid top face";
            case TORCH, LANTERN -> "block below has no center support";
            default -> "nothing solid below";
        };
    }
}
