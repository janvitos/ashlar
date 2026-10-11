// SPDX-License-Identifier: AGPL-3.0-or-later
package cc.wujm.ashlar.engine;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Says why a block-state string is invalid, in one short line an agent can act on: a syntax error,
 * an unknown block (with close names), an unknown property (with the block's properties) or an
 * invalid value (with the values the block accepts). Pure Java; the registry is reached through
 * {@link Probe}. Runs only on the error path; allowed values are found by probing candidate values
 * one at a time and cached per block and property.
 */
public final class StateDiagnosis {

    /** Registry access: defaults and single-state validity are pure lookups, safe off the main thread. */
    public interface Probe {
        /** Full default property map without namespace; {@code null} for an unknown block. */
        Map<String, String> defaults(String material);

        /** Whether {@code state} ({@code minecraft:name[k=v,...]}) parses. */
        boolean valid(String state);

        /** Every block name without namespace, for suggestions. */
        Collection<String> blocks();
    }

    static final int SUGGESTIONS = 3;
    /**
     * Every non-numeric value vanilla block properties use. Numbers 0-25 are added separately.
     * A missing token would only shorten an allowed-values list; {@link #allowed} also tries each property's own default.
     */
    static final List<String> VOCABULARY = List.of(
            "true", "false", "north", "east", "south", "west", "up", "down", "x", "y", "z",
            "top", "bottom", "double", "upper", "lower", "left", "right", "single", "center", "head", "foot",
            "straight", "inner_left", "inner_right", "outer_left", "outer_right",
            "north_south", "east_west", "ascending_east", "ascending_west", "ascending_north", "ascending_south",
            "south_east", "south_west", "north_west", "north_east",
            "none", "low", "tall", "side", "floor", "wall", "ceiling", "single_wall", "double_wall",
            "compare", "subtract", "save", "load", "corner", "data", "normal", "sticky",
            "down_east", "down_north", "down_south", "down_west", "up_east", "up_north", "up_south", "up_west",
            "west_up", "east_up", "north_up", "south_up",
            "harp", "basedrum", "snare", "hat", "bass", "flute", "bell", "guitar", "chime", "xylophone", "iron_xylophone",
            "cow_bell", "didgeridoo", "bit", "banjo", "pling", "trumpet", "trumpet_exposed", "trumpet_weathered",
            "trumpet_oxidized", "zombie", "skeleton", "creeper", "dragon", "wither_skeleton", "piglin", "custom_head",
            "tip_merge", "tip", "frustum", "middle", "base", "unstable", "partial", "full",
            "inactive", "active", "cooldown", "small", "large", "waiting_for_players", "waiting_for_reward_ejection",
            "ejecting_reward", "unlocking", "ejecting", "uprooted", "dormant", "awake",
            "start", "log", "fail", "accept", "standing", "sitting", "running", "star", "unconnected",
            "continuous", "erupting", "dry", "wet");

    private final Probe probe;
    private final Map<String, List<String>> values = new ConcurrentHashMap<>();

    public StateDiagnosis(Probe probe) {
        this.probe = probe;
    }

    /** Why {@code state} is invalid; a generic reason when every part is valid on its own. */
    public String diagnose(String state) {
        StateCanon.Parsed p;
        try {
            p = StateCanon.parse(state);
        } catch (IllegalArgumentException e) {
            // Callers already name the state; keep only the reason.
            String m = e.getMessage(), prefix = "invalid block state '" + state + "'";
            return !m.startsWith(prefix) ? m : m.length() > prefix.length() ? m.substring(prefix.length() + 2) : "malformed block name";
        }
        Map<String, String> defaults = probe.defaults(p.material());
        if (defaults == null) {
            List<String> close = suggest(p.material(), probe.blocks());
            return "unknown block '" + p.material() + "'" + (close.isEmpty() ? "" : "; did you mean " + String.join(", ", close) + "?");
        }
        for (var e : p.properties().entrySet()) {
            if (!defaults.containsKey(e.getKey())) {
                return defaults.isEmpty() ? p.material() + " has no properties; use plain " + p.material()
                        : p.material() + " has no property '" + e.getKey() + "'; properties: " + String.join(", ", defaults.keySet());
            }
        }
        for (var e : p.properties().entrySet()) {
            List<String> allowed = allowed(p.material(), e.getKey(), defaults.get(e.getKey()));
            if (!allowed.contains(e.getValue())) {
                return p.material() + " " + e.getKey() + "=" + e.getValue() + " is invalid; " + e.getKey() + ": " + String.join("|", allowed);
            }
        }
        return "this combination of properties is not a valid state of " + p.material();
    }

    /** Values of {@code property} that {@code material} accepts on their own, default first. */
    List<String> allowed(String material, String property, String def) {
        return values.computeIfAbsent(material + "\u0000" + property, k -> {
            Set<String> candidates = new LinkedHashSet<>();
            candidates.add(def);
            candidates.addAll(VOCABULARY);
            for (int i = 0; i <= 25; i++) candidates.add(Integer.toString(i));
            List<String> out = new ArrayList<>();
            for (String v : candidates) if (probe.valid("minecraft:" + material + "[" + property + "=" + v + "]")) out.add(v);
            return List.copyOf(out);
        });
    }

    /** Up to {@link #SUGGESTIONS} names that contain {@code name}'s words or are a small edit away, closest first. */
    static List<String> suggest(String name, Collection<String> names) {
        record Scored(String name, int score) {}
        List<Scored> scored = new ArrayList<>();
        for (String n : names) {
            int d = distance(name, n, 4);
            if (d <= Math.max(2, name.length() / 4)) scored.add(new Scored(n, d));
            else if (n.contains(name) || name.contains(n) && n.length() > 3) scored.add(new Scored(n, 10 + Math.abs(n.length() - name.length())));
        }
        return scored.stream().sorted(Comparator.comparingInt(Scored::score).thenComparing(Scored::name))
                .limit(SUGGESTIONS).map(Scored::name).toList();
    }

    /** Levenshtein distance, or {@code limit + 1} once it is certainly larger than {@code limit}. */
    static int distance(String a, String b, int limit) {
        if (Math.abs(a.length() - b.length()) > limit) return limit + 1;
        int[] prev = new int[b.length() + 1], cur = new int[b.length() + 1];
        for (int j = 0; j <= b.length(); j++) prev[j] = j;
        for (int i = 1; i <= a.length(); i++) {
            cur[0] = i;
            int best = cur[0];
            for (int j = 1; j <= b.length(); j++) {
                cur[j] = Math.min(Math.min(cur[j - 1] + 1, prev[j] + 1), prev[j - 1] + (a.charAt(i - 1) == b.charAt(j - 1) ? 0 : 1));
                best = Math.min(best, cur[j]);
            }
            if (best > limit) return limit + 1;
            int[] t = prev; prev = cur; cur = t;
        }
        return prev[b.length()];
    }
}
