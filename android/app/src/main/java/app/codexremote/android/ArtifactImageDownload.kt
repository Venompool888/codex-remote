package app.codexremote.android

import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.security.MessageDigest
import java.util.concurrent.TimeUnit

/** Uses the same device-owned snapshot endpoint as saved artifacts, never a host path. */
internal object ArtifactImageDownload {
    private val http = OkHttpClient.Builder().callTimeout(60, TimeUnit.SECONDS)
        .followRedirects(false).followSslRedirects(false).build()
    fun read(server: String, token: String, artifact: JSONObject): ByteArray {
        val id = artifact.getString("id")
        val size = artifact.getLong("size")
        val hash = artifact.getString("sha256")
        require(Regex("[a-f0-9]{64}").matches(id) && Regex("[a-fA-F0-9]{64}").matches(hash))
        require(size in 1..12L * 1024 * 1024)
        require(artifact.optString("mimeType") in setOf("image/png", "image/jpeg", "image/gif", "image/webp"))
        return http.newCall(Request.Builder().url(server.trimEnd('/') + "/v2/artifacts/$id")
            .header("Authorization", "Bearer $token").build()).execute().use { response ->
            check(response.isSuccessful)
            val digest = MessageDigest.getInstance("SHA-256")
            val output = ByteArrayOutputStream(size.toInt())
            (response.body ?: error("Image unavailable")).byteStream().use { input ->
                val buffer = ByteArray(32 * 1024)
                var total = 0L
                while (true) {
                    val count = input.read(buffer)
                    if (count < 0) break
                    total += count
                    check(total <= size)
                    digest.update(buffer, 0, count)
                    output.write(buffer, 0, count)
                }
                check(total == size)
            }
            check(digest.digest().joinToString("") { "%02x".format(it) }.equals(hash, ignoreCase = true))
            output.toByteArray()
        }
    }
}
