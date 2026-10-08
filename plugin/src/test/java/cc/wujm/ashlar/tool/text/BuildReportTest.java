// SPDX-License-Identifier: AGPL-3.0-or-later
package cc.wujm.ashlar.tool.text;

import org.junit.jupiter.api.Test;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

class BuildReportTest {
    @Test
    void compactTotalsDistinguishUnchangedOrSkippedFromChanges() {
        assertEquals(List.of("Fills: 7/10 changed; 3 unchanged/skipped in 2ms"),
                ToolText.compactSection("Fills", 7, 10, 2));
        assertEquals(List.of("Blocks: 0/1 changed; 1 unchanged/skipped in 0ms"),
                ToolText.compactSection("Blocks", 0, 1, 0));
    }

    @Test
    void detailedReportRetainsOperationCoordinates() {
        var lines = ToolText.fillsSection(List.of(new ToolText.FillOpLine(0,
                new int[]{1,2,3}, new int[]{1,2,3}, "minecraft:stone", 1, 1)), 1, 1, 0);
        assertTrue(String.join("\n", lines).contains("#0 [1,2,3]"));
        assertEquals(1, ToolText.compactSection("Fills", 1, 1, 0).size());
    }

    @Test
    void compactCompositionRetainsSnapshotAndWarningTruncation() {
        var warnings = List.of(new WarningText.SupportWarning(1, 2, 3, "minecraft:torch", "unsupported"));
        String report = ToolText.buildResultText("Snapshot: saved-id",
                ToolText.compactSection("Fills", 1, 1, 0), null, null, 0, warnings, true);
        assertTrue(report.contains("saved-id"));
        assertTrue(report.contains("Fills: 1/1"));
        assertTrue(report.contains("1,2,3"));
        assertTrue(report.contains("unsupported"));
        assertTrue(report.contains("warnings capped"));
    }
}
