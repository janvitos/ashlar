// SPDX-License-Identifier: AGPL-3.0-or-later
package cc.wujm.ashlar.journal;

import cc.wujm.ashlar.engine.EntitySpec;
import cc.wujm.ashlar.engine.Region;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.*;

/** Step 17: sign and entity journal records and their storage next to the cells. */
class JournalExtrasTest {

    @TempDir Path dir;

    private static EntitySpec stand(int x) {
        return new EntitySpec(EntitySpec.ARMOR_STAND, new int[]{x, 64, 0}, null, null, false, null, null, 0, false, false, 0f, null, null,
                false, false, true);
    }

    private static JsonObject text(String line) {
        return JsonParser.parseString("{\"front\":[\"" + line + "\",\"\",\"\",\"\"]}").getAsJsonObject();
    }

    private static JournalExtras sample() {
        JournalExtras x = new JournalExtras();
        assertTrue(x.signBefore(1, 70, 1, text("old")));
        assertFalse(x.signBefore(1, 70, 1, text("ignored")), "first text wins");
        x.signBefore(2, 70, 1, text("same"));
        for (long k : x.signKeys()) x.signAfter(k, JournalRecorder.x(k) == 1 ? text("new") : text("same"));
        x.spawned(new UUID(1, 2), stand(5));
        x.removed(stand(-3));
        x.changed(new UUID(3, 4), stand(9), stand(9).withPlaced(false));
        return x;
    }

    @Test void unchangedSignsAreDropped() {
        JournalExtras x = sample();
        assertEquals(1, x.signs().size());
        assertEquals(4, x.size());
        assertFalse(x.isEmpty());
        assertTrue(new JournalExtras().isEmpty());
        assertEquals(new Region(-3, 64, 0, 9, 70, 1), x.bounds(null));
        assertEquals(new Region(-3, 0, -5, 9, 70, 1), x.bounds(new Region(0, 0, -5, 0, 0, -5)));
    }

    @Test void jsonRoundTrip() {
        JournalExtras x = sample();
        JournalExtras y = JournalExtras.fromJson(JsonParser.parseString(x.toJson().toString()).getAsJsonObject());
        assertEquals(x.toJson(), y.toJson());
        assertEquals(new UUID(1, 2), y.spawnedList().getFirst().id());
        assertTrue(y.removedList().getFirst().sameAs(stand(-3)));
        assertEquals("new", y.signs().getFirst().after().getAsJsonArray("front").get(0).getAsString());
    }

    @Test void storeKeepsExtrasOnlyEntries() throws Exception {
        Clock clock = Clock.fixed(Instant.parse("2026-10-11T12:00:00Z"), ZoneOffset.UTC);
        JournalStore s = new JournalStore(dir, () -> new JournalStore.Limits(10, 1000, 30), clock, Logger.getLogger("test"));
        s.loadFromDisk();
        JournalCells none = new JournalCells(List.of(), new long[0], new int[0], new int[0]);
        JournalStore.Entry e = s.put("o", "world", "mc_build", null, null, 0, none, sample());
        assertEquals(4, e.extras());
        assertEquals(new Region(-3, 64, 0, 9, 70, 1), e.bounds());
        assertTrue(Files.exists(dir.resolve(e.id() + ".extra.json.gz")));
        assertEquals(sample().toJson(), s.extras(e).toJson());

        JournalStore reloaded = new JournalStore(dir, () -> new JournalStore.Limits(10, 1000, 30), clock, Logger.getLogger("test"));
        reloaded.loadFromDisk();
        JournalStore.Entry again = reloaded.get(e.id(), new JournalStore.Viewer("o", true));
        assertEquals(4, again.extras());
        assertEquals(4, reloaded.extras(again).size());
        assertEquals(List.of(again), reloaded.list(new JournalStore.Viewer("o", true), null, null, null, new Region(9, 64, 0, 9, 64, 0), 10));

        reloaded.delete(e.id(), new JournalStore.Viewer("o", true));
        assertFalse(Files.exists(dir.resolve(e.id() + ".extra.json.gz")));
    }
}
