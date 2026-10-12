// SPDX-License-Identifier: AGPL-3.0-or-later
package cc.wujm.ashlar.tool.mc;

import cc.wujm.ashlar.engine.Region;
import cc.wujm.ashlar.journal.UndoRules;
import cc.wujm.ashlar.tool.ToolArgError;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class JournalArgsTest {
    private static JsonObject json(String s) {
        return JsonParser.parseString(s).getAsJsonObject();
    }

    @Test void restoreUndoArguments() {
        var a = McRestore.parseUndo(json("{\"journal\":\"jrn-20261010-120239-aa42\"}"));
        assertEquals(UndoRules.Mode.SAFE, a.mode());
        assertFalse(a.dryRun());
        assertEquals(20, a.samples());
        var f = McRestore.parseUndo(json("{\"journal\":\"jrn-20261010-120239-aa42\",\"mode\":\"force\",\"dryRun\":true,\"allowBlockEntityReplacement\":true,\"samples\":0}"));
        assertEquals(UndoRules.Mode.FORCE, f.mode());
        assertTrue(f.dryRun() && f.allowBlockEntityReplacement());
        assertThrows(ToolArgError.class, () -> McRestore.parseUndo(json("{\"journal\":\"snap-1\"}")));
        assertThrows(ToolArgError.class, () -> McRestore.parseUndo(json("{\"journal\":\"jrn-20261010-120239-aa42\",\"id\":\"snap-x\"}")));
        assertThrows(ToolArgError.class, () -> McRestore.parseUndo(json("{\"journal\":\"jrn-20261010-120239-aa42\",\"mode\":\"all\"}")));
        assertThrows(ToolArgError.class, () -> McRestore.Args.parse(json("{\"id\":\"snap-x\",\"mode\":\"force\"}")));
    }

    @Test void journalListArguments() {
        var l = McSnapshot.parseJournalList(json("{\"action\":\"journal-list\",\"since\":\"2026-10-10T12:00:00Z\",\"label\":\"berg\",\"touches\":{\"from\":[5,70,9],\"to\":[1,60,0]},\"limit\":5}"));
        assertEquals(java.time.Instant.parse("2026-10-10T12:00:00Z"), l.since());
        assertEquals(new Region(1, 60, 0, 5, 70, 9), l.touches());
        assertEquals(5, l.limit());
        assertEquals(50, McSnapshot.parseJournalList(json("{}")).limit());
        assertThrows(ToolArgError.class, () -> McSnapshot.parseJournalList(json("{\"since\":\"yesterday\"}")));
        assertThrows(ToolArgError.class, () -> McSnapshot.parseJournalList(json("{\"touches\":{\"from\":[0,0,0]}}")));
    }

    @Test void buildJournalArguments() {
        var j = McBuild.JournalArgs.parse(json("{\"label\":\" lookout \"}"));
        assertEquals("lookout", j.label());
        assertTrue(j.journal());
        assertFalse(McBuild.JournalArgs.parse(json("{\"journal\":false}")).journal());
        assertThrows(ToolArgError.class, () -> McBuild.JournalArgs.parse(json("{\"label\":\"" + "x".repeat(101) + "\"}")));
    }

    @Test void journalledBuildsLargerThanOneEntryAreRefusedUpFront() {
        var c = cc.wujm.ashlar.config.PluginConfig.JournalConfig.DEFAULT;
        assertEquals(4_000_000, c.maxCellsPerEntry());
        var on = McBuild.JournalArgs.parse(json("{}"));
        on.requireFits(c, 4_000_000, "this build");
        var e = assertThrows(ToolArgError.class, () -> on.requireFits(c, 4_000_001, "this cells build"));
        assertTrue(e.getMessage().startsWith("this cells build writes up to 4,000,001 cells"), e.getMessage());
        assertTrue(e.getMessage().contains("Nothing was written") && e.getMessage().contains("journal:false"), e.getMessage());
        // Without a journal there is nothing to fit: journal:false, or journalling disabled in config.
        McBuild.JournalArgs.parse(json("{\"journal\":false}")).requireFits(c, 9_000_000, "this build");
        on.requireFits(new cc.wujm.ashlar.config.PluginConfig.JournalConfig(false, 200, 40_000_000, 30, 4_000_000), 9_000_000, "this build");
    }
}
