package app.codexremote.android

import android.content.Context
import java.io.File
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit

/** One ordered, bounded worker per process; diagnostics must never break app operations. */
class DiagnosticLogs private constructor(context: Context) {
    private val store = DiagnosticLogStore(File(context.filesDir, "diagnostics"))
    private val exports = File(context.cacheDir, "diagnostic-exports")
    private val header = "Codex Remote ${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})\n" +
        "Android ${android.os.Build.VERSION.RELEASE} / API ${android.os.Build.VERSION.SDK_INT}\n" +
        "Times are UTC. Local diagnostic log; up to two 256 KiB files.\n" +
        "No request bodies or attachment contents are logged. Review errors and host paths before sharing.\n\n"
    private val worker = ThreadPoolExecutor(1, 1, 0, TimeUnit.MILLISECONDS, ArrayBlockingQueue(512),
        { runnable -> Thread(runnable, "remote-diagnostics").apply { isDaemon = true } },
        ThreadPoolExecutor.AbortPolicy())
    @Volatile private var storageFailure = false

    fun record(event: String, server: String = "", detail: String = "", secrets: Collection<String> = emptyList()) {
        val safe = DiagnosticRedaction.record(event, server, detail, secrets)
        submit { runCatching { store.append(safe) }.onFailure { storageFailure = true } }
    }

    fun read(done: (String) -> Unit) {
        if (!submit {
            val text = runCatching { store.read() }.getOrElse { storageFailure = true; "Log storage is unavailable.\n" }
            done(header + (if (storageFailure) "Some diagnostic entries could not be saved.\n\n" else "") + text)
        }) done("Diagnostic worker is busy. Please refresh again.")
    }

    fun clear(done: (Boolean) -> Unit) {
        if (!submit {
            val ok = runCatching {
                store.clear()
                exports.listFiles()?.forEach { check(it.delete()) }
            }.isSuccess
            storageFailure = !ok
            done(ok)
        }) done(false)
    }

    fun export(done: (File?) -> Unit) {
        if (!submit {
            done(runCatching {
                exports.mkdirs()
                // One fixed, bounded snapshot; repeated sharing cannot fill the cache.
                File(exports, "codex-remote-diagnostics.txt").apply {
                    writeText(header + (if (storageFailure) "Some diagnostic entries could not be saved.\n\n" else "") + store.read())
                }
            }.getOrNull())
        }) done(null)
    }

    private fun submit(action: () -> Unit): Boolean = runCatching { worker.execute(action); true }
        .getOrElse { storageFailure = true; false }

    companion object {
        @Volatile private var instance: DiagnosticLogs? = null
        fun get(context: Context): DiagnosticLogs = instance ?: synchronized(this) {
            instance ?: DiagnosticLogs(context.applicationContext).also { instance = it }
        }
    }
}
