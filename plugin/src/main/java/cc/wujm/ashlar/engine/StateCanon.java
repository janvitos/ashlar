// SPDX-License-Identifier: AGPL-3.0-or-later
package cc.wujm.ashlar.engine;

import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Canonical block-state strings: no {@code minecraft:} prefix, no property whose value equals the
 * material default, remaining keys sorted. {@code light[level=11,waterlogged=false]} and {@code
 * minecraft:light[level=11]} both become {@code light[level=11]}, so equal states always compare
 * equal as plain strings. Pure Java; defaults come from an injected {@link Defaults} resolver.
 */
public final class StateCanon {

    /** Full default property map of a material without namespace; {@code null} for an unknown material. */
    @FunctionalInterface
    public interface Defaults {
        Map<String, String> of(String material);
    }

    /** Parsed state: material without namespace plus sorted properties. */
    public record Parsed(String material, Map<String, String> properties) {
    }

    private final Defaults defaults;
    private final Map<String, Map<String, String>> defaultCache = new ConcurrentHashMap<>();
    private final Map<String, String> canonCache = new ConcurrentHashMap<>();

    public StateCanon(Defaults defaults) {
        this.defaults = defaults;
    }

    /** Parses {@code [minecraft:]name[k=v,...]}; rejects malformed input with {@link IllegalArgumentException}. */
    public static Parsed parse(String state) {
        if (state == null) throw new IllegalArgumentException("block state is missing");
        String s = state.trim().toLowerCase(Locale.ROOT);
        int open = s.indexOf('[');
        String name = open < 0 ? s : s.substring(0, open);
        if (name.startsWith("minecraft:")) name = name.substring("minecraft:".length());
        if (name.isEmpty() || !name.matches("[a-z0-9_./-]+"))
            throw new IllegalArgumentException("invalid block state '" + state + "'");
        Map<String, String> props = new TreeMap<>();
        if (open >= 0) {
            if (!s.endsWith("]")) throw new IllegalArgumentException("invalid block state '" + state + "': missing ]");
            String body = s.substring(open + 1, s.length() - 1).trim();
            if (!body.isEmpty()) {
                for (String kv : body.split(",")) {
                    int eq = kv.indexOf('=');
                    String k = eq < 0 ? "" : kv.substring(0, eq).trim();
                    String v = eq < 0 ? "" : kv.substring(eq + 1).trim();
                    if (k.isEmpty() || v.isEmpty())
                        throw new IllegalArgumentException("invalid property '" + kv.trim() + "' in '" + state + "'");
                    if (props.put(k, v) != null)
                        throw new IllegalArgumentException("duplicate property '" + k + "' in '" + state + "'");
                }
            }
        }
        return new Parsed(name, props);
    }

    /** Material without namespace, without parsing properties. */
    public static String material(String state) {
        String s = state.trim().toLowerCase(Locale.ROOT);
        int open = s.indexOf('[');
        String name = open < 0 ? s : s.substring(0, open);
        return name.startsWith("minecraft:") ? name.substring("minecraft:".length()) : name;
    }

    public static boolean isAir(String canonicalMaterial) {
        return canonicalMaterial.equals("air") || canonicalMaterial.equals("cave_air") || canonicalMaterial.equals("void_air");
    }

    /** The canonical form of {@code state}; unknown materials and properties are rejected. Cached per input string. */
    public String canon(String state) {
        String cached = canonCache.get(state);
        if (cached != null) return cached;
        Parsed p = parse(state);
        Map<String, String> d = defaults(p.material());
        StringBuilder out = new StringBuilder(p.material());
        boolean first = true;
        for (var e : p.properties().entrySet()) {
            String def = d.get(e.getKey());
            if (def == null)
                throw new IllegalArgumentException("unknown property '" + e.getKey() + "' for block '" + p.material() + "'");
            if (def.equals(e.getValue())) continue;
            out.append(first ? '[' : ',').append(e.getKey()).append('=').append(e.getValue());
            first = false;
        }
        if (!first) out.append(']');
        String result = out.toString();
        if (canonCache.size() < 100_000) canonCache.put(state, result);
        return result;
    }

    private Map<String, String> defaults(String material) {
        Map<String, String> d = defaultCache.get(material);
        if (d != null) return d;
        Map<String, String> resolved = defaults.of(material);
        if (resolved == null) throw new IllegalArgumentException("unknown block '" + material + "'");
        d = Map.copyOf(resolved);
        defaultCache.put(material, d);
        return d;
    }
}
