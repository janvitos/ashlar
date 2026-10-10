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
        List<Region> tiles = McWalk.tiles(box, 200_000);
        long sum = 0;
        for (Region t : tiles) {
            assertTrue(t.volume() <= 200_000);
            sum += t.volume();
        }
        assertEquals(box.volume(), sum);
        assertTrue(tiles.size() >= 4);
        assertThrows(ToolArgError.class, () -> McWalk.tiles(new Region(0, 0, 0, 0, 300, 0), 100));
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
        McWalk.Cells cells = new McWalk.Cells(ids, palette, "world");
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
}
