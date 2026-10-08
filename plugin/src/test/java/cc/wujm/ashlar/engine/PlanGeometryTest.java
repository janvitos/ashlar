// SPDX-License-Identifier: AGPL-3.0-or-later
package cc.wujm.ashlar.engine;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class PlanGeometryTest {
    private final Region cube = new Region(-1,-1,-1,1,1,1);
    @Test void replaceAndKeepSelectEveryCell() {
        for (var mode:new FillMode[]{FillMode.REPLACE,FillMode.KEEP})
            for (int y=-1;y<=1;y++) for (int z=-1;z<=1;z++) for (int x=-1;x<=1;x++)
                assertEquals(1,PlanGeometry.target(mode,cube,x,y,z));
    }
    @Test void outlineLeavesInteriorUntouched() {
        assertEquals(0,PlanGeometry.target(FillMode.OUTLINE,cube,0,0,0));
        assertEquals(1,PlanGeometry.target(FillMode.OUTLINE,cube,0,1,0));
        assertEquals(1,PlanGeometry.target(FillMode.OUTLINE,cube,1,0,0));
    }
    @Test void hollowClearsInteriorAndFillsShell() {
        assertEquals(2,PlanGeometry.target(FillMode.HOLLOW,cube,0,0,0));
        assertEquals(1,PlanGeometry.target(FillMode.HOLLOW,cube,0,0,-1));
    }
    @Test void wallsExcludeFloorAndRoofInterior() {
        assertEquals(0,PlanGeometry.target(FillMode.WALLS,cube,0,-1,0));
        assertEquals(0,PlanGeometry.target(FillMode.WALLS,cube,0,1,0));
        assertEquals(1,PlanGeometry.target(FillMode.WALLS,cube,1,1,0));
    }
    @Test void aOneBlockThickPlaneIsEntirelyShell() {
        Region plane = new Region(0,0,0,2,2,0);
        assertEquals(1,PlanGeometry.target(FillMode.OUTLINE,plane,1,1,0));
        assertEquals(1,PlanGeometry.target(FillMode.HOLLOW,plane,1,1,0));
    }
    @Test void volumeIsInclusiveAndUsesLongs() {
        assertEquals(27,PlanGeometry.checkedVolume(cube));
        assertEquals(4_294_967_296L,PlanGeometry.checkedVolume(new Region(Integer.MIN_VALUE,0,0,Integer.MAX_VALUE,0,0)));
    }
    @Test void oversizedVolumeDoesNotWrapToAValidSmallValue() {
        assertThrows(IllegalArgumentException.class,() -> PlanGeometry.checkedVolume(new Region(Integer.MIN_VALUE,Integer.MIN_VALUE,Integer.MIN_VALUE,
                Integer.MAX_VALUE,Integer.MAX_VALUE,Integer.MAX_VALUE)));
    }
}
