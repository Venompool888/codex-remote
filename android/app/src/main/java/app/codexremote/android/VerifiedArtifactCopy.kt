package app.codexremote.android

import java.io.File
import java.io.FileOutputStream
import java.io.InputStream
import java.security.MessageDigest

/** The caller exposes the file only after exact length and digest verification succeeds. */
internal object VerifiedArtifactCopy {
    fun copy(input: InputStream, temporary: File, size: Long, sha256: String, progress: (Int) -> Unit = {}) {
        try {
            require(size in 1..50L * 1024 * 1024 && sha256.matches(Regex("[a-fA-F0-9]{64}"))) { "Invalid artifact metadata" }
            val digest = MessageDigest.getInstance("SHA-256")
            var received = 0L
            var lastProgress = -1
            FileOutputStream(temporary).use { output ->
                val buffer = ByteArray(64 * 1024)
                while (true) {
                    val count = input.read(buffer)
                    if (count < 0) break
                    received += count
                    check(received <= size) { "Artifact size mismatch" }
                    output.write(buffer, 0, count)
                    digest.update(buffer, 0, count)
                    val percent = (received * 100 / size).toInt()
                    if (percent != lastProgress) { lastProgress = percent; progress(percent) }
                }
                val hash = digest.digest().joinToString("") { "%02x".format(it) }
                check(received == size && hash.equals(sha256, ignoreCase = true)) { "Artifact integrity check failed" }
                output.fd.sync()
            }
        } catch (error: Throwable) {
            temporary.delete()
            throw error
        }
    }
}
