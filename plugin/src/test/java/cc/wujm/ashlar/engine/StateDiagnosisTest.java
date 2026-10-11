// SPDX-License-Identifier: AGPL-3.0-or-later
package cc.wujm.ashlar.engine;

import org.junit.jupiter.api.Test;

import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;

class StateDiagnosisTest {

    /** Stairs and a repeater with their vanilla properties; any single valid property parses. */
    private static final class FakeProbe implements StateDiagnosis.Probe {
        final AtomicInteger probes = new AtomicInteger();
        final Map<String, Map<String, Set<String>>> blocks = Map.of(
                "oak_stairs", props("facing", Set.of("north", "south", "west", "east"), "half", Set.of("top", "bottom"),
                        "shape", Set.of("straight", "inner_left", "inner_right", "outer_left", "outer_right"), "waterlogged", Set.of("true", "false")),
                "repeater", props("delay", Set.of("1", "2", "3", "4"), "facing", Set.of("north", "south", "west", "east"),
                        "locked", Set.of("true", "false"), "powered", Set.of("true", "false")),
                "stone", Map.of());

        private static Map<String, Set<String>> props(Object... kv) {
            Map<String, Set<String>> m = new LinkedHashMap<>();
            for (int i = 0; i < kv.length; i += 2) {
                @SuppressWarnings("unchecked") Set<String> v = (Set<String>) kv[i + 1];
                m.put((String) kv[i], v);
            }
            return m;
        }

        @Override public Map<String, String> defaults(String material) {
            Map<String, Set<String>> b = blocks.get(material);
            if (b == null) return null;
            Map<String, String> d = new LinkedHashMap<>();
            b.forEach((k, v) -> d.put(k, k.equals("delay") ? "1" : k.equals("facing") ? "north" : v.contains("false") ? "false"
                    : k.equals("half") ? "bottom" : "straight"));
            return d;
        }

        @Override public boolean valid(String state) {
            probes.incrementAndGet();
            StateCanon.Parsed p = StateCanon.parse(state);
            Map<String, Set<String>> b = blocks.get(p.material());
            return b != null && p.properties().entrySet().stream().allMatch(e -> b.containsKey(e.getKey()) && b.get(e.getKey()).contains(e.getValue()));
        }

        @Override public Collection<String> blocks() {
            return List.of("oak_stairs", "oak_slab", "stone", "stone_bricks", "stone_brick_stairs", "repeater");
        }
    }

    @Test
    void syntaxErrorsKeepTheParserMessage() {
        StateDiagnosis d = new StateDiagnosis(new FakeProbe());
        assertEquals("missing ]", d.diagnose("minecraft:oak_stairs[facing=north"));
        assertEquals("malformed block name", d.diagnose("minecraft:oak stairs"));
        assertEquals("duplicate property 'half' in 'oak_stairs[half=top,half=top]'", d.diagnose("oak_stairs[half=top,half=top]"));
    }

    @Test
    void unknownBlockSuggestsCloseNames() {
        StateDiagnosis d = new StateDiagnosis(new FakeProbe());
        assertEquals("unknown block 'oak_stair'; did you mean oak_stairs?", d.diagnose("minecraft:oak_stair"));
        assertEquals("unknown block 'stone_brick'; did you mean stone_bricks, stone, stone_brick_stairs?", d.diagnose("stone_brick"));
        assertEquals("unknown block 'zzzzzzzz'", d.diagnose("zzzzzzzz"));
    }

    @Test
    void unknownPropertyListsTheBlocksProperties() {
        StateDiagnosis d = new StateDiagnosis(new FakeProbe());
        assertEquals("oak_stairs has no property 'facin'; properties: facing, half, shape, waterlogged",
                d.diagnose("minecraft:oak_stairs[facin=north]"));
        assertEquals("stone has no properties; use plain stone", d.diagnose("minecraft:stone[facing=north]"));
    }

    @Test
    void invalidValueListsAllowedValuesDefaultFirstAndIsCached() {
        FakeProbe probe = new FakeProbe();
        StateDiagnosis d = new StateDiagnosis(probe);
        assertEquals("oak_stairs facing=up is invalid; facing: north|east|south|west", d.diagnose("minecraft:oak_stairs[facing=up,half=top]"));
        int probes = probe.probes.get();
        d.diagnose("minecraft:oak_stairs[facing=down]");
        assertEquals(probes, probe.probes.get());
        assertEquals("repeater delay=9 is invalid; delay: 1|2|3|4", d.diagnose("minecraft:repeater[delay=9]"));
    }

    @Test
    void distanceStopsEarly() {
        assertEquals(1, StateDiagnosis.distance("oak_stair", "oak_stairs", 4));
        assertEquals(5, StateDiagnosis.distance("a", "abcdefghij", 4));
    }
}
