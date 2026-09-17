package app.codexremote.android

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.nio.file.Files
import java.io.ByteArrayInputStream
import java.security.MessageDigest

class DraftStoreTest {
    @Test fun emptyLegacyDraftReusesExistingOpaqueDraftAndItsAttachmentWorkers() {
        val root = Files.createTempDirectory("draft-empty-legacy").toFile()
        try {
            val old = "host\u0000device\u0000draft:/project"
            val next = "host\u0000device\u0000draft:remote-workspace://" + "b".repeat(64)
            val store = DraftStore(root)
            store.write(old, JSONObject().put("text", "").put("attachments", JSONArray()))
            store.write(next, JSONObject().put("text", "retain me").put("threadKey", "retry-key")
                .put("attachments", JSONArray().put(JSONObject().put("localId", "upload").put("progress", 25))))
            val before = root.listFiles()!!.associate { it.name to it.readText() }
            store.bindWorkspaceAlias(old, next)
            store.bindWorkspaceAlias(old, next)
            before.forEach { (name, value) -> assertEquals(value, java.io.File(root, name).readText()) }
            val restored = DraftStore(root)
            assertEquals("retain me", restored.read(old).getString("text"))
            assertEquals("retry-key", restored.read(old).getString("threadKey"))
            assertTrue(restored.updateAttachment(next, "upload") { it.put("progress", 50) })
            assertEquals(50, restored.attachment(old, "upload")!!.getInt("progress"))
        } finally { root.deleteRecursively() }
    }

    @Test fun emptyOpaqueDraftCanAliasLegacyContentButUnknownStateIsNotEmpty() {
        val root = Files.createTempDirectory("draft-empty-opaque").toFile()
        try {
            val old = "host\u0000device\u0000draft:/project"
            val next = "host\u0000device\u0000draft:remote-workspace://" + "c".repeat(64)
            val store = DraftStore(root)
            store.write(old, JSONObject().put("text", "legacy text"))
            store.write(next, JSONObject().put("text", ""))
            store.bindWorkspaceAlias(old, next)
            assertEquals("legacy text", DraftStore(root).read(next).getString("text"))
            val second = "host\u0000device\u0000draft:/second"
            store.write(second, JSONObject().put("text", "").put("futurePendingState", true))
            try { store.bindWorkspaceAlias(second, next); fail("unknown state must remain separate") }
            catch (_: IllegalStateException) { }
            assertTrue(store.read(second).getBoolean("futurePendingState"))
        } finally { root.deleteRecursively() }
    }

    @Test fun rejectedFirstTurnRestoresEditableDraftWithoutReusingRejectedWriteKeys() {
        val root = Files.createTempDirectory("rejected-first-turn").toFile()
        try {
            val store = DraftStore(root)
            val task = "host\u0000device\u0000task"
            val draft = "host\u0000device\u0000draft:workspace"
            store.write(task, JSONObject().put("text", "keep exact text").put("threadKey", "old-thread")
                .put("turnKey", "old-turn").put("attachments", JSONArray().put(JSONObject()
                    .put("localId", "one").put("remoteId", "uploaded").put("localFile", "retained.payload"))))
            assertTrue(store.restoreRejectedNewChat(task, draft))
            val restored = DraftStore(root).read(draft)
            assertEquals("keep exact text", restored.getString("text"))
            assertEquals("uploaded", restored.getJSONArray("attachments").getJSONObject(0).getString("remoteId"))
            assertFalse(restored.has("threadKey"))
            assertFalse(restored.has("turnKey"))
            assertEquals(0, store.read(task).getJSONArray("attachments").length())
        } finally { root.deleteRecursively() }
    }

    @Test fun rejectedFirstTurnDoesNotOverwriteAnotherDraftOrCrossDevices() {
        val root = Files.createTempDirectory("rejected-first-turn-conflict").toFile()
        try {
            val store = DraftStore(root)
            val task = "host\u0000device\u0000task"
            val draft = "host\u0000device\u0000draft:workspace"
            store.write(task, JSONObject().put("text", "failed message"))
            store.write(draft, JSONObject().put("text", "independently edited"))
            assertFalse(store.restoreRejectedNewChat(task, draft))
            assertEquals("failed message", store.read(task).getString("text"))
            assertEquals("independently edited", store.read(draft).getString("text"))
            try { store.restoreRejectedNewChat(task, draft.replace("device", "other")); fail("cross device") }
            catch (_: IllegalArgumentException) { }
        } finally { root.deleteRecursively() }
    }
    @Test fun workspaceAliasPreservesWorkerProgressAndOneRecordAcrossRestart() {
        val root = Files.createTempDirectory("draft-alias").toFile()
        try {
            val old = "host\u0000device\u0000draft:/private/project"
            val next = "host\u0000device\u0000draft:remote-workspace://" + "a".repeat(64)
            val store = DraftStore(root)
            store.write(old, JSONObject().put("text", "unsent").put("attachments", JSONArray()
                .put(JSONObject().put("localId", "one").put("progress", 25))))
            store.bindWorkspaceAlias(old, next)
            store.bindWorkspaceAlias(old, next)
            assertTrue(store.updateAttachment(old, "one") { it.put("progress", 50) })
            val restored = DraftStore(root)
            assertEquals(50, restored.attachment(next, "one")!!.getInt("progress"))
            restored.write(next, restored.read(next).put("text", "edited"))
            assertEquals("edited", store.read(old).getString("text"))
            assertEquals(listOf(old), restored.scopes())
            assertEquals(old, restored.read(next).getString("scope"))
        } finally { root.deleteRecursively() }
    }

    @Test fun workspaceAliasDoesNotOverwriteAnotherDraftOrCrossDeviceBoundary() {
        val root = Files.createTempDirectory("draft-alias-conflict").toFile()
        try {
            val old = "host\u0000device\u0000draft:/private/project"
            val next = "host\u0000device\u0000draft:remote-workspace://" + "a".repeat(64)
            val store = DraftStore(root)
            store.write(old, JSONObject().put("text", "old"))
            store.write(next, JSONObject().put("text", "new"))
            try { store.bindWorkspaceAlias(old, next); fail("must retain both") } catch (_: IllegalStateException) { }
            assertEquals("old", store.read(old).getString("text"))
            assertEquals("new", store.read(next).getString("text"))
            try { store.bindWorkspaceAlias(old, next.replace("device", "other")); fail("cross device") } catch (_: IllegalArgumentException) { }
            java.io.File(root, "workspace.aliases").writeText("broken")
            try { store.read(old); fail("corrupt aliases must not hide drafts") } catch (_: IllegalStateException) { }
        } finally { root.deleteRecursively() }
    }
    @Test fun survivesRecreationAndSeparatesHostDeviceAndDraft() {
        val root = Files.createTempDirectory("draft-test").toFile()
        try {
            val scope = "host-A\u0000device-A\u0000draft-A"
            DraftStore(root).write(scope, JSONObject().put("text", "unsent")
                .put("attachments", JSONArray().put(JSONObject().put("localId", "one").put("remoteId", "confirmed-id"))))
            val recreated = DraftStore(root)
            assertEquals("unsent", recreated.read(scope).getString("text"))
            assertEquals("confirmed-id", recreated.read(scope).getJSONArray("attachments").getJSONObject(0).getString("remoteId"))
            listOf("host-B\u0000device-A\u0000draft-A", "host-A\u0000device-B\u0000draft-A", "host-A\u0000device-A\u0000draft-B").forEach {
                assertEquals("", recreated.read(it).getString("text"))
            }
            assertFalse(recreated.updateAttachment(scope, "removed") { it.put("state", "ready") })
            assertTrue(recreated.updateAttachment(scope, "one") { it.put("progress", 50) })
            assertEquals("unsent", recreated.read(scope).getString("text"))
        } finally { root.deleteRecursively() }
    }

    @Test fun boundedImportHashesContentAndRemovesFailedCopy() {
        val root = Files.createTempDirectory("draft-test").toFile()
        try {
            val store = DraftStore(root)
            val bytes = ByteArray(130_000) { (it % 251).toByte() }
            val (file, hash) = store.importFile(ByteArrayInputStream(bytes))
            assertArrayEquals(bytes, file.readBytes())
            assertEquals(MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }, hash)
            try { store.importFile(ByteArrayInputStream(bytes), 100); fail("expected size rejection") }
            catch (_: IllegalArgumentException) { }
            assertEquals(1, root.listFiles()!!.size)
            store.cleanupOrphans(System.currentTimeMillis() + 2 * 24 * 60 * 60_000L)
            assertFalse(file.exists())
        } finally { root.deleteRecursively() }
    }
    @Test fun cancelledImportStopsBetweenChunksAndDeletesPartialPayload() {
        val root = Files.createTempDirectory("draft-cancel").toFile()
        try {
            val input = ByteArrayInputStream(ByteArray(200_000))
            var checks = 0
            try {
                DraftStore(root).importFile(input, cancelled = { ++checks > 1 })
                fail("expected cancellation")
            } catch (_: java.io.IOException) { }
            assertEquals(200_000 - 64 * 1024, input.available())
            assertTrue(root.listFiles()!!.isEmpty())
        } finally { root.deleteRecursively() }
    }

    @Test fun removalPersistsRemoteDeletionAndExpiryKeepsTextWithExplicitAttachmentState() {
        val root = Files.createTempDirectory("draft-retention").toFile()
        try {
            val store = DraftStore(root)
            val (file, _) = store.importFile(ByteArrayInputStream("proof".toByteArray()))
            store.write("scope", JSONObject().put("text", "Keep this draft").put("attachments", JSONArray()
                .put(JSONObject().put("type", "remoteAttachment").put("localId", "one").put("remoteId", "remote-one"))
                .put(JSONObject().put("type", "remoteAttachment").put("localId", "two").put("localFile", file.absolutePath))))
            store.removeAttachment("scope", "one")
            assertEquals("remote-one", DraftStore(root).read("scope").getJSONArray("deletions").getString(0))
            store.cleanupOrphans(System.currentTimeMillis() + 31L * 24 * 60 * 60_000)
            assertEquals("Keep this draft", store.read("scope").getString("text"))
            assertEquals("expired", store.attachment("scope", "two")!!.getString("state"))
            assertFalse(file.exists())
        } finally { root.deleteRecursively() }
    }

}
