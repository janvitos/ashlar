// SPDX-License-Identifier: AGPL-3.0-or-later
package cc.wujm.ashlar.tool.mc;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import cc.wujm.ashlar.tool.ToolArgError;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Argument parsing tests for {@link McCommand}, mirroring mc-command.ts's zod schema. */
class McCommandTest {

    private static JsonObject obj(String json) {
        return JsonParser.parseString(json).getAsJsonObject();
    }

    @Test
    void validCommandParses() {
        McCommand.Args a = McCommand.Args.parse(obj("{\"command\":\"weather clear\"}"));
        assertEquals("weather clear", a.command());
    }

    @Test
    void missingCommandThrows() {
        ToolArgError e = assertThrows(ToolArgError.class, () -> McCommand.Args.parse(obj("{}")));
        assertEquals("command: required, must be a string", e.getMessage());
    }

    @Test
    void emptyCommandThrows() {
        ToolArgError e = assertThrows(ToolArgError.class, () -> McCommand.Args.parse(obj("{\"command\":\"\"}")));
        assertEquals("command: must contain at least 1 character(s)", e.getMessage());
    }

    @Test
    void nonStringCommandThrows() {
        assertThrows(ToolArgError.class, () -> McCommand.Args.parse(obj("{\"command\":123}")));
    }

    @Test
    void commandListParsesAndRejectsBadInput() {
        List<McCommand.Args> batch = McCommand.parseBatch(obj("{\"commands\":[\"weather clear\",\"/time set day\"],\"vanilla\":true}"));
        assertEquals(2, batch.size());
        assertEquals("/time set day", batch.get(1).command());
        assertTrue(batch.get(0).vanilla());
        for (String bad : List.of("{\"commands\":[]}", "{\"commands\":\"weather clear\"}", "{\"commands\":[\"\"]}", "{\"commands\":[1]}",
                "{\"commands\":[\"weather clear\"],\"command\":\"weather clear\"}")) {
            assertThrows(ToolArgError.class, () -> McCommand.parseBatch(obj(bad)), bad);
        }
        JsonObject tooMany = obj("{\"commands\":[]}");
        for (int i = 0; i <= McCommand.MAX_COMMANDS; i++) tooMany.getAsJsonArray("commands").add("weather clear");
        assertThrows(ToolArgError.class, () -> McCommand.parseBatch(tooMany));
    }
}
