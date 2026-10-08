// SPDX-License-Identifier: AGPL-3.0-or-later
package cc.wujm.ashlar.tool.mc;

import cc.wujm.ashlar.rpc.RpcHandler;
import cc.wujm.ashlar.tool.ToolArgError;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static cc.wujm.ashlar.tool.mc.BlueprintCompilerTest.*;
import static org.junit.jupiter.api.Assertions.*;

class BlueprintStoreTest {
    @TempDir Path directory;

    @Test void saveGetReloadAndDeletePreserveWholeDocument() {
        var store = new BlueprintStore(directory);
        var doc = document(); var saved = store.save("windows", doc, false);
        assertEquals(64, saved.get("revision").getAsString().length());
        assertEquals(doc, store.get("windows"));
        var secondStore = new BlueprintStore(directory);
        assertEquals(doc, secondStore.get("windows"));
        assertEquals(saved, secondStore.list().getAsJsonArray("blueprints").get(0));
        secondStore.delete("windows");
        assertEquals(0, secondStore.list().getAsJsonArray("blueprints").size());
        assertThrows(ToolArgError.class, () -> store.get("windows"));
    }

    @Test void savesAndGetsDoNotExposeMutableStoredReferences() {
        var store = new BlueprintStore(directory); var doc = document();
        store.save("test", doc, false); doc.addProperty("description", "Changed externally");
        var returned = store.get("test"); returned.addProperty("description", "Changed read copy");
        assertEquals("Repeated windows", store.get("test").get("description").getAsString());
    }

    @Test void overwriteMustBeExplicitAndChangesRevision() throws Exception {
        var store = new BlueprintStore(directory); var doc = document();
        var original = store.save("test", doc, false);
        doc.addProperty("description", "New version");
        assertThrows(ToolArgError.class, () -> store.save("test", doc, false));
        assertEquals("Repeated windows", store.get("test").get("description").getAsString());
        var updated = store.save("test", doc, true);
        assertNotEquals(original.get("revision"), updated.get("revision"));
        try (var files = Files.list(directory)) { assertEquals(1, files.count()); }
    }

    @Test void invalidOrOversizedWritesLeaveExistingDocumentUntouched() {
        var store = new BlueprintStore(directory); var doc = document(); store.save("test", doc, false);
        var invalid = document(); invalid.addProperty("version", 2);
        assertThrows(ToolArgError.class, () -> store.save("test", invalid, true));
        var oversized = document(); oversized.getAsJsonObject("constraints").addProperty("large", "x".repeat(BlueprintStore.MAX_DOCUMENT_BYTES));
        assertThrows(ToolArgError.class, () -> store.save("test", oversized, true));
        assertEquals(doc, store.get("test"));
    }

    @Test void pathsAndMalformedFilesAreRejected() throws Exception {
        var store = new BlueprintStore(directory);
        for (String id : List.of("../escape", "a/b", "", "Upper", ".hidden", "a".repeat(65))) {
            assertThrows(ToolArgError.class, () -> store.save(id, document(), false));
            assertThrows(ToolArgError.class, () -> store.get(id));
            assertThrows(ToolArgError.class, () -> store.delete(id));
        }
        Files.writeString(directory.resolve("bad.json"), "not json");
        assertThrows(ToolArgError.class, () -> store.get("bad"));
        Files.delete(directory.resolve("bad.json"));
        Files.writeString(directory.resolve("bad.json"), "[]");
        assertThrows(ToolArgError.class, () -> store.get("bad"));
    }

    @Test void symlinksCannotBeReadAndAtomicOverwriteDoesNotFollowThem() throws Exception {
        var store = new BlueprintStore(directory); var outside = directory.resolve("outside.txt");
        Files.writeString(outside, "original"); Files.createSymbolicLink(directory.resolve("link.json"), outside);
        assertThrows(ToolArgError.class, () -> store.get("link"));
        store.save("link", document(), true);
        assertFalse(Files.isSymbolicLink(directory.resolve("link.json")));
        assertEquals("original", Files.readString(outside));
    }

    @Test void listsAreSortedAndStorageQuotaCannotBeExceeded() {
        var store = new BlueprintStore(directory);
        for (int i = 0; i < BlueprintStore.MAX_BLUEPRINTS; i++) store.save("test" + i, document(), false);
        assertThrows(ToolArgError.class, () -> store.save("overflow", document(), false));
        assertDoesNotThrow(() -> store.save("test0", document(), true));
        assertEquals("test0", store.list().getAsJsonArray("blueprints").get(0).getAsJsonObject().get("id").getAsString());
    }

    @Test void toolActionsAreStrictAndNeverChangeTheWorld() {
        var store = new BlueprintStore(directory); var tool = new McBlueprint(store);
        var save = obj("{\"action\":\"save\",\"id\":\"test\"}"); save.add("document", document());
        assertFalse(tool.call(null, save).join().isError());
        assertFalse(tool.call(null, obj("{\"action\":\"get\",\"id\":\"test\"}")).join().isError());
        assertFalse(tool.call(null, obj("{\"action\":\"list\"}")).join().isError());
        assertTrue(tool.call(null, obj("{\"action\":\"list\",\"id\":\"ignored\"}")).join().isError());
        assertTrue(tool.call(null, obj("{\"action\":\"save\",\"id\":\"test\"}")).join().isError());
        assertFalse(tool.call(null, obj("{\"action\":\"delete\",\"id\":\"test\"}")).join().isError());
        assertTrue(tool.call(null, obj("{\"action\":\"delete\",\"id\":\"test\"}")).join().isError());
    }

    @Test void buildRejectsMixedModesAndUnknownBlueprintsBeforeHandlers() {
        var store = new BlueprintStore(directory); store.save("demo", document(), false);
        RpcHandler unexpected = (ctx, args) -> { fail("No world handler should run"); return null; };
        var tool = new McBuild(unexpected, unexpected, unexpected, store, () -> BlueprintCompiler.Limits.DEFAULT);
        var request = request(); request.add("blocks", new com.google.gson.JsonArray());
        assertTrue(tool.call(null, request).join().isError());
        assertTrue(tool.call(null, obj("{\"blueprint\":{\"id\":\"missing\"}}")).join().isError());
        assertTrue(tool.call(null, obj("{\"blueprint\":{\"id\":\"demo\",\"unknown\":true}}")).join().isError());
    }

    @Test void compilerBudgetFailureOccursBeforeSnapshotsOrHandlers() {
        var store = new BlueprintStore(directory); store.save("demo", document(), false);
        RpcHandler unexpected = (ctx, args) -> { fail("No world handler should run"); return null; };
        var tool = new McBuild(unexpected, unexpected, unexpected, store, () -> new BlueprintCompiler.Limits(1,1024,2000));
        var request = request(); request.addProperty("snapshot", true);
        assertTrue(tool.call(null, request).join().isError());
    }
}
