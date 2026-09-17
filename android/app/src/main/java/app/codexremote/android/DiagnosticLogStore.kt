package app.codexremote.android

import java.io.File
import java.net.URI
import java.time.Instant

/** Bounded app-private log. Call from the diagnostic worker, never the UI thread. */
class DiagnosticLogStore(private val directory: File, private val maxFileBytes: Int = 256 * 1024) {
    private val current get() = File(directory, "current.log")
    private val previous get() = File(directory, "previous.log")

    @Synchronized fun append(record: String) {
        directory.mkdirs()
        val bytes = (record + "\n\n").toByteArray(Charsets.UTF_8)
        require(bytes.size <= maxFileBytes) { "Diagnostic record too large" }
        if (current.length() + bytes.size > maxFileBytes) {
            if (previous.exists()) check(previous.delete())
            if (current.exists()) check(current.renameTo(previous))
        }
        current.appendBytes(bytes)
    }

    @Synchronized fun read(): String = listOf(previous, current)
        .filter { it.exists() }.joinToString("") { it.readText(Charsets.UTF_8) }

    @Synchronized fun clear() {
        listOf(previous, current).forEach { if (it.exists()) check(it.delete()) }
    }
}

/** Never pass complete RPC payloads to the logger. Redact before enqueueing or writing. */
object DiagnosticRedaction {
    fun host(server: String): String = runCatching {
        val uri = URI(server)
        val host = uri.host ?: return@runCatching "[invalid host]"
        "${uri.scheme}://$host" + if (uri.port >= 0) ":${uri.port}" else ""
    }.getOrDefault("[invalid host]")

    fun clean(value: String, secrets: Collection<String> = emptyList()): String {
        var text = value
        secrets.filter { it.isNotBlank() }.sortedByDescending { it.length }.forEach {
            text = text.replace(it, "[redacted]")
        }
        text = text.replace(Regex("(?i)Bearer\\s+[^\\s,;\"']+"), "Bearer [redacted]")
        text = text.replace(Regex("(?i)([\"']?(?:access[_-]?token|refresh[_-]?token|token|api[_-]?key|authorization|password|secret|pairing[_-]?code)[\"']?\\s*[:=]\\s*)(?:\"[^\"]*\"|'[^']*'|[^\\s,;}]+)"), "$1[redacted]")
        text = text.replace(Regex("\\bsk-[A-Za-z0-9_-]{12,}"), "[redacted]")
        text = text.replace(Regex("(?:https?|wss?)://[^\\s<>\"']+")) { host(it.value) }
        text = text.replace(Regex("[\\p{Cntrl}&&[^\\n\\t]]"), " ")
        return if (text.length > 16000) text.take(16000) + "\n[diagnostic field truncated at 16000 characters]" else text
    }

    fun record(event: String, server: String = "", detail: String = "", secrets: Collection<String> = emptyList()): String =
        "${Instant.now()} ${clean(event, secrets)}" +
            (if (server.isBlank()) "" else " host=${host(server)}") +
            (if (detail.isBlank()) "" else "\n" + clean(detail, secrets).prependIndent("  "))
}
