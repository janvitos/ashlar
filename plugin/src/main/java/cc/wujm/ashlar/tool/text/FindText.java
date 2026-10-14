// SPDX-License-Identifier: AGPL-3.0-or-later
package cc.wujm.ashlar.tool.text;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * Renders {@code mc_inspect}'s {@code find}: the positions of every block whose id (without the
 * minecraft: prefix and properties) matches a pattern, grouped by state, so lamps, leftovers or
 * markers can be located without slicing layer by layer. Positions are listed in y, z, x order up
 * to {@link #MAX_LISTED} in all. No Bukkit dependency.
 */
public final class FindText {

    /** Positions listed across all states; the counts stay exact beyond it. */
    public static final int MAX_LISTED = 500;

    private FindText() {
    }

    private static String id(String state) {
        String s = state.startsWith("minecraft:") ? state.substring("minecraft:".length()) : state;
        int b = s.indexOf('[');
        return b < 0 ? s : s.substring(0, b);
    }

    public static String renderFind(BlockGrid decoded, Pattern pattern, String world) {
        int fx = decoded.bounds().from()[0], fy = decoded.bounds().from()[1], fz = decoded.bounds().from()[2];
        int tx = decoded.bounds().to()[0], ty = decoded.bounds().to()[1], tz = decoded.bounds().to()[2];
        Map<String, Boolean> matches = new LinkedHashMap<>();
        Map<String, List<String>> positions = new LinkedHashMap<>();
        Map<String, Long> counts = new LinkedHashMap<>();
        long total = 0;
        int listed = 0;
        for (int y = fy; y <= ty; y++) {
            for (int z = fz; z <= tz; z++) {
                for (int x = fx; x <= tx; x++) {
                    String state = decoded.at(x, y, z);
                    if (!matches.computeIfAbsent(state, s -> pattern.matcher(id(s)).find())) continue;
                    total++;
                    counts.merge(state, 1L, Long::sum);
                    List<String> list = positions.computeIfAbsent(state, k -> new ArrayList<>());
                    if (listed < MAX_LISTED) {
                        list.add(x + "," + y + "," + z);
                        listed++;
                    }
                }
            }
        }
        List<String> lines = new ArrayList<>();
        lines.add("Find /" + pattern.pattern() + "/ in " + world + " x=[" + fx + ".." + tx + "] y=[" + fy + ".." + ty + "] z=[" + fz + ".." + tz
                + "]: " + total + " block" + (total == 1 ? "" : "s") + " in " + counts.size() + " state" + (counts.size() == 1 ? "" : "s") + ".");
        for (Map.Entry<String, Long> e : counts.entrySet()) {
            List<String> list = positions.get(e.getKey());
            String state = e.getKey().startsWith("minecraft:") ? e.getKey().substring("minecraft:".length()) : e.getKey();
            List<String> parts = new ArrayList<>(list);
            if (list.size() < e.getValue()) parts.add("+" + (e.getValue() - list.size()) + " more");
            lines.add("  " + state + " (" + e.getValue() + "): " + String.join(" ", parts));
        }
        if (total > listed) lines.add("Only the first " + MAX_LISTED + " positions are listed; narrow the box or the pattern for the rest.");
        return String.join("\n", lines);
    }
}
