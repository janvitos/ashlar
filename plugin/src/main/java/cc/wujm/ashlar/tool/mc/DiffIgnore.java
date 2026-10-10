// SPDX-License-Identifier: AGPL-3.0-or-later
package cc.wujm.ashlar.tool.mc;

import cc.wujm.ashlar.engine.StateCanon;
import cc.wujm.ashlar.tool.ToolArgError;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.function.Predicate;
import java.util.regex.Pattern;

/**
 * {@code mc_diff ignore} globs: {@code material-glob} or {@code material-glob[k=v,...]}, where
 * {@code *} matches any run of id characters in the material and in values. Without brackets (or with
 * {@code [*]}) any properties match; with {@code [k=v]} the full state must carry every listed pair.
 * Matching runs on full {@code getAsString()} states, so default-valued properties can be named.
 */
final class DiffIgnore implements Predicate<String> {

    static final int MAX_GLOBS = 32;
    private static final Pattern SYNTAX = Pattern.compile(
            "[a-z0-9_*.:-]+(\\[(\\*|[a-z0-9_]+=[a-z0-9_*]+(,[a-z0-9_]+=[a-z0-9_*]+)*)\\])?");

    private record Glob(Pattern material, Map<String, Pattern> properties) {
    }

    static final DiffIgnore NONE = new DiffIgnore(List.of());

    private final List<Glob> globs;

    private DiffIgnore(List<Glob> globs) {
        this.globs = globs;
    }

    static DiffIgnore parse(List<String> raw) {
        if (raw.size() > MAX_GLOBS) throw new ToolArgError("ignore accepts at most " + MAX_GLOBS + " globs");
        List<Glob> globs = new ArrayList<>();
        for (String g : raw) {
            String s = g.trim().toLowerCase(java.util.Locale.ROOT).replace(" ", "");
            if (!SYNTAX.matcher(s).matches()) {
                throw new ToolArgError("invalid ignore glob '" + g + "'; use e.g. \"*_leaves\", \"snow[layers=1]\" or \"oak_*[*]\"");
            }
            int open = s.indexOf('[');
            String material = open < 0 ? s : s.substring(0, open);
            if (material.startsWith("minecraft:")) material = material.substring("minecraft:".length());
            Map<String, Pattern> props = new TreeMap<>();
            if (open >= 0 && !s.substring(open).equals("[*]")) {
                for (String kv : s.substring(open + 1, s.length() - 1).split(",")) {
                    int eq = kv.indexOf('=');
                    props.put(kv.substring(0, eq), glob(kv.substring(eq + 1)));
                }
            }
            globs.add(new Glob(glob(material), props));
        }
        return new DiffIgnore(globs);
    }

    private static Pattern glob(String text) {
        StringBuilder re = new StringBuilder();
        for (String part : text.split("\\*", -1)) {
            if (!re.isEmpty()) re.append("[a-z0-9_.:-]*");
            re.append(Pattern.quote(part));
        }
        return Pattern.compile(re.toString());
    }

    boolean isEmpty() {
        return globs.isEmpty();
    }

    @Override
    public boolean test(String state) {
        if (globs.isEmpty()) return false;
        StateCanon.Parsed p;
        try {
            p = StateCanon.parse(state);
        } catch (IllegalArgumentException e) {
            return false;
        }
        for (Glob g : globs) {
            if (!g.material().matcher(p.material()).matches()) continue;
            boolean all = true;
            for (var e : g.properties().entrySet()) {
                String v = p.properties().get(e.getKey());
                if (v == null || !e.getValue().matcher(v).matches()) { all = false; break; }
            }
            if (all) return true;
        }
        return false;
    }
}
