// SPDX-License-Identifier: AGPL-3.0-or-later
package cc.wujm.ashlar.protect;

import cc.wujm.ashlar.engine.Region;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;

import static org.junit.jupiter.api.Assertions.*;

class ProtectedRegionsTest {
    @TempDir Path dir;
    private final Clock clock = Clock.fixed(Instant.parse("2026-10-10T12:00:00Z"), ZoneOffset.UTC);

    private ProtectedRegions store() throws IOException {
        ProtectedRegions s = new ProtectedRegions(dir.resolve("protected.json"), clock);
        s.load();
        return s;
    }

    @Test
    void persistsAndReloadsEveryField() throws IOException {
        ProtectedRegions s = store();
        s.add("dock", "world", Region.of(new int[]{10, 60, -5}, new int[]{0, 70, 5}), ProtectedRegion.Mode.WARN, "approved", "PLAYER:alice");
        s.add("berg", "world_nether", new Region(1, 2, 3, 4, 5, 6), ProtectedRegion.Mode.DENY, null, "WS_TOKEN:127.0.0.1");
        ProtectedRegions again = store();
        assertEquals(2, again.all().size());
        ProtectedRegion dock = again.get("dock");
        assertEquals(new Region(0, 60, -5, 10, 70, 5), dock.box());
        assertEquals(ProtectedRegion.Mode.WARN, dock.mode());
        assertEquals("approved", dock.note());
        assertEquals("PLAYER:alice", dock.owner());
        assertEquals(clock.instant(), dock.createdAt());
        assertEquals(1, again.inWorld("world").size());
        assertNull(again.get("berg").note());
        try (var files = Files.list(dir)) {
            assertEquals(1, files.count(), "no temporary files left behind");
        }
    }

    @Test
    void duplicateNamesAndForeignRemovalsAreRejected() throws IOException {
        ProtectedRegions s = store();
        s.add("dock", "world", new Region(0, 0, 0, 1, 1, 1), ProtectedRegion.Mode.DENY, null, "PLAYER:alice");
        assertThrows(IllegalArgumentException.class,
                () -> s.add("dock", "world", new Region(0, 0, 0, 1, 1, 1), ProtectedRegion.Mode.DENY, null, "PLAYER:alice"));
        assertThrows(IllegalArgumentException.class, () -> s.remove("dock", "PLAYER:bob", false));
        assertThrows(IllegalArgumentException.class, () -> s.remove("missing", "PLAYER:alice", true));
        assertEquals("dock", s.remove("dock", "PLAYER:bob", true).name());
        assertTrue(store().all().isEmpty());
    }

    @Test
    void ownerMayRemoveAndAMissingFileMeansNoRegions() throws IOException {
        ProtectedRegions s = store();
        assertTrue(s.all().isEmpty());
        s.add("dock", "world", new Region(0, 0, 0, 1, 1, 1), ProtectedRegion.Mode.DENY, null, "PLAYER:alice");
        s.remove("dock", "PLAYER:alice", false);
        assertTrue(s.all().isEmpty());
    }

    @Test
    void anInvalidFileFailsToLoadAndKeepsTheLoadedRegions() throws IOException {
        ProtectedRegions s = store();
        s.add("dock", "world", new Region(0, 0, 0, 1, 1, 1), ProtectedRegion.Mode.DENY, null, "PLAYER:alice");
        Files.writeString(dir.resolve("protected.json"), "{\"regions\":[{\"name\":\"BAD NAME\"}]}");
        assertThrows(IOException.class, s::load);
        assertNotNull(s.get("dock"));
    }
}
