// SPDX-License-Identifier: AGPL-3.0-or-later
package cc.wujm.ashlar.tool.mc;

import cc.wujm.ashlar.engine.BuildExpectation;
import cc.wujm.ashlar.tool.ToolArgError;
import org.junit.jupiter.api.Test;

import java.time.*;
import java.util.List;
import java.util.stream.IntStream;

import static org.junit.jupiter.api.Assertions.*;

class DiffStoreTest {
    private static List<BuildExpectation.Observed> cells(int n) {
        return IntStream.range(0, n).mapToObj(i -> new BuildExpectation.Observed(
                new BuildExpectation.Cell(new BuildExpectation.Pos(i, 64, 0), "minecraft:stone", null), "minecraft:air", null, false)).toList();
    }
    private static final class MutableClock extends Clock {Instant now=Instant.parse("2026-01-01T00:00:00Z");@Override public ZoneId getZone(){return ZoneOffset.UTC;}@Override public Clock withZone(ZoneId zone){return this;}@Override public Instant instant(){return now;}}

    @Test void ownerScopedAndConsumed() {
        var s = new DiffStore();
        var r = s.put("one", "world", cells(2), 2);
        assertTrue(r.id.startsWith("diff-"));
        assertSame(r, s.get(r.id, "one"));
        assertThrows(ToolArgError.class, () -> s.get(r.id, "two"));
        s.consume(r);
        assertThrows(ToolArgError.class, () -> s.get(r.id, "one"));
    }

    @Test void oldestIdleReceiptIsEvictedButBusyOnesAreKept() {
        var s = new DiffStore(Clock.systemUTC(), 2, 10, Duration.ofHours(1));
        var a = s.put("o", "w", cells(4), 4);
        var b = s.put("o", "w", cells(4), 4);
        s.acquire(a);
        var c = s.put("o", "w", cells(4), 4);
        assertSame(a, s.get(a.id, "o"));
        assertThrows(ToolArgError.class, () -> s.get(b.id, "o"));
        assertThrows(ToolArgError.class, () -> s.acquire(a));
        s.acquire(c);
        assertThrows(ToolArgError.class, () -> s.put("o", "w", cells(1), 1));
        s.release(a);
        s.release(c);
    }

    @Test void expiry() {
        var clock = new MutableClock();
        var s = new DiffStore(clock, 2, 10, Duration.ofHours(2));
        var r = s.put("o", "w", cells(1), 1);
        clock.now = clock.now.plusSeconds(7200);
        assertThrows(ToolArgError.class, () -> s.get(r.id, "o"));
    }
}
