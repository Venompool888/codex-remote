package app.codexremote.android

import android.graphics.Bitmap
import android.util.Base64
import org.json.JSONObject
import java.io.File
import java.security.MessageDigest
import java.util.concurrent.Executor
import java.util.concurrent.atomic.AtomicBoolean

/** Image presentation data contains no host credentials. */
data class RemoteImageContent(
    val bitmap: Bitmap? = null,
    val error: String? = null,
    val share: (() -> Unit)? = null,
    val save: (() -> Unit)? = null,
)

/** Captured by the runtime on the main thread, including its navigation/device ownership fence. */
class RemoteImageSource(
    val server: String,
    val deviceId: String,
    val threadId: String?,
    internal val token: String,
    val attachmentPreviews: Boolean,
    val artifacts: Boolean,
    internal val stillCurrent: () -> Boolean,
)

/** Existing private image IO with presentation supplied by the caller, independent of Views. */
class RemoteImageRepository(
    private val cacheDirectory: File,
    private val capture: () -> RemoteImageSource?,
    private val rpc: (RemoteImageSource, String, JSONObject, (JSONObject?) -> Unit) -> Unit,
    private val worker: Executor,
    private val main: Executor,
    private val decode: (ByteArray) -> Bitmap?,
    private val cached: (String) -> Bitmap?,
    private val cache: (String, Bitmap) -> Unit,
    private val downloadArtifact: (RemoteImageSource, String, Boolean) -> Unit,
) {
    /** Call on the main thread. The returned cancellation function suppresses late presentation. */
    fun loadImage(source: String, alt: String, onResult: (RemoteImageContent) -> Unit): () -> Unit {
        val cancelled = AtomicBoolean(false)
        val cancel = { cancelled.set(true); Unit }
        val host = capture()
        if (host == null) {
            onResult(RemoteImageContent(error = "Connect to a host to view this image"))
            return cancel
        }
        fun current() = !cancelled.get() && host.stillCurrent()
        if (!current()) return cancel
        val attachment = AttachmentImageReference.parse(source)
        if (source.startsWith("remote-attachment:") && attachment == null) {
            onResult(RemoteImageContent(error = "Image reference is unavailable"))
            return cancel
        }
        val key = if (attachment != null) "${host.server}\u0000${host.deviceId}\u0000${attachment.id}"
            else RemoteImageIdentity.key(host.server, host.deviceId, host.threadId, source)
        val reference = source.removePrefix("remote-artifact-image://")
        val artifactImage = source.startsWith("remote-artifact-image:")
        val hasArtifactActions = source.startsWith("remote-artifact-image://") && Regex("[a-f0-9]{64}").matches(reference) && host.artifacts
        fun content(bitmap: Bitmap?, error: String? = null) = RemoteImageContent(
            bitmap = bitmap,
            error = if (bitmap == null) error ?: "Image unavailable" else null,
            share = if (bitmap != null && hasArtifactActions) ({ if (current()) downloadArtifact(host, reference, true) }) else null,
            save = if (bitmap != null && hasArtifactActions) ({ if (current()) downloadArtifact(host, reference, false) }) else null,
        )
        fun display(bitmap: Bitmap?, error: String? = null) {
            if (!current()) {
                bitmap?.recycle()
                return
            }
            if (bitmap != null) cache(key, bitmap)
            onResult(content(bitmap, error))
        }
        cached(key)?.let { onResult(content(it)); return cancel }
        if (attachment != null) {
            if (!host.attachmentPreviews) {
                onResult(RemoteImageContent(error = "Image preview requires a newer host"))
                return cancel
            }
            worker.execute {
                val result = runCatching { decode(AttachmentTransfer().preview(host.server, host.token, attachment.id)) }
                val message = (result.exceptionOrNull() as? AttachmentReadException)?.message
                    ?: "Preview unavailable · tap to retry"
                main.execute { display(result.getOrNull(), message) }
            }
            return cancel
        }
        if (artifactImage) {
            if (!hasArtifactActions) {
                onResult(RemoteImageContent(error = "Image reference is unavailable"))
                return cancel
            }
            rpc(host, "host/artifacts/list", JSONObject().put("threadId", host.threadId)) { result ->
                if (current()) {
                    val artifacts = result?.optJSONArray("artifacts")
                    val matches = (0 until (artifacts?.length() ?: 0)).mapNotNull { artifacts?.optJSONObject(it) }
                        .filter { it.optString("imageReference") == reference }
                    val artifact = matches.singleOrNull()
                    if (artifact == null) display(null)
                    else worker.execute {
                        val bytes = runCatching { ArtifactImageDownload.read(host.server, host.token, artifact) }.getOrNull()
                        val bitmap = bytes?.let { runCatching { decode(it) }.getOrNull() }
                        if (bitmap != null) writeDisk(key, bytes)
                        main.execute { display(bitmap) }
                    }
                }
            }
            return cancel
        }
        rpc(host, "host/image/read", JSONObject().put("path", source)) { result ->
            if (current()) worker.execute {
                val bytes = if (result == null) runCatching { diskFile(key).takeIf { it.isFile }?.readBytes() }.getOrNull()
                    else runCatching {
                        result.optString("data").takeIf { it.length <= 16 * 1024 * 1024 }
                            ?.let { Base64.decode(it, Base64.DEFAULT) }
                    }.getOrNull()
                val bitmap = bytes?.let { runCatching { decode(it) }.getOrNull() }
                if (bitmap != null && result != null) writeDisk(key, bytes)
                main.execute { display(bitmap) }
            }
        }
        return cancel
    }

    private fun diskFile(key: String): File {
        val name = MessageDigest.getInstance("SHA-256").digest(key.toByteArray()).joinToString("") { "%02x".format(it) }
        return File(File(cacheDirectory, "remote-images"), name)
    }
    private fun writeDisk(key: String, bytes: ByteArray) {
        runCatching { diskFile(key).apply { parentFile?.mkdirs() }.writeBytes(bytes) }
    }
}
