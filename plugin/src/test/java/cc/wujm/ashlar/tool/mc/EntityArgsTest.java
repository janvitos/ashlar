// SPDX-License-Identifier: AGPL-3.0-or-later
package cc.wujm.ashlar.tool.mc;

import cc.wujm.ashlar.engine.EntitySpec;
import cc.wujm.ashlar.engine.Region;
import cc.wujm.ashlar.tool.ToolArgError;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/** Step 17: {@code mc_build entities} parsing, validation and transforms. */
class EntityArgsTest {

    private static JsonObject json(String s) {
        return JsonParser.parseString(s).getAsJsonObject();
    }

    private static String error(String s) {
        return assertThrows(ToolArgError.class, () -> EntityArgs.parse(json(s))).getMessage();
    }

    @Test void absentWithoutEntityKeys() {
        assertNull(EntityArgs.parse(json("{\"fills\":[]}")));
    }

    @Test void parsesEachType() {
        EntityArgs a = EntityArgs.parse(json("""
                {"entities":[
                  {"type":"painting","pos":[1,64,2],"facing":"south","variant":"kebab"},
                  {"type":"glow_item_frame","pos":[3,64,2],"facing":"up","item":"clock","rotation":2,"fixed":false},
                  {"type":"armor_stand","pos":[5,64,2],"yaw":90,"equipment":{"head":"iron_helmet"},"pose":{"rightArm":[-90,0,0]},"small":true}
                ]}"""));
        assertNull(a.replaceBox());
        EntitySpec p = a.entities().get(0), f = a.entities().get(1), s = a.entities().get(2);
        assertEquals("minecraft:kebab", p.variant());
        assertEquals("south", p.facing());
        assertFalse(p.fromRight());
        assertEquals("minecraft:clock", f.item());
        assertEquals(2, f.rotation());
        assertFalse(f.fixed());
        assertEquals("up", f.facing());
        assertEquals(90f, s.yaw());
        assertEquals("minecraft:iron_helmet", s.equipment().get("head"));
        assertArrayEquals(new double[]{-90, 0, 0}, s.pose().get("rightArm"));
        assertTrue(s.small());
        assertTrue(EntityArgs.parse(json("{\"entities\":[{\"type\":\"item_frame\",\"pos\":[0,0,0],\"facing\":\"north\"}]}")).entities().getFirst().fixed());
    }

    @Test void rejectsBadEntries() {
        assertTrue(error("{\"entities\":[]}").contains("1-256"));
        assertTrue(error("{\"entities\":[{\"type\":\"painting\",\"pos\":[0,0,0],\"variant\":\"kebab\"}]}").contains("facing"));
        assertTrue(error("{\"entities\":[{\"type\":\"painting\",\"pos\":[0,0,0],\"facing\":\"up\",\"variant\":\"kebab\"}]}").contains("facing"));
        assertTrue(error("{\"entities\":[{\"type\":\"painting\",\"pos\":[0,0,0],\"facing\":\"south\",\"variant\":\"kebab\",\"item\":\"x\"}]}")
                .contains("not a painting parameter"));
        assertTrue(error("{\"entities\":[{\"type\":\"item_frame\",\"pos\":[0,0,0],\"facing\":\"south\",\"rotation\":1.5}]}").contains("integer"));
        assertTrue(error("{\"entities\":[{\"type\":\"item_frame\",\"pos\":[0,0,0],\"facing\":\"south\",\"rotation\":8}]}").contains("between"));
        assertTrue(error("{\"entities\":[{\"type\":\"item_frame\",\"pos\":[0,0,0],\"facing\":\"south\",\"item\":\"Bad Item\"}]}").contains("not an id"));
        assertTrue(error("{\"entities\":[{\"type\":\"armor_stand\",\"pos\":[0,0,0],\"equipment\":{\"hat\":\"x\"}}]}").contains("slots"));
        assertTrue(error("{\"entities\":[{\"type\":\"armor_stand\",\"pos\":[0,0,0],\"pose\":{\"head\":[1,2]}}]}").contains("[x, y, z]"));
        assertTrue(error("{\"entities\":[{\"type\":\"armor_stand\",\"pos\":[0,0,0],\"yaw\":400}]}").contains("between"));
        assertTrue(error("{\"entities\":[{\"type\":\"zombie\",\"pos\":[0,0,0]}]}").contains("type"));
    }

    @Test void replaceModeNeedsItsBox() {
        String e = "\"entities\":[{\"type\":\"armor_stand\",\"pos\":[0,64,0]}]";
        assertTrue(error("{" + e + ",\"entityMode\":\"replace-in-box\"}").contains("entityBox"));
        assertTrue(error("{" + e + ",\"entityBox\":{\"from\":[0,0,0],\"to\":[1,1,1]}}").contains("only with"));
        EntityArgs a = EntityArgs.parse(json("{" + e + ",\"entityMode\":\"replace-in-box\",\"entityBox\":{\"from\":[4,70,4],\"to\":[-4,60,-4]}}"));
        assertEquals(new Region(-4, 60, -4, 4, 70, 4), a.replaceBox());
        assertEquals(new Region(-4, 60, -4, 4, 70, 4), a.bounds());
    }

    @Test void transformMovesAndTurns() {
        EntityArgs a = EntityArgs.parse(json("""
                {"transform":{"origin":[100,64,100],"rotation":90},"entities":[
                  {"type":"painting","pos":[1,0,0],"facing":"south","variant":"minecraft:kebab"},
                  {"type":"item_frame","pos":[0,1,0],"facing":"up"},
                  {"type":"armor_stand","pos":[0,0,2],"yaw":0}
                ]}"""));
        EntitySpec p = a.entities().get(0), f = a.entities().get(1), s = a.entities().get(2);
        assertArrayEquals(new int[]{100, 64, 101}, p.cell());
        assertEquals("west", p.facing());
        assertFalse(p.fromRight());
        assertEquals("up", f.facing());
        assertArrayEquals(new int[]{98, 64, 100}, s.cell());
        assertEquals(90f, s.yaw(), 1e-4);

        EntityArgs m = EntityArgs.parse(json("""
                {"transform":{"origin":[0,0,0],"mirror":"x"},"entities":[
                  {"type":"painting","pos":[3,0,0],"facing":"south","variant":"kebab"}]}"""));
        assertTrue(m.entities().getFirst().fromRight());
        assertArrayEquals(new int[]{-3, 0, 0}, m.entities().getFirst().cell());
        assertEquals("south", m.entities().getFirst().facing());
    }

    @Test void boundsPadPaintings() {
        EntityArgs a = EntityArgs.parse(json("{\"entities\":[{\"type\":\"painting\",\"pos\":[0,64,0],\"facing\":\"south\",\"variant\":\"kebab\"}]}"));
        assertEquals(new Region(-4, 60, -4, 4, 68, 4), a.bounds());
        assertEquals(1, a.cells().size());
    }
}
