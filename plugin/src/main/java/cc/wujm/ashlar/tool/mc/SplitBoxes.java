// SPDX-License-Identifier: AGPL-3.0-or-later
package cc.wujm.ashlar.tool.mc;

import java.util.ArrayList;
import java.util.List;

/**
 * Splits an over-limit box into boxes that each fit a read limit, for the over-limit error text:
 * naming the exact corners saves the agent working them out (and getting an edge wrong). Slabs
 * along the longest axis when its cross-section fits; otherwise the box is halved along its
 * longest axis until every piece fits.
 */
final class SplitBoxes {

    /** Boxes listed in full; the rest are counted. */
    static final int LISTED = 4;

    /** Over this many times the limit, splitting is not a sensible suggestion. */
    static final int MAX_SPLIT = 64;

    private SplitBoxes() {
    }

    /** Inclusive {@code lo}/{@code hi} corners of equal length (2 for [x, z], 3 for [x, y, z]), already ordered. */
    static List<int[][]> split(int[] lo, int[] hi, long max) {
        List<int[][]> out = new ArrayList<>();
        split(lo.clone(), hi.clone(), max, out);
        return out;
    }

    private static void split(int[] lo, int[] hi, long max, List<int[][]> out) {
        long volume = 1;
        int axis = 0;
        for (int i = 0; i < lo.length; i++) {
            volume *= hi[i] - lo[i] + 1L;
            if (hi[i] - lo[i] > hi[axis] - lo[axis]) axis = i;
        }
        if (volume <= max) {
            out.add(new int[][] {lo, hi});
            return;
        }
        long len = hi[axis] - lo[axis] + 1L;
        long cross = volume / len;
        if (cross <= max) {
            long step = max / cross;
            for (long start = lo[axis]; start <= hi[axis]; start += step) {
                int[] a = lo.clone(), b = hi.clone();
                a[axis] = (int) start;
                b[axis] = (int) Math.min(hi[axis], start + step - 1);
                out.add(new int[][] {a, b});
            }
            return;
        }
        int mid = (int) (lo[axis] + len / 2 - 1);
        int[] b = hi.clone();
        b[axis] = mid;
        int[] a = lo.clone();
        a[axis] = mid + 1;
        split(lo, b, max, out);
        split(a, hi, max, out);
    }

    /** " Split it into N calls: [..]..[..], ..." for an error message. */
    static String hint(int[] lo, int[] hi, long max, String unit) {
        double volume = 1;
        for (int i = 0; i < lo.length; i++) volume *= hi[i] - lo[i] + 1.0;
        if (volume > MAX_SPLIT * (double) max) {
            return String.format(java.util.Locale.ROOT, "Reduce the from/to range: it is about %.0f times the limit.", Math.ceil(volume / max));
        }
        List<int[][]> boxes = split(lo, hi, max);
        StringBuilder sb = new StringBuilder("Reduce the from/to range, or split it into ").append(boxes.size()).append(' ').append(unit).append(": ");
        for (int i = 0; i < Math.min(LISTED, boxes.size()); i++) {
            if (i > 0) sb.append(", ");
            sb.append(corner(boxes.get(i)[0])).append("..").append(corner(boxes.get(i)[1]));
        }
        if (boxes.size() > LISTED) sb.append(" and ").append(boxes.size() - LISTED).append(" more continuing the same way");
        return sb.append('.').toString();
    }

    private static String corner(int[] c) {
        StringBuilder sb = new StringBuilder("[");
        for (int i = 0; i < c.length; i++) sb.append(i > 0 ? "," : "").append(c[i]);
        return sb.append(']').toString();
    }
}
