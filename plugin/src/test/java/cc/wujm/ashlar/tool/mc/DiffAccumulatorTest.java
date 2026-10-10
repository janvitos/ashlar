// SPDX-License-Identifier: AGPL-3.0-or-later
package cc.wujm.ashlar.tool.mc;

import cc.wujm.ashlar.engine.Region;
import cc.wujm.ashlar.engine.RegionData;
import cc.wujm.ashlar.tool.ToolArgError;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.function.UnaryOperator;

import static org.junit.jupiter.api.Assertions.*;

class DiffAccumulatorTest {
    private static final Region BOX = new Region(0, 0, 0, 2, 0, 2);
    // Toy canon: drops the "waterlogged=false" default and the minecraft: prefix.
    private static final UnaryOperator<String> CANON = s -> {
        String c = s.replace("minecraft:", "").replace(",waterlogged=false", "").replace("[waterlogged=false]", "");
        if (c.startsWith("bogus")) throw new IllegalArgumentException("unknown");
        return c;
    };

    private static DiffAccumulator.Settings settings(boolean declaredOnly, boolean material) {
        return new DiffAccumulator.Settings(declaredOnly, material, 50, 100, false);
    }

    /** Live 3x1x3 layer: all stone except the given overrides at index z*3+x. */
    private static void feed(DiffAccumulator acc, String... overrides) {
        List<String> palette = new ArrayList<>(List.of("minecraft:stone"));
        int[] grid = new int[9];
        for (int i = 0; i < overrides.length; i += 2) {
            int idx = Integer.parseInt(overrides[i]);
            palette.add(overrides[i + 1]);
            grid[idx] = palette.size() - 1;
        }
        acc.feed(BOX, BOX, palette, grid);
    }

    private static DiffReference allStone() {
        var ref = new DiffReference(BOX, CANON);
        for (int z = 0; z <= 2; z++) for (int x = 0; x <= 2; x++) ref.add(x, 0, z, "stone");
        return ref;
    }

    @Test void classifiesEachDifferenceKind() {
        var ref = new DiffReference(BOX, CANON);
        for (int z = 0; z <= 2; z++) for (int x = 0; x <= 2; x++) ref.add(x, 0, z, x == 2 && z == 2 ? "air" : "oak_stairs[facing=north,waterlogged=false]");
        var acc = new DiffAccumulator(ref, null, settings(false, false), CANON, DiffIgnore.NONE);
        List<String> palette = List.of("minecraft:oak_stairs[facing=north,waterlogged=false]", "minecraft:cave_air",
                "minecraft:oak_stairs[facing=south,waterlogged=false]", "minecraft:stone", "minecraft:dirt");
        acc.feed(BOX, BOX, palette, new int[]{0, 1, 2, 3, 0, 0, 0, 0, 4});
        assertEquals(9, acc.compared);
        assertEquals(1, acc.missing);
        assertEquals(1, acc.wrongProperties);
        assertEquals(1, acc.wrongMaterial);
        assertEquals(1, acc.unexpected);
        assertEquals(4, acc.differing());
        assertEquals(4, acc.receipt.size());
        assertTrue(acc.summaryLine().contains("4 differ"));
    }

    @Test void canonicalEquivalenceAndAirVariantsMatch() {
        var ref = new DiffReference(BOX, CANON);
        ref.add(0, 0, 0, "light[level=3]");
        ref.add(1, 0, 0, "air");
        var acc = new DiffAccumulator(ref, null, settings(true, false), CANON, DiffIgnore.NONE);
        feed(acc, "0", "minecraft:light[level=3,waterlogged=false]", "1", "minecraft:void_air");
        assertEquals(2, acc.compared);
        assertEquals(7, acc.undeclared);
        assertEquals(0, acc.differing());
    }

    @Test void materialCompareIgnoresProperties() {
        var ref = new DiffReference(BOX, CANON);
        ref.add(0, 0, 0, "oak_stairs[facing=north]");
        var acc = new DiffAccumulator(ref, null, settings(true, true), CANON, DiffIgnore.NONE);
        feed(acc, "0", "minecraft:oak_stairs[facing=east]");
        assertEquals(0, acc.differing());
    }

    @Test void ignoreGlobsAndBaselineFallback() {
        var ref = new DiffReference(BOX, CANON);
        ref.add(0, 0, 0, "stone");
        var acc = new DiffAccumulator(ref, allStone(), settings(false, false), CANON, DiffIgnore.parse(List.of("*_leaves")));
        feed(acc, "4", "minecraft:oak_leaves[distance=1]", "8", "minecraft:dirt");
        assertEquals(1, acc.ignoredCells);
        assertEquals(8, acc.compared);
        assertEquals(1, acc.wrongMaterial);
        assertEquals(0, acc.undeclared);
    }

    @Test void unknownStatesAreComparedVerbatim() {
        var acc = new DiffAccumulator(allStone(), null, settings(false, false), CANON, DiffIgnore.NONE);
        feed(acc, "0", "minecraft:bogus_block");
        assertEquals(1, acc.wrongMaterial);
    }

    @Test void referenceRejectsDuplicatesAndCountsOutside() {
        var ref = new DiffReference(BOX, CANON);
        ref.add(0, 0, 0, "stone");
        ref.add(9, 0, 0, "stone");
        assertEquals(1, ref.outside);
        assertThrows(ToolArgError.class, () -> ref.add(0, 0, 0, "dirt"));
    }

    @Test void snapshotReferenceMustContainTheBox() {
        var data = new RegionData(new Region(0, 0, 0, 1, 0, 1), List.of("minecraft:stone"), new int[]{0}, new int[]{4});
        assertThrows(ToolArgError.class, () -> DiffReference.fromRegionData(BOX, data, "snapshot s"));
        var ref = DiffReference.fromRegionData(new Region(1, 0, 1, 1, 0, 1), data, "snapshot s");
        assertEquals(1, ref.declared);
        assertEquals(1, ref.cells[0]);
    }

    @Test void enclosedAirFindsSealedPocket() {
        Region box = new Region(0, 0, 0, 2, 2, 2);
        var ref = new DiffReference(box, CANON);
        for (int y = 0; y <= 2; y++) for (int z = 0; z <= 2; z++) for (int x = 0; x <= 2; x++) ref.add(x, y, z, "stone");
        var acc = new DiffAccumulator(ref, null, new DiffAccumulator.Settings(false, false, 50, 100, true), CANON, DiffIgnore.NONE);
        int[] grid = new int[27];
        grid[13] = 1;
        acc.feed(box, box, List.of("minecraft:stone", "minecraft:air"), grid);
        acc.finishEnclosedAir();
        assertEquals(1L, acc.anomalyCounts.get("enclosedAir"));
    }
}
