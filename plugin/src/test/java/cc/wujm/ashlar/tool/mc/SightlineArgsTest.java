// SPDX-License-Identifier: AGPL-3.0-or-later
package cc.wujm.ashlar.tool.mc;

import cc.wujm.ashlar.tool.ToolArgError;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class SightlineArgsTest {

    private static JsonObject json(String s) {
        return JsonParser.parseString(s).getAsJsonObject();
    }

    @Test
    void sightlineNeedsOneEyeAndOneMode() {
        var a = McSightline.Args.parse(json("{\"eye\":[0.5,65.62,0.5],\"targets\":[[10,65,0]],\"ignore\":[\"*_leaves\"]}"));
        assertEquals(1, a.targets().size());
        assertEquals(1, a.ignore().size());
        assertThrows(ToolArgError.class, () -> McSightline.Args.parse(json("{\"targets\":[[1,2,3]]}")));
        assertThrows(ToolArgError.class, () -> McSightline.Args.parse(json("{\"eye\":[0,64,0],\"player\":\"Steve\",\"targets\":[[1,2,3]]}")));
        assertThrows(ToolArgError.class, () -> McSightline.Args.parse(json("{\"eye\":[0,64,0]}")));
        assertThrows(ToolArgError.class, () -> McSightline.Args.parse(json("{\"eye\":[0,64,0],\"targets\":[[1,2,3]],\"cone\":{\"yaw\":0,\"pitch\":0}}")));
        assertThrows(ToolArgError.class, () -> McSightline.Args.parse(json("{\"eye\":[0,64,0],\"targets\":[[1.5,2,3]]}")));
        assertThrows(ToolArgError.class, () -> McSightline.Args.parse(json("{\"eye\":[0,64,0],\"targets\":[[300,64,0]]}")), "beyond 256 blocks");
        assertThrows(ToolArgError.class, () -> McSightline.Args.parse(json("{\"eye\":[0,64,0],\"targets\":[[1,2,3]],\"ignore\":[\"*\"]}")));
        assertThrows(ToolArgError.class, () -> McSightline.Args.parse(json("{\"player\":\"Steve\",\"world\":\"world\",\"targets\":[[1,2,3]]}")));
    }

    @Test
    void coneDefaultsAndBounds() {
        var c = McSightline.Args.parse(json("{\"player\":\"Steve\",\"cone\":{}}")).cone();
        assertNull(c.yaw());
        assertEquals(24, c.rays());
        assertEquals(128, c.distance());
        assertThrows(ToolArgError.class, () -> McSightline.Args.parse(json("{\"eye\":[0,64,0],\"cone\":{\"yaw\":0}}")), "pitch required with eye");
        assertThrows(ToolArgError.class, () -> McSightline.Args.parse(json("{\"player\":\"Steve\",\"cone\":{\"rays\":100}}")));
        assertThrows(ToolArgError.class, () -> McSightline.Args.parse(json("{\"player\":\"Steve\",\"cone\":{\"spread\":3}}")));
    }

    @Test
    void firstPersonRenderArgs() {
        var a = McRender.FirstPersonArgs.parse(json("{\"view\":\"first-person\",\"eye\":[0,65.62,0],\"yaw\":-90,\"pitch\":10}"));
        assertEquals(70, a.fov());
        assertEquals(128, a.distance());
        assertEquals(960, a.width());
        assertThrows(ToolArgError.class, () -> McRender.FirstPersonArgs.parse(json("{\"view\":\"first-person\",\"eye\":[0,65,0]}")), "yaw/pitch required with eye");
        assertThrows(ToolArgError.class, () -> McRender.FirstPersonArgs.parse(json("{\"view\":\"first-person\",\"player\":\"Steve\",\"from\":[0,0,0]}")));
        assertThrows(ToolArgError.class, () -> McRender.FirstPersonArgs.parse(json("{\"view\":\"first-person\",\"player\":\"Steve\",\"distance\":400}")));
        assertThrows(ToolArgError.class, () -> McRender.FirstPersonArgs.parse(json("{\"view\":\"first-person\",\"player\":\"Steve\",\"width\":1920,\"height\":1080}")));
        assertDoesNotThrow(() -> McRender.FirstPersonArgs.parse(json("{\"view\":\"first-person\",\"player\":\"Steve\",\"pitch\":30}")));
    }

    @Test
    void playersLookRange() {
        assertEquals(64, McPlayers.params(json("{\"lookRange\":64}")).get("lookRange").getAsInt());
        assertFalse(McPlayers.params(json("{}")).has("lookRange"));
        assertThrows(ToolArgError.class, () -> McPlayers.params(json("{\"lookRange\":8}")));
        assertThrows(ToolArgError.class, () -> McPlayers.params(json("{\"lookRange\":300}")));
        assertThrows(ToolArgError.class, () -> McPlayers.params(json("{\"range\":20}")));
    }
}
