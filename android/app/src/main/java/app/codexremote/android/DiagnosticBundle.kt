package app.codexremote.android

import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.time.Instant
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

internal fun diagnosticObjects(array: JSONArray?): List<JSONObject> =
    (0 until (array?.length() ?: 0)).mapNotNull { array?.optJSONObject(it) }

/** Explicit allowlist: never serialize thread payloads, titles, text, paths or attachments. */
object DiagnosticThreadState {
    fun capture(server: String, thread: JSONObject, live: Map<String, LiveTurnSnapshot>, running: Boolean,
                connected: Boolean, submitting: Boolean, secrets: Collection<String> = emptyList()): JSONObject {
        fun safe(value: String) = DiagnosticRedaction.clean(value, secrets).take(256)
        val turns = diagnosticObjects(thread.optJSONArray("turns")).takeLast(12)
        return JSONObject().put("server", DiagnosticRedaction.host(server))
            .put("threadId", safe(thread.optString("id")))
            .put("threadStatus", safe(thread.optJSONObject("status")?.optString("type") ?: thread.optString("status")))
            .put("composerRunning", running).put("connected", connected).put("submitting", submitting)
            .put("turns", JSONArray().apply { turns.forEach { turn -> put(JSONObject()
                .put("id", safe(turn.optString("id"))).put("status", safe(turn.optString("status")))
                .put("itemCount", turn.optJSONArray("items")?.length() ?: 0)) } })
            .put("liveTurns", JSONArray().apply { live.entries.toList().takeLast(12).forEach { (id, turn) -> put(JSONObject()
                .put("id", safe(id)).put("status", safe(turn.status)).put("itemCount", turn.items.size)) } })
    }
}

/** Worker-owned metadata snapshots survive process restarts; at most 20 recently viewed tasks. */
class DiagnosticRecentThreads(private val file: File) {
    /** Returns true when malformed metadata was replaced; I/O failures still propagate. */
    fun record(state: JSONObject): Boolean {
        var recovered = false
        val entries = try { read() } catch (_: org.json.JSONException) {
            recovered = true
            JSONArray()
        }
        val retained = diagnosticObjects(entries).filterNot {
            it.optString("server") == state.optString("server") && it.optString("threadId") == state.optString("threadId")
        }.takeLast(19)
        val next = JSONArray(retained).put(JSONObject(state.toString()).put("capturedAt", Instant.now().toString()))
        file.parentFile?.mkdirs()
        val pending = File(file.parentFile, file.name + ".tmp")
        pending.writeText(next.toString())
        check(pending.renameTo(file))
        return recovered
    }
    fun read(): JSONArray = if (file.exists()) JSONArray(file.readText()) else JSONArray()
    fun clear() {
        listOf(file, File(file.parentFile, file.name + ".tmp")).forEach { if (it.exists()) check(it.delete()) }
    }
}

object DiagnosticBundle {
    fun write(file: File, log: String, context: JSONObject, recent: JSONArray) {
        file.parentFile?.mkdirs()
        ZipOutputStream(file.outputStream().buffered()).use { zip ->
            val files = linkedMapOf(
                "README.txt" to """
                    Codex Remote diagnostic bundle
                    Generated locally at ${Instant.now()}. Times are UTC.
                    diagnostic-log.txt: bounded client operation log (up to two 256 KiB files).
                    app-state.json: app/device version and current connection/composer state.
                    recent-threads.json: up to 20 recently viewed task states, up to 12 turns each.
                    No conversation text, drafts, attachments, preferences, or credentials are copied.
                    Logs can still contain host names, task IDs, error text and paths. Review before sharing.
                    This is not Android logcat or server-side logs. Events before logging was installed,
                    rotated logs, and tasks not visited on this phone are unavailable.
                    The app does not upload this archive. Saving/sharing is initiated by you.
                """.trimIndent(),
                "diagnostic-log.txt" to log,
                "app-state.json" to context.toString(2),
                "recent-threads.json" to recent.toString(2)
            )
            files.forEach { (name, content) ->
                zip.putNextEntry(ZipEntry(name)); zip.write(content.toByteArray(Charsets.UTF_8)); zip.closeEntry()
            }
        }
    }
}
