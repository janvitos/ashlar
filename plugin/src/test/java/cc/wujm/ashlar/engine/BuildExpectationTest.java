// SPDX-License-Identifier: AGPL-3.0-or-later
package cc.wujm.ashlar.engine;

import org.junit.jupiter.api.Test;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import static org.junit.jupiter.api.Assertions.*;

class BuildExpectationTest {
    private static BuildExpectation.Cell cell(String s) {return new BuildExpectation.Cell(new BuildExpectation.Pos(1,2,3),s,null);}
    @Test void exactIncludesEveryProperty(){assertFalse(BuildExpectation.stateMatches("minecraft:oak_stairs[facing=north,shape=straight]","minecraft:oak_stairs[facing=east,shape=straight]",Set.of()));}
    @Test void missingAndUnexpectedClassify(){assertEquals("missing",BuildExpectation.kind(cell("minecraft:stone"),"minecraft:air"));assertEquals("unexpected",BuildExpectation.kind(cell("minecraft:air"),"minecraft:stone"));}
    @Test void materialAndStateClassify(){assertEquals("wrong_material",BuildExpectation.kind(cell("minecraft:stone"),"minecraft:dirt"));assertEquals("wrong_state",BuildExpectation.kind(cell("minecraft:oak_stairs[facing=north]"),"minecraft:oak_stairs[facing=south]"));}
    @Test void placementOnlyExcludesListedDerivedProperties(){var excluded=BuildExpectation.excluded("minecraft:oak_stairs[facing=north,shape=straight]",true,true);assertEquals(Set.of("shape"),excluded);assertTrue(BuildExpectation.stateMatches("minecraft:oak_stairs[facing=north,shape=straight]","minecraft:oak_stairs[shape=outer_left,facing=north]",excluded));assertFalse(BuildExpectation.stateMatches("minecraft:oak_stairs[facing=north,shape=straight]","minecraft:oak_stairs[shape=outer_left,facing=south]",excluded));}
    @Test void noExclusionsForUnconnectedOrExact(){assertTrue(BuildExpectation.excluded("minecraft:oak_stairs",true,false).isEmpty());assertTrue(BuildExpectation.excluded("minecraft:oak_stairs",false,true).isEmpty());}
    @Test void chestTypesAndNonConnectionPropertiesStayExact(){assertTrue(BuildExpectation.excluded("minecraft:chest[type=left]",true,true).isEmpty());var ex=BuildExpectation.excluded("minecraft:glass_pane",true,true);assertFalse(ex.contains("waterlogged"));assertFalse(ex.contains("facing"));assertFalse(ex.contains("power"));}
    @Test void exclusionsNeverAllowWrongMaterials(){assertFalse(BuildExpectation.stateMatches("minecraft:glass_pane[north=true]","minecraft:iron_bars[north=true]",Set.of("north")));}
    @Test void diagnosticsIncludeCoordinatesAndPropertyDifferences(){var e=cell("minecraft:oak_stairs[facing=north,half=bottom]");var d=BuildExpectation.difference(new BuildExpectation.Observed(e,"minecraft:oak_stairs[facing=south,half=bottom]",null,false),Set.of());assertEquals("[1,2,3]",d.get("pos").toString());assertEquals("north",d.getAsJsonObject("propertyDifferences").getAsJsonObject("facing").get("expected").getAsString());assertFalse(d.getAsJsonObject("propertyDifferences").has("half"));}
    @Test void manifestDefensivelyCopiesCells(){List<BuildExpectation.Cell> cells=new ArrayList<>(List.of(cell("minecraft:stone")));var e=new BuildExpectation("world",Region.of(new int[]{0,0,0},new int[]{1,1,1}),cells,false,false);cells.clear();assertEquals(1,e.cells().size());assertThrows(UnsupportedOperationException.class,() -> e.cells().clear());}
    @Test void signOnlyDifferenceClassifies(){var e=new BuildExpectation.Cell(new BuildExpectation.Pos(0,0,0),"minecraft:oak_sign",SignSnapshot.empty());assertEquals("sign_metadata",BuildExpectation.difference(new BuildExpectation.Observed(e,e.state(),null,true),Set.of()).get("kind").getAsString());}
}
