// SPDX-License-Identifier: AGPL-3.0-or-later
package cc.wujm.ashlar.tool.mc;

import cc.wujm.ashlar.engine.Region;
import cc.wujm.ashlar.tool.ToolArgError;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class WalkArgsTest {

    private static JsonObject json(String s) {
        return JsonParser.parseString(s).getAsJsonObject();
    }

    private static final String BOX = "\"box\":{\"from\":[0,60,0],\"to\":[20,70,20]}";

    @Test
    void modesAndTargets() {
        var p = McWalk.Args.parse(json("{\"from\":[1,64,1],\"to\":[5,64,5]," + BOX + "}"));
        assertEquals("path", p.mode());
        assertFalse(p.rules().jump());
        var r = McWalk.Args.parse(json("{\"from\":[1,64,1]," + BOX + ",\"targets\":\"doors\",\"rules\":{\"jump\":true,\"doors\":\"any\",\"maxDrop\":0.6}}"));
        assertEquals("reach", r.mode());
        assertEquals("doors", r.targetKind());
        assertTrue(r.rules().jump());
        assertTrue(r.rules().anyDoor());
        assertEquals(.6, r.rules().maxDrop(), 1e-9);
        assertEquals(2, McWalk.Args.parse(json("{\"from\":[1,64,1]," + BOX + ",\"targets\":[[2,64,2],[3,64,3]]}")).targets().size());
        assertThrows(ToolArgError.class, () -> McWalk.Args.parse(json("{\"from\":[1,64,1],\"mode\":\"path\"," + BOX + "}")));
        assertThrows(ToolArgError.class, () -> McWalk.Args.parse(json("{\"from\":[1,64,1],\"to\":[2,64,2]," + BOX + ",\"targets\":\"doors\"}")));
        assertThrows(ToolArgError.class, () -> McWalk.Args.parse(json("{\"from\":[30,64,1]," + BOX + "}")), "from outside box");
        assertThrows(ToolArgError.class, () -> McWalk.Args.parse(json("{\"from\":[1,64,1]," + BOX + ",\"targets\":\"chests\"}")));
        assertThrows(ToolArgError.class, () -> McWalk.Args.parse(json("{\"from\":[1,64,1]," + BOX + ",\"rules\":{\"stepHeight\":2}}")));
        assertThrows(ToolArgError.class, () -> McWalk.Args.parse(json("{\"from\":[1,64,1]," + BOX + ",\"rules\":{\"sprint\":true}}")));
        assertThrows(ToolArgError.class, () -> McWalk.Args.parse(json("{\"from\":[1,64,1]," + BOX + ",\"samples\":0}")));
    }

    @Test
    void tilesCoverTheBoxWithinTheReadLimit() {
        Region box = new Region(-10, 50, 5, 189, 89, 104);
        List<Region> tiles = BoxRead.tiles(box, 200_000);
        long sum = 0;
        for (Region t : tiles) {
            assertTrue(t.volume() <= 200_000);
            sum += t.volume();
        }
        assertEquals(box.volume(), sum);
        assertTrue(tiles.size() >= 4);
        assertThrows(ToolArgError.class, () -> BoxRead.tiles(new Region(0, 0, 0, 0, 300, 0), 100));
    }

    @Test
    void runReportsPathAndUnreachableDoor() {
        // 10x10 stone floor at y=63, a wall at x=5 with a closed door at z=5.
        Region box = new Region(0, 63, 0, 9, 67, 9);
        List<String> palette = new ArrayList<>(List.of("minecraft:air", "minecraft:stone",
                "minecraft:oak_door[facing=east,half=lower,hinge=left,open=false,powered=false]",
                "minecraft:oak_door[facing=east,half=upper,hinge=left,open=false,powered=false]"));
        int[] ids = new int[10 * 5 * 10];
        for (int x = 0; x < 10; x++) for (int z = 0; z < 10; z++) ids[z * 10 + x] = 1;
        for (int z = 0; z < 10; z++) for (int y = 64; y <= 66; y++) ids[((y - 63) * 10 + z) * 10 + 5] = 1;
        ids[((64 - 63) * 10 + 5) * 10 + 5] = 2;
        ids[((65 - 63) * 10 + 5) * 10 + 5] = 3;
        BoxRead.Cells cells = new BoxRead.Cells(ids, palette, "world");
        String path = McWalk.run(McWalk.Args.parse(json("{\"from\":[0,64,0],\"to\":[4,64,9],\"box\":{\"from\":[0,63,0],\"to\":[9,67,9]}}")), cells);
        assertTrue(path.contains("reachable in 13 moves"), path);
        String blocked = McWalk.run(McWalk.Args.parse(json("{\"from\":[0,64,0],\"to\":[9,64,5],\"box\":{\"from\":[0,63,0],\"to\":[9,67,9]}}")), cells);
        assertTrue(blocked.contains("NOT reachable"), blocked);
        assertTrue(blocked.contains("closed door at 5,64,5"), blocked);
        String doors = McWalk.run(McWalk.Args.parse(json("{\"from\":[0,64,0],\"box\":{\"from\":[0,63,0],\"to\":[9,67,9]},\"targets\":\"doors\"}")), cells);
        assertTrue(doors.contains("Doors: 1 found, 0 reachable, 1 not:"), doors);
        assertTrue(doors.contains("east side unreachable"), doors);
        assertThrows(ToolArgError.class, () -> McWalk.run(McWalk.Args.parse(json("{\"from\":[0,66,0],\"box\":{\"from\":[0,63,0],\"to\":[9,67,9]}}")), cells));
    }

    /** Cells for {@code box}: a stone floor at y=63 (feet 64), plus a 3-high stone wall along x={@code wallX} when it is >= 0. */
    private static BoxRead.Cells floor(Region box, int wallX) {
        int sx = box.maxX() - box.minX() + 1, sz = box.maxZ() - box.minZ() + 1, sy = box.maxY() - box.minY() + 1;
        int[] ids = new int[sx * sy * sz];
        for (int y = box.minY(); y <= box.maxY(); y++) for (int z = box.minZ(); z <= box.maxZ(); z++) for (int x = box.minX(); x <= box.maxX(); x++) {
            boolean stone = y == 63 || x == wallX && y >= 64 && y <= 66;
            ids[((y - box.minY()) * sz + (z - box.minZ())) * sx + (x - box.minX())] = stone ? 1 : 0;
        }
        return new BoxRead.Cells(ids, List.of("minecraft:air", "minecraft:stone"), "world");
    }

    @Test
    void routeLegsGetTheirOwnBoxes() {
        var r = McWalk.Route.parse(json("{\"from\":[0,64,0],\"waypoints\":[[100,64,100],[100,70,140]]}"));
        assertEquals(6, r.margin());
        assertEquals(new Region(-6, 60, -6, 106, 68, 106), r.box(0));
        assertEquals(new Region(94, 60, 94, 106, 74, 146), r.box(1));
        r.checkLegs(800_000);
        // A 300x300 diagonal in one leg is far over the limit; the error names the leg and a midpoint.
        var far = McWalk.Route.parse(json("{\"from\":[0,64,0],\"waypoints\":[[10,64,10],[310,64,310]]}"));
        var e = assertThrows(ToolArgError.class, () -> far.checkLegs(800_000));
        assertTrue(e.getMessage().startsWith("leg 2 [10,64,10] -> [310,64,310]") && e.getMessage().contains("about [160,64,160]"), e.getMessage());
        assertThrows(ToolArgError.class, () -> McWalk.Route.parse(json("{\"from\":[0,64,0],\"waypoints\":[[1,64,1]]," + BOX + "}")));
        assertThrows(ToolArgError.class, () -> McWalk.Route.parse(json("{\"from\":[0,64,0],\"waypoints\":[]}")));
        assertThrows(ToolArgError.class, () -> McWalk.Route.parse(json("{\"from\":[0,64,0],\"waypoints\":[[1,64,1]],\"margin\":0}")));
        var missing = assertThrows(ToolArgError.class, () -> McWalk.Args.parse(json("{\"from\":[0,64,0]}")));
        assertTrue(missing.getMessage().contains("waypoints"), missing.getMessage());
    }

    @Test
    void routeLegLines() {
        var r = McWalk.Route.parse(json("{\"from\":[0,64,0],\"waypoints\":[[10,64,0],[10,64,10]],\"margin\":2}"));
        McWalk.Leg ok = McWalk.leg(r, 0, floor(r.box(0), -1000));
        assertEquals(10, ok.moves());
        assertEquals("Leg 1 [0,64,0] -> [10,64,0]: reachable in 10 moves.", ok.line());
        // A 3-high wall at x=5 across the whole leg box: the diagnosis names it.
        var walled = McWalk.Route.parse(json("{\"from\":[0,64,0],\"waypoints\":[[10,64,0]],\"margin\":2}"));
        McWalk.Leg blocked = McWalk.leg(walled, 0, floor(walled.box(0), 5));
        assertEquals(-1, blocked.moves());
        assertTrue(blocked.line().startsWith("Leg 1 [0,64,0] -> [10,64,0]: NOT reachable.") && blocked.line().contains("at 5,64,"), blocked.line());
        // With the wall on the start column the start itself is inside stone.
        McWalk.Leg badStart = McWalk.leg(walled, 0, floor(walled.box(0), 0));
        assertTrue(badStart.line().contains("the start is not a standing place"), badStart.line());
    }
}
