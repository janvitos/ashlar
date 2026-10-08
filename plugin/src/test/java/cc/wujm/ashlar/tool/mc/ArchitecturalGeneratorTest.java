// SPDX-License-Identifier: AGPL-3.0-or-later
package cc.wujm.ashlar.tool.mc;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import static org.junit.jupiter.api.Assertions.*;

class ArchitecturalGeneratorTest {
    private record P(int x, int y, int z) {}
    private static ArchitecturalGenerator.Output generate(String s) { return ArchitecturalGenerator.generate(JsonParser.parseString(s).getAsJsonObject()); }
    private static Map<P,String> cells(ArchitecturalGenerator.Output g) {
        Map<P,String> out = new HashMap<>();
        var c = g.document().getAsJsonObject("components").entrySet().iterator().next().getValue().getAsJsonObject();
        for (var e : c.getAsJsonArray("fills")) {
            var f=e.getAsJsonObject();var a=f.getAsJsonArray("from");var b=f.getAsJsonArray("to");
            assertFalse(f.has("mode"));assertNotEquals("minecraft:air",f.get("block").getAsString());
            for(int x=a.get(0).getAsInt();x<=b.get(0).getAsInt();x++)for(int y=a.get(1).getAsInt();y<=b.get(1).getAsInt();y++)for(int z=a.get(2).getAsInt();z<=b.get(2).getAsInt();z++) {
                P p=new P(x,y,z);assertNull(out.put(p,f.get("block").getAsString()),"generated operations must not overwrite another feature at "+p);
            }
        }
        assertEquals(out.size(),g.summary().get("requestedCells").getAsInt());
        return out;
    }
    private static void connected(Set<P> cells) {
        Set<P> seen=new HashSet<>();var queue=new java.util.ArrayDeque<P>();queue.add(cells.iterator().next());
        while(!queue.isEmpty()) { P p=queue.remove();if(!cells.contains(p)||!seen.add(p))continue;
            queue.add(new P(p.x+1,p.y,p.z));queue.add(new P(p.x-1,p.y,p.z));queue.add(new P(p.x,p.y+1,p.z));queue.add(new P(p.x,p.y-1,p.z));queue.add(new P(p.x,p.y,p.z+1));queue.add(new P(p.x,p.y,p.z-1));
        }
        assertEquals(cells.size(),seen.size(),"face-connected body");
    }
    @Test void allKindsDefaultToBoundedValidReusableDocuments() {
        for(String kind:new String[]{"roof","arch","tower","stairs"}) {
            var g=generate("{\"kind\":\""+kind+"\"}");BlueprintCompiler.validate(g.document());cells(g);
            assertTrue(g.summary().get("operations").getAsInt()<=10000);assertTrue(g.summary().get("requestedCells").getAsInt()<=200000);
            var request=JsonParser.parseString("{\"blueprint\":{\"id\":\"test_part\"}}").getAsJsonObject();
            assertEquals(1,BlueprintCompiler.compile(g.document(),request,BlueprintCompiler.Limits.DEFAULT).size());
        }
    }
    @Test void maximumHipRoofCompressesLongIdenticalRuns() {var g=generate("{\"kind\":\"roof\",\"style\":\"hip\",\"width\":9,\"depth\":64}");assertTrue(g.summary().get("operations").getAsInt()<9*64/2);assertEquals(9*64,cells(g).size());}
    @Test void dimensionsExactlyEncloseGeneratedCells() {for(String kind:new String[]{"roof","arch","tower","stairs"}){var g=generate("{\"kind\":\""+kind+"\"}");var c=cells(g);var d=g.document().getAsJsonArray("dimensions");assertEquals(c.keySet().stream().mapToInt(P::x).max().orElseThrow()+1,d.get(0).getAsInt());assertEquals(c.keySet().stream().mapToInt(P::y).max().orElseThrow()+1,d.get(1).getAsInt());assertEquals(c.keySet().stream().mapToInt(P::z).max().orElseThrow()+1,d.get(2).getAsInt());}}
    @Test void generationIsDeterministic() {var a=generate("{\"kind\":\"tower\"}");assertEquals(a,generate("{\"kind\":\"tower\"}"));}
    @Test void gableProfilesAreSymmetricForEvenAndOddWidths() {
        for(int w:new int[]{3,4,9,10,63,64}) {
            var c=cells(generate("{\"kind\":\"roof\",\"width\":"+w+",\"depth\":7}"));assertEquals(w*7,c.size());
            for(int x=0;x<w;x++) {int y=Math.min(x,w-1-x);assertNotNull(c.get(new P(x,y,3)));if(w%2==1&&x==w/2)assertEquals("$slab[type=bottom]",c.get(new P(x,y,3)));}
        }
    }
    @Test void gableInfillDoesNotOverwriteSlope() {var c=cells(generate("{\"kind\":\"roof\",\"width\":9,\"depth\":7,\"gableInfill\":true}"));assertEquals("$full",c.get(new P(4,0,0)));assertNull(c.get(new P(4,0,3)));assertEquals("$slab[type=bottom]",c.get(new P(4,4,0)));}
    @Test void gableUsesCompressedDepthRuns() {assertEquals(64,generate("{\"kind\":\"roof\",\"width\":64,\"depth\":64}").summary().get("operations").getAsInt());}
    @Test void shedRisesEast() {var c=cells(generate("{\"kind\":\"roof\",\"style\":\"shed\",\"width\":7,\"depth\":5}"));for(int x=0;x<7;x++)assertEquals("$stairs[facing=east,half=bottom,shape=straight]",c.get(new P(x,x,2)));}
    @Test void hipHasFourCorrectQuarterCorners() {var c=cells(generate("{\"kind\":\"roof\",\"style\":\"hip\",\"width\":9,\"depth\":11}"));for(var e:Map.of(new P(0,0,0),"east",new P(8,0,0),"south",new P(8,0,10),"west",new P(0,0,10),"north").entrySet())assertEquals("$stairs[facing="+e.getValue()+",half=bottom,shape=outer_right]",c.get(e.getKey()));assertEquals("$slab[type=bottom]",c.get(new P(4,4,5)));}
    @Test void hipHeightIsMinimumDistanceToFootprintEdge() {for(int w:new int[]{3,4,7,8})for(int d:new int[]{3,6,9}){var c=cells(generate("{\"kind\":\"roof\",\"style\":\"hip\",\"width\":"+w+",\"depth\":"+d+"}"));assertEquals(w*d,c.size());for(int x=0;x<w;x++)for(int z=0;z<d;z++)assertNotNull(c.get(new P(x,Math.min(Math.min(x,w-1-x),Math.min(z,d-1-z)),z)));}}
    @Test void roundArchesAreSymmetricOpenAndFaceConnected() {for(int w:new int[]{3,5,9,19,63}){var c=cells(generate("{\"kind\":\"arch\",\"width\":"+w+"}"));for(P p:c.keySet())assertTrue(c.containsKey(new P(w-1-p.x,p.y,p.z)));assertNull(c.get(new P(w/2,0,0)));assertNull(c.get(new P(w/2,2,0)));connected(c.keySet());}}
    @Test void pointedArchesAreSymmetricOpenAndFaceConnected() {for(int w:new int[]{3,5,9,19,63}){var c=cells(generate("{\"kind\":\"arch\",\"style\":\"pointed\",\"width\":"+w+"}"));for(P p:c.keySet())assertTrue(c.containsKey(new P(w-1-p.x,p.y,p.z)));assertNull(c.get(new P(w/2,0,0)));connected(c.keySet());}}
    @Test void thickExtrudedArchesRetainOpening() {var c=cells(generate("{\"kind\":\"arch\",\"width\":13,\"thickness\":3,\"depth\":4}"));assertNull(c.get(new P(6,1,2)));assertEquals("$full",c.get(new P(1,1,2)));connected(c.keySet());}
    @Test void towersHaveOpenInteriorAndOptionalFloor() {var c=cells(generate("{\"kind\":\"tower\",\"diameter\":13}"));assertEquals("$full",c.get(new P(6,0,6)));assertNull(c.get(new P(6,1,6)));assertNull(c.get(new P(0,1,0)));var no=cells(generate("{\"kind\":\"tower\",\"diameter\":13,\"floor\":false}"));assertNull(no.get(new P(6,0,6)));}
    @Test void thinTowerWallsAreFaceConnectedWithoutFloorOrMerlons() {for(int d:new int[]{5,6,9,13,19,32,63,64}) {var c=cells(generate("{\"kind\":\"tower\",\"diameter\":"+d+",\"height\":3,\"floor\":false,\"battlements\":false}"));connected(c.keySet());assertNull(c.get(new P(d/2,1,d/2)));}}
    @Test void thickTowerWallsRetainCoreAndConnectivity() {var c=cells(generate("{\"kind\":\"tower\",\"diameter\":21,\"height\":4,\"thickness\":5,\"floor\":false}"));assertNull(c.get(new P(10,0,10)));connected(c.keySet());}
    @Test void battlementsAreOptionalAndDoNotExceedOneExtraLayer() {var g=generate("{\"kind\":\"tower\",\"height\":9}");var c=cells(g);assertTrue(c.keySet().stream().anyMatch(p->p.y==9));assertEquals(9,g.summary().getAsJsonArray("to").get(1).getAsInt());assertEquals(8,generate("{\"kind\":\"tower\",\"height\":9,\"battlements\":false}").summary().getAsJsonArray("to").get(1).getAsInt());}
    @Test void straightStairsHaveDistinctStepsSupportsAndLevelLanding() {var c=cells(generate("{\"kind\":\"stairs\",\"width\":2,\"steps\":5,\"landing\":3}"));for(int i=0;i<5;i++)assertEquals("$stairs[facing=south,half=bottom,shape=straight]",c.get(new P(1,i,i)));assertEquals("$full",c.get(new P(1,0,4)));assertEquals("$full",c.get(new P(1,4,7)));connected(c.keySet());}
    @Test void unsupportedStairsOmitPlinths() {var c=cells(generate("{\"kind\":\"stairs\",\"steps\":5,\"supports\":false}"));assertNull(c.get(new P(0,0,4)));assertNotNull(c.get(new P(0,4,4)));}
    @Test void switchbackPreservesLastStairAndBothLandings() {var c=cells(generate("{\"kind\":\"stairs\",\"style\":\"switchback\",\"width\":2,\"steps\":5,\"landing\":2,\"gap\":1}"));assertEquals("$stairs[facing=south,half=bottom,shape=straight]",c.get(new P(0,4,6)));assertEquals("$full",c.get(new P(2,4,7)));assertEquals("$stairs[facing=north,half=bottom,shape=straight]",c.get(new P(3,5,6)));assertEquals("$stairs[facing=north,half=bottom,shape=straight]",c.get(new P(3,9,2)));assertEquals("$full",c.get(new P(3,9,1)));assertNull(c.get(new P(2,7,4)));connected(c.keySet());}
    @Test void singleStepFlightsAreValid() {for(String style:new String[]{"straight","switchback"})cells(generate("{\"kind\":\"stairs\",\"style\":\""+style+"\",\"steps\":1}"));}
    @Test void materialsAreLiteralPaletteRoles() {var g=generate("{\"kind\":\"roof\",\"materials\":{\"stairs\":\"minecraft:stone_brick_stairs[waterlogged=false]\",\"full\":\"minecraft:stone_bricks\"}}");assertEquals("minecraft:stone_bricks",g.document().getAsJsonObject("palette").get("full").getAsString());assertTrue(g.document().toString().contains("$stairs[facing="));}
    @Test void unknownAndInapplicableParametersReject() {for(String s:new String[]{"{\"kind\":\"bridge\"}","{\"kind\":\"roof\",\"unknown\":1}","{\"kind\":\"roof\",\"style\":\"hip\",\"gableInfill\":true}","{\"kind\":\"arch\",\"style\":\"round\",\"rise\":3}","{\"kind\":\"tower\",\"battlements\":false,\"merlons\":8}","{\"kind\":\"stairs\",\"gap\":1}","{\"kind\":\"roof\",\"materials\":{\"unknown\":\"minecraft:stone\"}}"})assertThrows(RuntimeException.class,()->generate(s));}
    @Test void invalidDimensionsAndTypesReject() {for(String s:new String[]{"{\"kind\":\"arch\",\"width\":8}","{\"kind\":\"arch\",\"width\":3,\"thickness\":2}","{\"kind\":\"arch\",\"width\":9,\"style\":\"pointed\",\"rise\":20}","{\"kind\":\"tower\",\"diameter\":4}","{\"kind\":\"tower\",\"height\":2147483648}","{\"kind\":\"stairs\",\"steps\":2.5}","{\"kind\":\"roof\",\"depth\":\"9\"}","{\"kind\":\"stairs\",\"supports\":\"yes\"}"})assertThrows(RuntimeException.class,()->generate(s));}
    @Test void operationBudgetsRejectBeforeReturningDocument() {assertThrows(RuntimeException.class,()->generate("{\"kind\":\"stairs\",\"style\":\"switchback\",\"width\":16,\"steps\":64,\"landing\":16,\"gap\":8}"));}
}
