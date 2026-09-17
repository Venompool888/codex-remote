package app.codexremote.android

import com.sun.net.httpserver.HttpServer
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.net.InetSocketAddress
import java.nio.file.Files
import java.security.MessageDigest

class AttachmentTransferTest {
    @Test fun diagnosticHttpFailureIncludesStatusAndObserverFailureDoesNotChangeTransferError() {
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/v2/attachments") { exchange ->
            exchange.sendResponseHeaders(403, -1)
            exchange.close()
        }
        server.start()
        try {
            var observed = false
            val transfer = AttachmentTransfer { _, method, status ->
                assertEquals("GET", method)
                assertEquals(403, status)
                observed = true
                error("Diagnostic storage unavailable")
            }
            try {
                transfer.describe("http://127.0.0.1:${server.address.port}", "test-token", "12345678-1234-1234-1234-123456789abc")
                fail("Expected permission failure")
            } catch (error: java.io.IOException) {
                assertTrue(error.message.orEmpty().contains("permission expired or denied"))
            }
            assertTrue(observed)
        } finally { server.stop(0) }
    }
    @Test fun historicalPreviewFailureDistinguishesMissingAndDifferentDeviceWithoutLeakingBody() {
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        var status = 403
        server.createContext("/v2/attachments/") { exchange ->
            val body = "private host path and credential must not be displayed".toByteArray()
            exchange.sendResponseHeaders(status, body.size.toLong())
            exchange.responseBody.use { it.write(body) }
        }
        server.start()
        try {
            for (code in listOf(401, 403, 404, 410, 500)) {
                status = code
                try {
                    AttachmentTransfer().preview("http://127.0.0.1:${server.address.port}", "test-token", "12345678-1234-1234-1234-123456789abc")
                    fail("expected a read failure")
                } catch (error: AttachmentReadException) {
                    assertEquals(attachmentReadFailure(code), error.message)
                    assertFalse(error.message.orEmpty().contains("private host path"))
                    assertFalse(error.message.orEmpty().contains("test-token"))
                }
            }
        } finally { server.stop(0) }
    }
    @Test fun displaysOnlyValidatedSizeMetadataWithSafeLegacyFallback() {
        assertTrue(attachmentSizeFailure(JSONObject().put("maxBytes",5*1024*1024)).contains("5 MiB"))
        for (value in listOf(JSONObject.NULL, "/private/token", "5242880", -1, 0, 1.5, Long.MAX_VALUE)) {
            assertEquals("Attachment exceeds the host size limit", attachmentSizeFailure(JSONObject().put("maxBytes", value)))
        }
        assertEquals("Attachment exceeds the host size limit", attachmentSizeFailure(JSONObject()))
    }
    @Test fun resumesCheckpointedIdAfterInterruptionWithoutReinitializing() {
        val file = Files.createTempFile("transfer", ".txt").toFile()
        val bytes = "abcdef".toByteArray()
        file.writeBytes(bytes)
        val id = "12345678-1234-1234-1234-123456789012"
        val digest = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
        var offset = 0
        var inits = 0
        var completed = false
        val received = java.io.ByteArrayOutputStream()
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/v2/attachments") { exchange ->
            assertEquals("Bearer test", exchange.requestHeaders.getFirst("Authorization"))
            val path = exchange.requestURI.path
            if (path == "/v2/attachments") inits++
            if (exchange.requestMethod == "PUT") {
                val chunk = exchange.requestBody.readBytes()
                assertEquals("bytes $offset-${offset + chunk.size - 1}/6", exchange.requestHeaders.getFirst("Content-Range"))
                received.write(chunk)
                offset += chunk.size
            }
            if (path.endsWith("/complete")) completed = true
            val descriptor = JSONObject().put("id", id).put("size", 6).put("sha256", digest)
                .put("status", if (completed) "complete" else "uploading").put("offset", offset)
            val response = JSONObject().put("attachment", descriptor).put("chunkBytes", 3).toString().toByteArray()
            exchange.sendResponseHeaders(200, response.size.toLong())
            exchange.responseBody.use { it.write(response) }
        }
        server.start()
        try {
            val item = JSONObject().put("name", "a.txt").put("mimeType", "text/plain").put("size", 6)
                .put("sha256", digest).put("localFile", file.absolutePath)
            val endpoint = "http://127.0.0.1:${server.address.port}"
            var saved = JSONObject()
            try {
                AttachmentTransfer().upload(endpoint, "test", item, { offset >= 3 }) { saved = JSONObject(it.toString()) }
                fail("must interrupt")
            } catch (_: IllegalStateException) { }
            assertEquals(3, offset)
            assertEquals(id, saved.getString("remoteId"))
            AttachmentTransfer().upload(endpoint, "test", saved, { false }) { }
            assertEquals(1, inits)
            assertTrue(completed)
            assertArrayEquals(bytes, received.toByteArray())
        } finally { server.stop(0); file.delete() }
    }
    @Test fun structuredRejectionsArePermanentSafeAttachmentErrors() {
        var code = "invalid_content"
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/v2/attachments") { exchange ->
            val response = JSONObject().put("code", code).put("error", "private-host-path /private/qa-secret token=do-not-display").toString().toByteArray()
            exchange.sendResponseHeaders(400, response.size.toLong())
            exchange.responseBody.use { it.write(response) }
        }
        server.start()
        try {
            for (reason in listOf("invalid_content", "unsupported_type", "quota_exceeded", "too_large")) {
                code = reason
                val item = JSONObject().put("name", "bad.pdf").put("mimeType", "application/pdf").put("size", 1).put("sha256", "0".repeat(64))
                try {
                    AttachmentTransfer().upload("http://127.0.0.1:${server.address.port}", "test", item, { false }) { }
                    fail("Expected server rejection")
                } catch (error: java.io.IOException) {
                    assertTrue("Queue must classify structured rejection as terminal", error.message.orEmpty().startsWith("Attachment"))
                    assertFalse(error.message.orEmpty().contains("private-host-path"))
                    assertFalse(error.message.orEmpty().contains("do-not-display"))
                    if (reason == "invalid_content") assertTrue(error.message.orEmpty().contains("contents do not match"))
                }
            }
        } finally { server.stop(0) }
    }

}
