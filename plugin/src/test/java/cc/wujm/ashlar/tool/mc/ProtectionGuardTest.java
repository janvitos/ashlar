// SPDX-License-Identifier: AGPL-3.0-or-later
package cc.wujm.ashlar.tool.mc;

import cc.wujm.ashlar.engine.Region;
import cc.wujm.ashlar.protect.ProtectedRegion;
import cc.wujm.ashlar.protect.ProtectedRegions;
import cc.wujm.ashlar.rpc.InvocationContext;
import cc.wujm.ashlar.tool.ToolArgError;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.*;

class ProtectionGuardTest {
    @TempDir Path dir;
    private ProtectionGuard guard;

    @BeforeEach
    void setUp() throws IOException {
        ProtectedRegions regions = new ProtectedRegions(dir.resolve("protected.json"));
        regions.add("dock", "world", new Region(0, 60, 0, 9, 69, 9), ProtectedRegion.Mode.DENY, "user's hand-built dock", "PLAYER:alice");
        regions.add("berg", "world", new Region(20, 60, 0, 29, 69, 9), ProtectedRegion.Mode.WARN, null, "PLAYER:alice");
        guard = new ProtectionGuard(regions, null, null, Logger.getLogger("test"));
    }

    private static JsonObject json(String s) {
        return JsonParser.parseString(s).getAsJsonObject();
    }

    private static McBuild.Args build(String s) {
        return McBuild.Args.parse(json(s));
    }

    @Test
    void overrideMustNameEachRegionExplicitly() {
        assertEquals(List.of(), ProtectionGuard.parseOverride(json("{}")));
        assertEquals(List.of("dock", "berg"), ProtectionGuard.parseOverride(json("{\"override\":[\"dock\",\"berg\"]}")));
        assertThrows(ToolArgError.class, () -> ProtectionGuard.parseOverride(json("{\"override\":[\"*\"]}")));
        assertThrows(ToolArgError.class, () -> ProtectionGuard.parseOverride(json("{\"override\":[\"dock\",\"dock\"]}")));
        assertThrows(ToolArgError.class, () -> ProtectionGuard.parseOverride(json("{\"override\":\"dock\"}")));
        assertThrows(ToolArgError.class, () -> guard.evaluate("world", List.of("pier"), c -> { }));
    }

    @Test
    void denyRejectsBeforeWritingAndNamesTheRegion() {
        var a = build("{\"fills\":[{\"from\":[5,65,5],\"to\":[25,65,5],\"block\":\"stone\"}]}");
        var e = assertThrows(ToolArgError.class,
                () -> guard.enforce(InvocationContext.system("t"), "mc_build", "world", List.of(), ProtectionGuard.targets(a)));
        assertTrue(e.getMessage().contains("'dock' (deny) covers 5 target cells"), e.getMessage());
        assertTrue(e.getMessage().contains("[5,65,5]..[9,65,5]"), e.getMessage());
        assertTrue(e.getMessage().contains("override:[\"dock\"]"), e.getMessage());
        assertTrue(e.getMessage().contains("user's hand-built dock"), e.getMessage());
    }

    @Test
    void overrideAndWarnProceedWithLines() {
        var a = build("{\"fills\":[{\"from\":[5,65,5],\"to\":[25,65,5],\"block\":\"stone\"}],\"blocks\":[{\"pos\":[1,61,1],\"block\":\"stone\"}]}");
        List<String> lines = guard.enforce(InvocationContext.system("t"), "mc_build", "world", List.of("dock"), ProtectionGuard.targets(a));
        assertEquals(2, lines.size());
        assertTrue(lines.get(0).startsWith("Protection warning: region 'berg' (warn) covers 6 target cells"), lines.get(0));
        assertTrue(lines.get(1).startsWith("Protection override: region 'dock' (deny) - 6 target cells"), lines.get(1));
    }

    @Test
    void otherWorldsAndHollowInteriorsAreUntouched() {
        var a = build("{\"fills\":[{\"from\":[5,65,5],\"to\":[25,65,5],\"block\":\"stone\"}]}");
        assertFalse(guard.evaluate("world_nether", List.of(), ProtectionGuard.targets(a)).any());
        var room = build("{\"fills\":[{\"from\":[-1,59,-1],\"to\":[10,70,10],\"block\":\"stone\",\"mode\":\"walls\"}]}");
        // The walls run just outside the dock, so the dock (inside the room) is not a target.
        assertFalse(guard.evaluate("world", List.of(), ProtectionGuard.targets(room)).any());
    }

    @Test
    void planningReportListsEveryOverlapWithStatus() {
        var a = build("{\"fills\":[{\"from\":[5,65,5],\"to\":[25,65,5],\"block\":\"stone\"}]}");
        JsonObject report = new JsonObject();
        guard.evaluate("world", List.of(), ProtectionGuard.targets(a)).addTo(report);
        assertFalse(report.get("protectedPass").getAsBoolean());
        assertEquals(2, report.getAsJsonArray("protected").size());
        assertEquals("denied", report.getAsJsonArray("protected").get(0).getAsJsonObject().get("status").getAsString());
        JsonObject clean = new JsonObject();
        guard.evaluate("world", List.of(), ProtectionGuard.targets(build("{\"blocks\":[{\"pos\":[50,65,50],\"block\":\"stone\"}]}"))).addTo(clean);
        assertFalse(clean.has("protected"));
    }

    @Test
    void protectAddArgumentsAreValidated() {
        var a = McProtect.AddArgs.parse(json("{\"action\":\"add\",\"name\":\"dock\",\"from\":[0,0,0],\"to\":[1,1,1]}"));
        assertEquals(ProtectedRegion.Mode.DENY, a.mode());
        assertThrows(ToolArgError.class, () -> McProtect.AddArgs.parse(json("{\"name\":\"Dock\",\"from\":[0,0,0],\"to\":[1,1,1]}")));
        assertThrows(ToolArgError.class, () -> McProtect.AddArgs.parse(json("{\"name\":\"dock\",\"from\":[0,0],\"to\":[1,1,1]}")));
        assertThrows(ToolArgError.class, () -> McProtect.AddArgs.parse(json("{\"name\":\"dock\",\"from\":[0,0,0],\"to\":[1,1,1],\"mode\":\"block\"}")));
        assertThrows(ToolArgError.class, () -> McProtect.AddArgs.parse(json("{\"name\":\"dock\",\"from\":[0,0,0],\"to\":[1,1,1],\"note\":\"" + "x".repeat(201) + "\"}")));
        assertThrows(ToolArgError.class, () -> McProtect.allowedKeys(json("{\"action\":\"list\",\"name\":\"dock\"}"), java.util.Set.of("action", "world", "touches"), "list"));
    }
}
