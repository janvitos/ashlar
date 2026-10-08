// SPDX-License-Identifier: AGPL-3.0-or-later
package cc.wujm.ashlar.render;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class BlockShapesTest {
    private static double volume(String s){return BlockShapes.resolve("minecraft:"+s).boxes().stream().mapToDouble(BlockShapes.Box::volume).sum();}
    private static boolean occupied(String s,double x,double y,double z){return BlockShapes.resolve("minecraft:"+s).boxes().stream().anyMatch(b -> x>=b.x0()&&x<b.x1()&&y>=b.y0()&&y<b.y1()&&z>=b.z0()&&z<b.z1());}
    @Test void airHasNoGeometry(){for(String s:new String[]{"air","cave_air","void_air"})assertTrue(BlockShapes.resolve("minecraft:"+s).boxes().isEmpty());}
    @Test void fullCubeIsModeled(){assertEquals(1,volume("stone"));assertEquals("modeled",BlockShapes.resolve("minecraft:oak_planks").fidelity());}
    @Test void slabHalvesAndDouble(){assertEquals(.5,volume("stone_slab[type=bottom]"));assertEquals(1,volume("stone_slab[type=double]"));assertFalse(occupied("stone_slab[type=bottom]",.5,.75,.5));assertTrue(occupied("stone_slab[type=top]",.5,.75,.5));}
    @Test void stairsUseTallFacingHalf(){assertTrue(occupied("oak_stairs[facing=north]",.25,.75,.25));assertFalse(occupied("oak_stairs[facing=north]",.25,.75,.75));assertTrue(occupied("oak_stairs[facing=east]",.75,.75,.25));assertFalse(occupied("oak_stairs[facing=east]",.25,.75,.25));}
    @Test void stairShapesHaveCorrectQuarterVolumes(){assertEquals(.75,volume("oak_stairs[shape=straight]"));assertEquals(.875,volume("oak_stairs[shape=inner_left]"));assertEquals(.625,volume("oak_stairs[shape=outer_right]"));}
    @Test void cornerHandednessIsNotInterchanged(){assertTrue(occupied("oak_stairs[facing=north,shape=outer_left]",.25,.75,.25));assertFalse(occupied("oak_stairs[facing=north,shape=outer_left]",.75,.75,.25));assertTrue(occupied("oak_stairs[facing=north,shape=outer_right]",.75,.75,.25));}
    @Test void upsideDownStairsInvertLevels(){assertTrue(occupied("oak_stairs[facing=north,half=top]",.5,.75,.75));assertFalse(occupied("oak_stairs[facing=north,half=top]",.5,.25,.75));assertTrue(occupied("oak_stairs[facing=north,half=top]",.5,.25,.25));}
    @Test void doorClosedOppositeFacingAndOpenHinge(){assertTrue(occupied("oak_door[facing=north,open=false]",.5,.5,.95));assertFalse(occupied("oak_door[facing=north,open=false]",.5,.5,.05));assertTrue(occupied("oak_door[facing=north,open=true,hinge=left]",.05,.5,.5));assertTrue(occupied("oak_door[facing=north,open=true,hinge=right]",.95,.5,.5));}
    @Test void trapdoorHalvesAndOpen(){assertEquals(3.0/16,volume("oak_trapdoor[half=bottom,open=false]"));assertTrue(occupied("oak_trapdoor[half=top,open=false]",.5,.95,.5));assertFalse(occupied("oak_trapdoor[open=true,facing=north]",.5,.95,.5));}
    @Test void connectedFenceHasRailsNotFullWall(){assertTrue(occupied("oak_fence[north=true]",.5,.8,.1));assertFalse(occupied("oak_fence[north=true]",.5,.65,.1));assertFalse(occupied("oak_fence[north=false]",.5,.8,.1));assertEquals(1,BlockShapes.resolve("minecraft:oak_fence").boxes().getFirst().y1());}
    @Test void wallHeightsAndPost(){assertTrue(occupied("cobblestone_wall[north=tall,up=false]",.5,.95,.1));assertFalse(occupied("cobblestone_wall[north=low,up=false]",.5,.95,.1));}
    @Test void paneConnectionsAndTransparency(){assertTrue(occupied("glass_pane[east=true]",.9,.5,.5));assertFalse(occupied("glass_pane[east=false]",.9,.5,.5));assertTrue(BlockShapes.resolve("minecraft:glass").opacity()<1);assertEquals(1,BlockShapes.resolve("minecraft:iron_bars").opacity());}
    @Test void unsupportedShapeExplicitFallback(){assertEquals("cube_fallback",BlockShapes.resolve("minecraft:anvil").fidelity());assertEquals("approximate",BlockShapes.resolve("minecraft:chest").fidelity());}
    @Test void gateOpenRemovesCenter(){assertTrue(occupied("oak_fence_gate[open=false,facing=north]",.5,.5,.5));assertFalse(occupied("oak_fence_gate[open=true,facing=north]",.5,.5,.5));}
    @Test void shapeListsAndBoundsAreSafe(){assertThrows(UnsupportedOperationException.class,() -> BlockShapes.resolve("minecraft:stone").boxes().clear());assertThrows(IllegalArgumentException.class,() -> new BlockShapes.Box(0,0,0,2,1,1));}
}
