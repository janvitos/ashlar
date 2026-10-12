// SPDX-License-Identifier: AGPL-3.0-or-later
package cc.wujm.ashlar.tool.mc;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import cc.wujm.ashlar.config.PluginConfig;
import cc.wujm.ashlar.tool.ToolArgError;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Argument parsing tests for {@link McCommand}, mirroring mc-command.ts's zod schema. */
class McCommandTest {

    private static final PluginConfig.RunCommandConfig OFF = new PluginConfig.RunCommandConfig(true);

    private static JsonObject obj(String json) {
        return JsonParser.parseString(json).getAsJsonObject();
    }

    @Test
    void validCommandParses() {
        McCommand.Args a = McCommand.Args.parse(obj("{\"command\":\"weather clear\"}"), OFF);
        assertEquals("weather clear", a.command());
    }

    @Test
    void locateIsRefusedInEveryForm() {
        for (String c : List.of("locate biome minecraft:desert", "/locate structure village", "minecraft:locate biome desert",
                "LOCATE biome desert", "execute positioned 0 0 0 run locate biome minecraft:desert",
                "execute in minecraft:overworld run minecraft:locate poi bed")) {
            ToolArgError e = assertThrows(ToolArgError.class, () -> McCommand.Args.parse(obj("{\"command\":\"" + c + "\"}"), OFF), c);
            assertTrue(e.getMessage().startsWith("locate is disabled"), e.getMessage());
        }
        ToolArgError e = assertThrows(ToolArgError.class,
                () -> McCommand.parseBatch(obj("{\"commands\":[\"time set day\",\"locate biome minecraft:desert\"]}"), OFF));
        assertTrue(e.getMessage().startsWith("commands[1]: locate is disabled"), e.getMessage());
        assertEquals("say run locate", McCommand.Args.parse(obj("{\"command\":\"say run locate\"}"), OFF).command());
        assertEquals("say locate the desert", McCommand.Args.parse(obj("{\"command\":\"say locate the desert\"}"), OFF).command());
        assertEquals("tellraw @a \"run locate\"", McCommand.Args.parse(obj("{\"command\":\"tellraw @a \\\"run locate\\\"\"}"), OFF).command());
    }

    @Test
    void saveCommandsAreRefusedInEveryForm() {
        for (String c : List.of("save-all", "save-all flush", "/save-all", "minecraft:save-all flush", "SAVE-ON", "save-off",
                "execute in minecraft:overworld run save-all flush")) {
            ToolArgError e = assertThrows(ToolArgError.class, () -> McCommand.Args.parse(obj("{\"command\":\"" + c + "\"}"), OFF), c);
            assertTrue(e.getMessage().contains("is disabled on this server: it changes saving for every loaded world"), e.getMessage());
        }
        ToolArgError e = assertThrows(ToolArgError.class,
                () -> McCommand.parseBatch(obj("{\"commands\":[\"time set day\",\"save-all flush\"]}"), OFF));
        assertTrue(e.getMessage().startsWith("commands[1]: save-all is disabled on this server"), e.getMessage());
        assertEquals("say save-all later", McCommand.Args.parse(obj("{\"command\":\"say save-all later\"}"), OFF).command());
        assertEquals("save-query", McCommand.Args.parse(obj("{\"command\":\"save-query\"}"), OFF).command());
    }

    @Test
    void onlyTheConfigLetsBlockedCommandsThrough() {
        PluginConfig.RunCommandConfig save = new PluginConfig.RunCommandConfig(true, false, true);
        PluginConfig.RunCommandConfig both = new PluginConfig.RunCommandConfig(true, true, true);
        assertEquals("save-all flush", McCommand.Args.parse(obj("{\"command\":\"save-all flush\"}"), save).command());
        assertEquals(2, McCommand.parseBatch(obj("{\"commands\":[\"save-off\",\"save-on\"]}"), save).size());
        assertEquals("locate structure village", McCommand.Args.parse(obj("{\"command\":\"locate structure village\"}"), both).command());
        ToolArgError e = assertThrows(ToolArgError.class,
                () -> McCommand.parseBatch(obj("{\"commands\":[\"save-all\",\"locate biome desert\"]}"), save));
        assertTrue(e.getMessage().startsWith("commands[1]: locate is disabled"), e.getMessage());
        // A caller cannot turn them on, and the refusal does not say how.
        e = assertThrows(ToolArgError.class,
                () -> McCommand.Args.parse(obj("{\"command\":\"locate biome desert\",\"allowLocate\":true}"), OFF));
        assertFalse(e.getMessage().contains("allow"), e.getMessage());
        e = assertThrows(ToolArgError.class, () -> McCommand.Args.parse(obj("{\"command\":\"save-all\",\"allowSave\":true}"), OFF));
        assertFalse(e.getMessage().contains("allow"), e.getMessage());
    }

    @Test
    void missingCommandThrows() {
        ToolArgError e = assertThrows(ToolArgError.class, () -> McCommand.Args.parse(obj("{}"), OFF));
        assertEquals("command: required, must be a string", e.getMessage());
    }

    @Test
    void emptyCommandThrows() {
        ToolArgError e = assertThrows(ToolArgError.class, () -> McCommand.Args.parse(obj("{\"command\":\"\"}"), OFF));
        assertEquals("command: must contain at least 1 character(s)", e.getMessage());
    }

    @Test
    void nonStringCommandThrows() {
        assertThrows(ToolArgError.class, () -> McCommand.Args.parse(obj("{\"command\":123}"), OFF));
    }

    @Test
    void commandListParsesAndRejectsBadInput() {
        List<McCommand.Args> batch = McCommand.parseBatch(obj("{\"commands\":[\"weather clear\",\"/time set day\"],\"vanilla\":true}"), OFF);
        assertEquals(2, batch.size());
        assertEquals("/time set day", batch.get(1).command());
        assertTrue(batch.get(0).vanilla());
        for (String bad : List.of("{\"commands\":[]}", "{\"commands\":\"weather clear\"}", "{\"commands\":[\"\"]}", "{\"commands\":[1]}",
                "{\"commands\":[\"weather clear\"],\"command\":\"weather clear\"}")) {
            assertThrows(ToolArgError.class, () -> McCommand.parseBatch(obj(bad), OFF), bad);
        }
        JsonObject tooMany = obj("{\"commands\":[]}");
        for (int i = 0; i <= McCommand.MAX_COMMANDS; i++) tooMany.getAsJsonArray("commands").add("weather clear");
        assertThrows(ToolArgError.class, () -> McCommand.parseBatch(tooMany, OFF));
    }
}
