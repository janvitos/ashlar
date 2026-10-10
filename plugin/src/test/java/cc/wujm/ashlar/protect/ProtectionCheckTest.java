// SPDX-License-Identifier: AGPL-3.0-or-later
package cc.wujm.ashlar.protect;

import cc.wujm.ashlar.engine.Region;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class ProtectionCheckTest {

    private static ProtectedRegion region(String name, Region box, ProtectedRegion.Mode mode) {
        return new ProtectedRegion(name, "world", box, mode, null, "SYSTEM:agent", Instant.EPOCH);
    }

    @Test
    void solidBoxCountsTheIntersectionAndItsBounds() {
        ProtectionCheck c = new ProtectionCheck(List.of(region("dock", new Region(0, 60, 0, 9, 69, 9), ProtectedRegion.Mode.DENY)));
        c.box(new Region(5, 65, 5, 20, 65, 20), ProtectionCheck.Shape.SOLID);
        c.box(new Region(100, 0, 100, 110, 10, 110), ProtectionCheck.Shape.SOLID);
        var o = c.overlaps();
        assertEquals(1, o.size());
        assertEquals(25, o.getFirst().cells());
        assertEquals(new Region(5, 65, 5, 9, 65, 9), o.getFirst().bounds());
    }

    @Test
    void outlineAndWallsSkipARegionInsideTheInterior() {
        Region inner = new Region(4, 64, 4, 6, 66, 6);
        ProtectionCheck c = new ProtectionCheck(List.of(region("core", inner, ProtectedRegion.Mode.DENY)));
        c.box(new Region(0, 60, 0, 10, 70, 10), ProtectionCheck.Shape.SHELL);
        c.box(new Region(0, 60, 0, 10, 70, 10), ProtectionCheck.Shape.SIDES);
        assertTrue(c.overlaps().isEmpty());
        // Every shell cell of an 11x11x11 box is counted once: 11^3 - 9^3.
        ProtectionCheck all = new ProtectionCheck(List.of(region("all", new Region(-100, 0, -100, 100, 200, 100), ProtectedRegion.Mode.WARN)));
        all.box(new Region(0, 60, 0, 10, 70, 10), ProtectionCheck.Shape.SHELL);
        assertEquals(1331 - 729, all.overlaps().getFirst().cells());
        ProtectionCheck sides = new ProtectionCheck(List.of(region("all", new Region(-100, 0, -100, 100, 200, 100), ProtectedRegion.Mode.WARN)));
        sides.box(new Region(0, 60, 0, 10, 70, 10), ProtectionCheck.Shape.SIDES);
        assertEquals(11L * (121 - 81), sides.overlaps().getFirst().cells());
    }

    @Test
    void thinBoxesAreAllShell() {
        ProtectionCheck c = new ProtectionCheck(List.of(region("all", new Region(-10, -10, -10, 10, 10, 10), ProtectedRegion.Mode.DENY)));
        c.box(new Region(0, 0, 0, 1, 5, 5), ProtectionCheck.Shape.SIDES);
        c.box(new Region(0, 0, 0, 5, 1, 5), ProtectionCheck.Shape.SHELL);
        assertEquals(2 * 6 * 6 + 6 * 2 * 6, c.overlaps().getFirst().cells());
    }

    @Test
    void cellsHitEveryContainingRegionAndNothingOutside() {
        ProtectionCheck c = new ProtectionCheck(List.of(
                region("a", new Region(0, 0, 0, 5, 5, 5), ProtectedRegion.Mode.DENY),
                region("b", new Region(5, 5, 5, 9, 9, 9), ProtectedRegion.Mode.WARN)));
        c.cell(5, 5, 5);
        c.cell(1, 1, 1);
        c.cell(50, 5, 5);
        var o = c.overlaps();
        assertEquals(2, o.size());
        assertEquals(2, o.get(0).cells());
        assertEquals(new Region(1, 1, 1, 5, 5, 5), o.get(0).bounds());
        assertEquals(1, o.get(1).cells());
    }

    @Test
    void noRegionsMeansEmptyAndNothingCounted() {
        ProtectionCheck c = new ProtectionCheck(List.of());
        assertTrue(c.empty());
        c.box(new Region(0, 0, 0, 10, 10, 10), ProtectionCheck.Shape.SOLID);
        c.cell(1, 1, 1);
        assertTrue(c.overlaps().isEmpty());
    }

    @Test
    void namesAreValidated() {
        assertThrows(IllegalArgumentException.class, () -> region("Dock", new Region(0, 0, 0, 1, 1, 1), ProtectedRegion.Mode.DENY));
        assertThrows(IllegalArgumentException.class, () -> region("*", new Region(0, 0, 0, 1, 1, 1), ProtectedRegion.Mode.DENY));
        assertDoesNotThrow(() -> region("dock-2_b", new Region(0, 0, 0, 1, 1, 1), ProtectedRegion.Mode.DENY));
    }
}
