package app.codexremote.android

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.nio.file.Files
import java.util.zip.ZipFile

class DiagnosticBundleTest {
    @Test fun snapshotsKeepOnlyBoundedMetadataAndRedactKnownCredentials() {
        val thread = JSONObject().put("id", "task-1").put("name", "private title").put("cwd", "/private/path")
            .put("turns", JSONArray().apply { repeat(20) { put(JSONObject().put("id", "turn-$it")
                .put("status", "completed").put("items", JSONArray().put(JSONObject().put("text", "private prompt")))) } })
        val state = DiagnosticThreadState.capture("https://user:secret@example.test/private?token=secret", thread,
            emptyMap(), true, false, false)
        assertEquals(12, state.getJSONArray("turns").length())
        assertEquals("https://example.test", state.getString("server"))
        listOf("private title", "/private/path", "private prompt", "secret").forEach { assertFalse(state.toString().contains(it)) }
        assertTrue(state.getBoolean("composerRunning"))
        thread.put("id", "credential")
        assertFalse(DiagnosticThreadState.capture("https://example.test", thread, emptyMap(), false, true, false,
            listOf("credential")).toString().contains("credential"))
    }

    @Test fun recentTasksSurviveRestartAreServerScopedBoundedAndClearable() {
        val dir = Files.createTempDirectory("recent-diagnostics").toFile()
        try {
            val file = dir.resolve("recent.json")
            val store = DiagnosticRecentThreads(file)
            repeat(25) { store.record(JSONObject().put("server", "one").put("threadId", "task-$it")) }
            store.record(JSONObject().put("server", "two").put("threadId", "task-24"))
            store.record(JSONObject().put("server", "one").put("threadId", "task-24").put("composerRunning", false))
            val recent = DiagnosticRecentThreads(file).read()
            assertEquals(20, recent.length())
            assertFalse(recent.toString().contains("task-0\""))
            assertEquals(2, diagnosticObjects(recent).count { it.optString("threadId") == "task-24" })
            assertFalse(recent.getJSONObject(19).getBoolean("composerRunning"))
            store.clear()
            assertEquals(0, store.read().length())
        } finally { dir.deleteRecursively() }
    }

    @Test fun malformedRecentMetadataDoesNotPermanentlyBlockNewRecords() {
        val dir = Files.createTempDirectory("broken-diagnostics").toFile()
        try {
            val file = dir.resolve("recent.json")
            file.writeText("[{broken")
            val store = DiagnosticRecentThreads(file)
            assertTrue(store.record(JSONObject().put("server", "one").put("threadId", "recovered-task")))
            val recent = DiagnosticRecentThreads(file).read()
            assertEquals(1, recent.length())
            assertEquals("recovered-task", recent.getJSONObject(0).getString("threadId"))
            assertFalse(store.record(JSONObject().put("server", "one").put("threadId", "next-task")))
            assertEquals(2, store.read().length())
        } finally { dir.deleteRecursively() }
    }

    @Test fun archiveHasOnlyExpectedFilesAndPreservesUnicodeLogAndState() {
        val dir = Files.createTempDirectory("diagnostic-bundle").toFile()
        try {
            dir.resolve("credentials.xml").writeText("secret-never-in-zip")
            val file = dir.resolve("bundle.zip")
            DiagnosticBundle.write(file, "回合结束\nno active turn to interrupt (-32600)",
                JSONObject().put("composerRunning", true), JSONArray().put(JSONObject().put("threadId", "task-1")))
            ZipFile(file).use { zip ->
                assertEquals(setOf("README.txt", "diagnostic-log.txt", "app-state.json", "recent-threads.json"),
                    zip.entries().asSequence().map { it.name }.toSet())
                assertTrue(zip.getInputStream(zip.getEntry("diagnostic-log.txt")).bufferedReader().use { it.readText() }.contains("回合结束"))
                assertTrue(JSONObject(zip.getInputStream(zip.getEntry("app-state.json")).bufferedReader().use { it.readText() }).getBoolean("composerRunning"))
            }
        } finally { dir.deleteRecursively() }
    }
}
