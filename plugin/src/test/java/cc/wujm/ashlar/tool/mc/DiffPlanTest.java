// SPDX-License-Identifier: AGPL-3.0-or-later
package cc.wujm.ashlar.tool.mc;

import cc.wujm.ashlar.engine.Region;
import cc.wujm.ashlar.tool.ToolArgError;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class DiffPlanTest {
    private static long innerVolume(List<DiffPlan.Part> parts) {
        return parts.stream().mapToLong(p -> p.inner().volume()).sum();
    }

    @Test void smallBoxIsOnePart() {
        Region box = new Region(0, 60, 0, 9, 69, 9);
        var parts = DiffPlan.split(box, 1_000_000, 1, -64, 319);
        assertEquals(1, parts.size());
        assertEquals(box, parts.getFirst().inner());
        assertEquals(new Region(-1, 59, -1, 10, 70, 10), parts.getFirst().outer());
    }

    @Test void ySlabsCoverTheBoxExactlyAndRespectTheReadLimit() {
        Region box = new Region(0, 0, 0, 99, 59, 99);
        var parts = DiffPlan.split(box, 100_000, 1, -64, 319);
        assertTrue(parts.size() > 1);
        assertEquals(box.volume(), innerVolume(parts));
        for (var p : parts) assertTrue(p.outer().volume() <= 100_000, p.toString());
    }

    @Test void wideBoxFallsBackToZBandsAndClampsToWorld() {
        Region box = new Region(0, -64, 0, 999, -63, 999);
        var parts = DiffPlan.split(box, 50_000, 1, -64, 319);
        assertEquals(box.volume(), innerVolume(parts));
        for (var p : parts) {
            assertEquals(p.inner().minY(), p.inner().maxY());
            assertTrue(p.outer().volume() <= 50_000);
            assertTrue(p.outer().minY() >= -64);
        }
    }

    @Test void rowWiderThanReadLimitIsRejected() {
        assertThrows(ToolArgError.class, () -> DiffPlan.split(new Region(0, 0, 0, 9_999, 0, 0), 1_000, 1, -64, 319));
    }
}
