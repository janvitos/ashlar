// SPDX-License-Identifier: AGPL-3.0-or-later
package cc.wujm.ashlar.tool.mc;

import cc.wujm.ashlar.tool.ToolArgError;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/** Step 19 command-shadow detection against a fake command map. */
class CommandShadowTest {

    /** label -> owner ("" server/vanilla); EssentialsX has taken over tp and give, time is vanilla, warp is plugin-only. */
    private static final Map<String, String> MAP = Map.of("tp", "Essentials", "minecraft:tp", "", "give", "Essentials", "minecraft:give", "",
            "time", "", "minecraft:time", "", "warp", "Essentials");

    private static final CommandShadow.Lookup FAKE = new CommandShadow.Lookup() {
        @Override public String owner(String label) { return MAP.get(label); }
        @Override public boolean exists(String label) { return MAP.containsKey(label); }
    };

    private static McCommand.Routed route(String json) {
        return McCommand.route(McCommand.Args.parse(JsonParser.parseString(json).getAsJsonObject()), FAKE);
    }

    @Test void detectsPluginOverridesOnly() {
        assertTrue(CommandShadow.check("tp Steve 0 64 0", FAKE).shadows());
        assertTrue(CommandShadow.check("  TP   Steve", FAKE).shadows());
        assertFalse(CommandShadow.check("time set day", FAKE).shadows());
        assertFalse(CommandShadow.check("warp spawn", FAKE).shadows());
        assertNull(CommandShadow.check("minecraft:tp Steve 0 64 0", FAKE));
        assertNull(CommandShadow.check("essentials:tp Steve", FAKE));
        assertNull(CommandShadow.check("nosuch arg", FAKE));
    }

    @Test void rewriteKeepsArguments() {
        assertEquals("minecraft:tp Steve 0 64 0", CommandShadow.vanilla("tp Steve 0 64 0", "tp"));
        assertEquals("minecraft:time", CommandShadow.vanilla("TIME", "time"));
    }

    @Test void routeWarnsWithoutVanilla() {
        var r = route("{\"command\":\"/tp Steve 0 64 0\"}");
        assertEquals("tp Steve 0 64 0", r.command());
        assertTrue(r.note().contains("Essentials's command here") && r.note().contains("vanilla:true"), r.note());
        assertNull(route("{\"command\":\"time set day\"}").note());
        assertNull(route("{\"command\":\"warp spawn\"}").note());
    }

    @Test void routeRewritesWithVanilla() {
        var r = route("{\"command\":\"give Steve diamond 1\",\"vanilla\":true}");
        assertEquals("minecraft:give Steve diamond 1", r.command());
        assertTrue(r.note().startsWith("Ran as minecraft:give"), r.note());
        r = route("{\"command\":\"time set day\",\"vanilla\":true}");
        assertEquals("minecraft:time set day", r.command());
        assertNull(r.note());
        assertThrows(ToolArgError.class, () -> route("{\"command\":\"warp spawn\",\"vanilla\":true}"));
        assertThrows(ToolArgError.class, () -> route("{\"command\":\"minecraft:tp a b\",\"vanilla\":true}"));
    }
}
