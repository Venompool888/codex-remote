package app.codexremote.android

import android.app.job.JobInfo
import android.app.job.JobParameters
import android.app.job.JobScheduler
import android.app.job.JobService
import android.content.ComponentName
import android.content.Context
import org.json.JSONObject
import java.io.File
import java.util.concurrent.Executors

/** Process-independent queue. Android reruns the job when network constraints are satisfied. */
object AttachmentQueue {
    private val executor = Executors.newSingleThreadExecutor()
    private const val JOB_ID = 4811
    fun schedule(context: Context) {
        val job = JobInfo.Builder(JOB_ID, ComponentName(context, AttachmentUploadJob::class.java))
            .setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY)
            .setBackoffCriteria(10_000, JobInfo.BACKOFF_POLICY_EXPONENTIAL).build()
        context.getSystemService(JobScheduler::class.java).schedule(job)
    }
    fun run(context: Context, stopped: () -> Boolean = { false }, done: (Boolean) -> Unit = {}) {
        val app = context.applicationContext
        executor.execute {
            val store = DraftStore.shared(File(app.filesDir, "drafts"))
            val tokens = SecureTokenStore(app)
            val transfer = AttachmentTransfer { url, method, status ->
                DiagnosticLogs.get(app).record("attachment.http", url,
                    "method=$method stage=${if (url.endsWith("/complete")) "complete" else "transfer"} status=$status")
            }
            var retry = false
            store.scopes().forEach { scope ->
                if (stopped()) { retry = true; return@forEach }
                val draft = store.read(scope)
                val server = draft.optString("server")
                val device = draft.optString("device")
                val credential = tokens.loadCredential(server)
                if (server.isBlank() || credential == null || credential.deviceId.orEmpty() != device || !draft.optBoolean("chunked")) return@forEach
                val deletions = draft.optJSONArray("deletions") ?: org.json.JSONArray()
                for (index in 0 until deletions.length()) {
                    if (stopped()) { retry = true; break }
                    val deletedId = deletions.getString(index)
                    try {
                        transfer.delete(server, credential.token, deletedId)
                        synchronized(store) {
                            val current = store.read(scope)
                            val list = current.optJSONArray("deletions") ?: org.json.JSONArray()
                            val remaining = org.json.JSONArray()
                            for (i in 0 until list.length()) if (list.getString(i) != deletedId) remaining.put(list.getString(i))
                            store.write(scope, current.put("deletions", remaining))
                        }
                    } catch (_: Exception) { retry = true }
                }
                val items = draft.optJSONArray("attachments") ?: return@forEach
                for (i in 0 until items.length()) {
                    if (stopped()) { retry = true; break }
                    val saved = items.getJSONObject(i)
                    if (saved.optString("type") != "remoteAttachment" || saved.optString("path").isNotBlank() ||
                        saved.optString("state") !in setOf("preparing", "queued", "uploading", "waiting")) continue
                    val id = saved.getString("localId")
                    val diagnostics = DiagnosticLogs.get(app)
                    val diagnosticSecrets = listOf(credential.token, saved.optString("name"), saved.optString("sourceUri"))
                    diagnostics.record("attachment.started", server, "id=$id state=${saved.optString("state")}", diagnosticSecrets)
                    try {
                        if (saved.optString("localFile").isBlank()) {
                            val uri = android.net.Uri.parse(saved.getString("sourceUri"))
                            val imported = app.contentResolver.openInputStream(uri)?.use { store.importFile(it, cancelled = stopped) }
                                ?: error("Attachment cannot be read; select it again")
                            val (file, hash) = imported
                            val retained = store.updateAttachment(scope, id) {
                                it.put("localFile", file.absolutePath).put("size", file.length()).put("sha256", hash)
                                if (saved.optString("mimeType").startsWith("image/")) it.put("previewPath", file.absolutePath)
                                if (it.optString("state") == "preparing") it.put("state", "queued").put("description", "Queued")
                                it.remove("sourceUri")
                            }
                            runCatching { app.contentResolver.releasePersistableUriPermission(uri, android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION) }
                            if (!retained) { file.delete(); continue }
                            val current = store.attachment(scope, id) ?: continue
                            current.keys().forEach { key -> saved.put(key, current.get(key)) }
                        }
                        val result = transfer.upload(server, credential.token, saved, {
                            stopped() || store.attachment(scope, id)?.optString("state") !in setOf("preparing", "queued", "uploading", "waiting") ||
                                tokens.loadCredential(server)?.deviceId != credential.deviceId
                        }) { checkpoint ->
                            store.updateAttachment(scope, id) {
                                it.put("remoteId", checkpoint.optString("remoteId"))
                                    .put("chunkBytes", checkpoint.optInt("chunkBytes"))
                                    .put("progress", checkpoint.optInt("progress"))
                                if (it.optString("state") in setOf("preparing", "queued", "uploading", "waiting"))
                                    it.put("state", "uploading").put("description", "Uploading ${checkpoint.optInt("progress")}%")
                            }
                        }
                        diagnostics.record("attachment.upload_complete", server, "id=$id bytes=${saved.optLong("size")}", diagnosticSecrets)
                        store.updateAttachment(scope, id) {
                            if (it.optString("state") in setOf("preparing", "queued", "uploading", "waiting"))
                                it.put("path", result.getString("id")).put("state", "ready").put("description", "Ready")
                        }
                    } catch (error: Exception) {
                        diagnostics.record("attachment.failed", server, "id=$id ${error.javaClass.simpleName}: ${error.message.orEmpty()}", diagnosticSecrets)
                        val network = saved.optString("localFile").isNotBlank() && error is java.io.IOException && !error.message.orEmpty().startsWith("Attachment")
                        if (network || stopped()) retry = true
                        store.updateAttachment(scope, id) {
                            if (it.optString("state") in setOf("preparing", "queued", "uploading", "waiting")) {
                                it.put("state", if (network || stopped()) "waiting" else "failed")
                                    .put("description", if (network || stopped()) "Waiting for network · will resume" else
                                        error.message?.takeIf { message -> message.startsWith("Attachment") }
                                            ?: "Upload failed; tap to retry or select the file again")
                            }
                        }
                    }
                }
            }
            done(retry)
        }
    }
}

class AttachmentUploadJob : JobService() {
    private val sessions = UploadJobSessions()
    private val main = android.os.Handler(android.os.Looper.getMainLooper())
    override fun onStartJob(params: JobParameters): Boolean {
        val run = sessions.begin()
        AttachmentQueue.run(this, run::isStopped) { retry ->
            main.post { if (sessions.finish(run)) jobFinished(params, retry) }
        }
        return true
    }
    override fun onStopJob(params: JobParameters): Boolean { sessions.stop(); return true }
    override fun onDestroy() {
        sessions.stop()
        super.onDestroy()
    }
}
