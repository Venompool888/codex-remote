package app.codexremote.android

import android.app.Activity
import android.content.Intent
import android.widget.Toast
import androidx.core.content.FileProvider
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.io.File
import java.security.MessageDigest
import java.util.concurrent.TimeUnit

class ArtifactDownloads(
    private val activity: Activity,
    private val onEvent: (ArtifactDownloadEvent) -> Unit,
    private val onOpenText: ((String, String) -> Unit)?,
) {
    constructor(activity: Activity, onEvent: (ArtifactDownloadEvent) -> Unit) : this(activity, onEvent, null)
    private val http = OkHttpClient.Builder().callTimeout(120, TimeUnit.SECONDS).followRedirects(false).followSslRedirects(false).build()
    fun downloadAttachment(server: String, token: String, id: String, stillCurrent: () -> Boolean) {
        if (activity.isDestroyed || activity.isFinishing || !stillCurrent()) return
        if (!Regex("[a-f0-9-]{36}").matches(id)) return
        Thread {
            val result = runCatching { AttachmentTransfer().describe(server, token, id) }
            activity.runOnUiThread {
                if (activity.isDestroyed || activity.isFinishing || !stillCurrent()) return@runOnUiThread
                val attachment = result.getOrNull()
                if (attachment == null || attachment.optString("id") != id || attachment.optString("status") != "complete") {
                    Toast.makeText(activity, "Attachment unavailable. Check the original connection and host version.", Toast.LENGTH_LONG).show()
                } else download(server, token, attachment, stillCurrent = stillCurrent, attachmentId = id)
            }
        }.start()
    }

    fun download(server: String, token: String, artifact: JSONObject, shareWhenReady: Boolean = false,
                 stillCurrent: () -> Boolean, attachmentId: String? = null) {
        if (activity.isDestroyed || activity.isFinishing || !stillCurrent()) return
        val id = artifact.optString("id")
        val size = artifact.optLong("size")
        val hash = artifact.optString("sha256")
        val validId = if (attachmentId == null) Regex("[a-f0-9]{64}").matches(id)
            else attachmentId == id && Regex("[a-f0-9-]{36}").matches(id)
        if (!validId || size !in 1..50L * 1024 * 1024 || !Regex("[a-fA-F0-9]{64}").matches(hash)) {
            Toast.makeText(activity, "Invalid artifact information; refresh outputs from the host", Toast.LENGTH_LONG).show()
            return
        }
        val name = artifact.optString("name").replace(Regex("[^\\p{L}\\p{N}._ -]"), "_").take(120).takeUnless { it.isBlank() || it == "." || it == ".." } ?: "artifact"
        val directory = File(activity.filesDir, "artifacts").apply { mkdirs() }
        directory.listFiles().orEmpty().filter {
            System.currentTimeMillis() - it.lastModified() > 7L * 24 * 60 * 60_000
        }.forEach { it.deleteRecursively() }
        val scope = MessageDigest.getInstance("SHA-256").digest("$server\u0000$id".toByteArray()).joinToString("") { "%02x".format(it) }
        val destination = File(File(directory, scope).apply { mkdirs() }, name)
        // Concurrent retries/recreated Activities must never write or delete each other's partial file.
        val temporary = File.createTempFile("$scope-", ".part", directory)
        val eventKey = java.util.UUID.randomUUID().toString()
        val resource = if (attachmentId == null) "/v2/artifacts/$id" else "/v2/attachments/$id/download"
        val request = http.newCall(Request.Builder().url(server.trimEnd('/') + resource)
            .header("Authorization", "Bearer $token").build())
        onEvent(ArtifactDownloadEvent.Started(eventKey, name) { request.cancel() })
        Thread {
            try {
                request.execute().use { response ->
                    check(response.isSuccessful) { "Download unavailable (HTTP ${response.code})" }
                    val body = response.body ?: error("Empty artifact response")
                    body.byteStream().use { input ->
                        VerifiedArtifactCopy.copy(input, temporary, size, hash) { percent ->
                            activity.runOnUiThread {
                                if (!activity.isDestroyed && !activity.isFinishing) {
                                    onEvent(ArtifactDownloadEvent.Progress(eventKey, percent))
                                }
                            }
                        }
                    }
                    check(temporary.renameTo(destination)) { "Cannot save artifact" }
                }
                activity.runOnUiThread {
                    if (!activity.isDestroyed) {
                        if (activity.isFinishing || !stillCurrent()) {
                            onEvent(ArtifactDownloadEvent.Dismissed(eventKey))
                            return@runOnUiThread
                        }
                        if (shareWhenReady) {
                            onEvent(ArtifactDownloadEvent.Dismissed(eventKey))
                            open(destination, artifact.optString("mimeType"), true, stillCurrent)
                            return@runOnUiThread
                        }
                        onEvent(ArtifactDownloadEvent.Completed(eventKey, name,
                            open = { open(destination, artifact.optString("mimeType"), false, stillCurrent) },
                            share = { open(destination, artifact.optString("mimeType"), true, stillCurrent) },
                        ))
                    }
                }
            } catch (_: Exception) {
                temporary.delete()
                activity.runOnUiThread {
                    if (!activity.isDestroyed) {
                        if (!activity.isFinishing && !request.isCanceled() && stillCurrent()) {
                            onEvent(ArtifactDownloadEvent.Failed(eventKey) {
                                download(server, token, artifact, shareWhenReady, stillCurrent, attachmentId)
                            })
                        } else {
                            onEvent(ArtifactDownloadEvent.Dismissed(eventKey))
                        }
                    }
                }
            }
        }.start()
    }
    private fun open(file: File, mime: String, share: Boolean, stillCurrent: () -> Boolean) {
        if (activity.isDestroyed || activity.isFinishing || !stillCurrent()) return
        if (!share && onOpenText != null && (mime.startsWith("text/") || mime == "application/json")) {
            val limit = 64 * 1024
            val bytes = runCatching { file.inputStream().use { input ->
                ByteArray(minOf(file.length(), limit.toLong()).toInt()).also {
                    java.io.DataInputStream(input).readFully(it)
                }
            } }.getOrNull()
            if (bytes != null) {
                val text = bytes.toString(Charsets.UTF_8) +
                    if (file.length() > limit) "\n\nPreview truncated. Share the file to read its full contents." else ""
                onOpenText.invoke(file.name, text)
                return
            }
        }
        val uri = FileProvider.getUriForFile(activity, "${activity.packageName}.artifacts", file)
        val intent = if (share) Intent(Intent.ACTION_SEND).setType(mime).putExtra(Intent.EXTRA_STREAM, uri)
            else Intent(Intent.ACTION_VIEW).setDataAndType(uri, mime)
        intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        intent.clipData = android.content.ClipData.newRawUri("Artifact", uri)
        runCatching { activity.startActivity(Intent.createChooser(intent, if (share) "Share artifact" else "Open artifact")) }
            .onFailure { Toast.makeText(activity, "No compatible app is available", Toast.LENGTH_LONG).show() }
    }
}
