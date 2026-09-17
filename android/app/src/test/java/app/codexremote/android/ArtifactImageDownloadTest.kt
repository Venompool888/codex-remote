package app.codexremote.android

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.net.ServerSocket
import java.security.MessageDigest
import java.util.concurrent.atomic.AtomicReference
import kotlin.concurrent.thread

class ArtifactImageDownloadTest {
    private val bytes = byteArrayOf(1, 2, 3, 4)
    private fun descriptor() = JSONObject().put("id", "a".repeat(64)).put("size", bytes.size)
        .put("mimeType", "image/png").put("sha256", MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) })
    private fun serve(status: Int = 200, body: ByteArray = bytes, action: (String) -> Unit) {
        ServerSocket(0).use { server ->
            server.soTimeout = 4000
            val failure = AtomicReference<Throwable>()
            val worker = thread {
                try {
                    server.accept().use { socket ->
                        socket.soTimeout = 4000
                        val reader = socket.getInputStream().bufferedReader()
                        assertEquals("GET /v2/artifacts/${"a".repeat(64)} HTTP/1.1", reader.readLine())
                        val headers = generateSequence { reader.readLine().takeUnless { it.isNullOrEmpty() } }.toList()
                        assertTrue(headers.any { it == "Authorization: Bearer fixture-only" })
                        socket.getOutputStream().write(("HTTP/1.1 $status Test\r\nContent-Length: ${body.size}\r\nConnection: close\r\nLocation: http://example.invalid/private\r\n\r\n").toByteArray() + body)
                    }
                } catch (error: Throwable) { failure.set(error) }
            }
            try { action("http://127.0.0.1:${server.localPort}") } finally { worker.join(5000) }
            failure.get()?.let { throw AssertionError("HTTP fixture failed", it) }
        }
    }
    @Test fun deviceCredentialAndRestrictedIdDownloadVerifiedBytes() = serve { server ->
        assertArrayEquals(bytes, ArtifactImageDownload.read(server, "fixture-only", descriptor()))
    }
    @Test fun changedOversizedAndTruncatedResponsesAreRejected() {
        for (body in listOf(byteArrayOf(4,3,2,1), bytes + 5, byteArrayOf(1))) serve(body = body) { server ->
            assertTrue(runCatching { ArtifactImageDownload.read(server, "fixture-only", descriptor()) }.isFailure)
        }
    }
    @Test fun redirectsAreNotFollowed() = serve(status = 302) { server ->
        assertTrue(runCatching { ArtifactImageDownload.read(server, "fixture-only", descriptor()) }.isFailure)
    }
    @Test fun unsafeDescriptorsAreRejectedBeforeNetwork() {
        for (value in listOf(descriptor().put("id", "../../secret"), descriptor().put("size", 13L * 1024 * 1024), descriptor().put("mimeType", "text/html"))) {
            assertTrue(runCatching { ArtifactImageDownload.read("https://invalid.invalid", "fixture-only", value) }.isFailure)
        }
    }
}
