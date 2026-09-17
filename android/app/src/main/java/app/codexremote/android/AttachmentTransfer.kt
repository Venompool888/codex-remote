package app.codexremote.android

import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.io.IOException
import java.io.RandomAccessFile
import java.security.MessageDigest
import java.util.concurrent.TimeUnit

internal fun attachmentSizeFailure(error: JSONObject): String {
    val limit = error.opt("maxBytes")
    val bytes = when (limit) { is Int -> limit.toLong(); is Long -> limit; else -> 0L }
    return if (bytes in 1..(1024L * 1024 * 1024) && bytes % (1024 * 1024) == 0L)
        "This file type is limited to ${bytes / (1024 * 1024)} MiB on this host; choose a smaller file"
    else "Attachment exceeds the host size limit"
}

/** Uses the existing v2 wire protocol; memory usage is bounded by one chunk. */
internal fun attachmentReadFailure(status: Int): String = when (status) {
    401 -> "Attachment access expired. Reconnect to this host and retry."
    403 -> "Attachment access denied. Use the connection that uploaded this file."
    404, 410 -> "This attachment is no longer on the host, or this host needs an update."
    else -> "Attachment could not be loaded. Check the connection and tap to retry."
}

internal class AttachmentReadException(message: String) : IOException(message)

class AttachmentTransfer(private val onHttpResult: (url: String, method: String, status: Int) -> Unit = { _, _, _ -> }) {
    private val http = OkHttpClient.Builder().callTimeout(45, TimeUnit.SECONDS)
        .followRedirects(false).followSslRedirects(false).build()

    fun describe(server: String, token: String, id: String): JSONObject {
        require(Regex("[a-f0-9-]{36}").matches(id)) { "Invalid attachment ID" }
        return request(server.trimEnd('/') + "/v2/attachments/$id", token).getJSONObject("attachment")
    }

    fun upload(server: String, token: String, item: JSONObject,
               cancelled: () -> Boolean, checkpoint: (JSONObject) -> Unit): JSONObject {
        val base = server.trimEnd('/') + "/v2/attachments"
        var id = item.optString("remoteId")
        var descriptor: JSONObject
        var chunkSize = item.optInt("chunkBytes", 1024 * 1024).coerceIn(1, 1024 * 1024)
        if (id.isBlank()) {
            check(!cancelled()) { "Upload cancelled" }
            val init = JSONObject().put("name", item.getString("name"))
                .put("uploadKey", item.optString("localId").takeIf { it.isNotBlank() })
                .put("mimeType", item.getString("mimeType")).put("size", item.getLong("size"))
                .put("sha256", item.getString("sha256"))
            val result = request(base, token, "POST", init.toString().toByteArray(), json = true)
            descriptor = result.getJSONObject("attachment")
            id = descriptor.getString("id")
            require(Regex("[a-f0-9-]{36}").matches(id)) { "Invalid attachment ID" }
            chunkSize = result.optInt("chunkBytes", chunkSize).coerceIn(1, 1024 * 1024)
            // Save the server ID before writing any data so all retries resume this resource.
            item.put("remoteId", id).put("chunkBytes", chunkSize)
            checkpoint(item)
        } else {
            require(Regex("[a-f0-9-]{36}").matches(id)) { "Invalid attachment ID" }
            descriptor = request("$base/$id", token).getJSONObject("attachment")
        }
        val size = item.getLong("size")
        require(descriptor.getLong("size") == size && descriptor.getString("sha256") == item.getString("sha256")) {
            "Remote attachment integrity mismatch"
        }
        if (descriptor.optString("status") == "complete") return descriptor
        var offset = descriptor.getLong("offset")
        require(offset in 0..size) { "Invalid upload offset" }
        RandomAccessFile(item.getString("localFile"), "r").use { file ->
            require(file.length() == size) { "Local attachment changed; remove and select it again" }
            while (offset < size) {
                check(!cancelled()) { "Upload cancelled" }
                val bytes = ByteArray(minOf(chunkSize.toLong(), size - offset).toInt())
                file.seek(offset)
                file.readFully(bytes)
                val hash = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
                descriptor = request("$base/$id", token, "PUT", bytes,
                    mapOf("Content-Range" to "bytes $offset-${offset + bytes.size - 1}/$size", "X-Chunk-SHA256" to hash))
                    .getJSONObject("attachment")
                val next = descriptor.getLong("offset")
                require(next == offset + bytes.size) { "Invalid upload offset" }
                offset = next
                item.put("progress", (offset * 100 / size).toInt())
                checkpoint(item)
            }
        }
        check(!cancelled()) { "Upload cancelled" }
        return request("$base/$id/complete", token, "POST", ByteArray(0)).getJSONObject("attachment")
    }

    fun preview(server: String, token: String, id: String): ByteArray {
        require(Regex("[a-f0-9-]{36}").matches(id))
        val request = Request.Builder().url(server.trimEnd('/') + "/v2/attachments/$id/preview")
            .header("Authorization", "Bearer $token").build()
        http.newCall(request).execute().use { response ->
            if (!response.isSuccessful) throw AttachmentReadException(attachmentReadFailure(response.code))
            val expected = response.header("Content-Length")?.toLongOrNull() ?: throw IOException("Preview length unavailable")
            require(expected in 1..20L * 1024 * 1024) { "Preview exceeds the size limit" }
            val sha = response.header("X-Content-SHA256") ?: throw IOException("Preview integrity unavailable")
            val output = java.io.ByteArrayOutputStream()
            val digest = MessageDigest.getInstance("SHA-256")
            response.body!!.byteStream().use { input ->
                val buffer = ByteArray(64 * 1024)
                while (true) {
                    val read = input.read(buffer)
                    if (read < 0) break
                    require(output.size().toLong() + read <= expected) { "Preview length mismatch" }
                    output.write(buffer, 0, read); digest.update(buffer, 0, read)
                }
            }
            require(output.size().toLong() == expected && digest.digest().joinToString("") { "%02x".format(it) } == sha) { "Preview integrity mismatch" }
            return output.toByteArray()
        }
    }

    fun delete(server: String, token: String, id: String) {
        require(Regex("[a-f0-9-]{36}").matches(id))
        try { request(server.trimEnd('/') + "/v2/attachments/$id", token, "DELETE") }
        catch (error: IOException) { if (!error.message.orEmpty().startsWith("Attachment expired")) throw error }
    }

    private fun request(url: String, token: String, method: String = "GET", bytes: ByteArray? = null,
                        headers: Map<String, String> = emptyMap(), json: Boolean = false): JSONObject {
        val builder = Request.Builder().url(url).header("Authorization", "Bearer $token")
        headers.forEach { (name, value) -> builder.header(name, value) }
        builder.method(method, bytes?.toRequestBody(if (json) "application/json".toMediaType() else null))
        http.newCall(builder.build()).execute().use { response ->
            runCatching { onHttpResult(url, method, response.code) }
            // Never show an untrusted HTTP response, token, URL, or filesystem path in an error.
            if (!response.isSuccessful) {
                val error = runCatching { JSONObject(response.peekBody(4096).string()) }.getOrDefault(JSONObject())
                val code = error.optString("code")
                val safeReason = when (code) {
                    "unsupported_type" -> "This host does not support this file type; update the host or choose an image, PDF, ZIP, text or code file"
                    "quota_exceeded" -> "Host attachment storage is full for this device; remove unused attachments and retry"
                    "invalid_content" -> "File contents do not match its type, or the file is damaged"
                    "too_large" -> attachmentSizeFailure(error)
                    else -> null
                }
                throw IOException(safeReason?.let { "Attachment: $it" } ?: when (response.code) {
                401, 403 -> "Attachment permission expired or denied; reconnect or pair this device again"
                404, 410 -> "Attachment expired or was removed; remove and select it again"
                409 -> "Upload interrupted; retry to resume the confirmed offset"
                413 -> "Attachment exceeds the host limit"
                else -> "Attachment request failed (HTTP ${response.code}); check type, quota and connection"
            })
            }
            val text = response.body?.string().orEmpty()
            return if (text.isBlank()) JSONObject() else JSONObject(text)
        }
    }
}
