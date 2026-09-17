package app.codexremote.android

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.nio.file.Files

class DiagnosticLogTest {
    @Test fun rotationRetainsRecentWholeRecordsAcrossRestartAndClearRemovesBothFiles() {
        val dir = Files.createTempDirectory("diagnostics").toFile()
        try {
            val store = DiagnosticLogStore(dir, 100)
            repeat(10) { store.append("record-$it " + "界".repeat(12)) }
            val restored = DiagnosticLogStore(dir, 100).read()
            assertTrue(restored.contains("record-9"))
            assertFalse(restored.contains("record-0"))
            assertFalse(restored.contains('\uFFFD'))
            assertTrue(dir.listFiles()!!.sumOf { it.length() } <= 200)
            store.clear()
            assertEquals("", DiagnosticLogStore(dir, 100).read())
        } finally { dir.deleteRecursively() }
    }

    @Test fun safeHostDropsUrlUserInfoQueryAndFragment() {
        assertEquals("https://example.org:8443", DiagnosticRedaction.host("https://user:password@example.org:8443/private?token=secret#hidden"))
        val cleaned = DiagnosticRedaction.clean("failed at https://user:password@example.org/private?token=secret#hidden")
        assertEquals("failed at https://example.org", cleaned)
    }

    @Test fun redactsCredentialsBeforePersistingWithoutHidingMacPermissionError() {
        val clean = DiagnosticRedaction.clean("Bearer bearerSecret token=tokenSecret api_key=keySecret \"password\":\"passwordSecret\" sk-1234567890123456 rawCredential\nfailed to load configuration: Operation not permitted (os error 1)", listOf("rawCredential"))
        listOf("bearerSecret", "tokenSecret", "keySecret", "passwordSecret", "sk-1234567890123456", "rawCredential").forEach { assertFalse(clean.contains(it)) }
        assertTrue(clean.contains("failed to load configuration: Operation not permitted (os error 1)"))
    }

    @Test fun capturesFullErrorRequestContextAndOutcomeButNeverRequestBodyOrEchoedPrompt() {
        val records = mutableListOf<String>()
        val tracker = RpcDiagnostics { event, host, detail, secrets -> records += DiagnosticRedaction.record(event, host, detail, secrets) }
        tracker.begin("request-1", "https://mac.example", "turn/start", JSONObject("""{"threadId":"thread-1","input":[{"type":"text","text":"private user message"},{"type":"remoteAttachment","attachmentId":"attachment-secret"}]}"""), "credential-secret")
        assertFalse(records.joinToString().contains("private user message"))
        assertFalse(records.joinToString().contains("attachment-secret"))
        tracker.finish("request-1", "https://mac.example", "rpc_error", "Operation not permitted (os error 1)\nprivate user message credential-secret", "-32603")
        val error = records.last()
        assertTrue(error.contains("method=turn/start"))
        assertTrue(error.contains("threadId=thread-1"))
        assertTrue(error.contains("code=-32603"))
        assertTrue(error.contains("durationMs="))
        assertTrue(error.contains("Operation not permitted (os error 1)"))
        assertFalse(error.contains("private user message"))
        assertFalse(error.contains("credential-secret"))
    }

    @Test fun differentHostCannotConsumeRequestCorrelationAndTimeoutHasExplicitUnknownOutcome() {
        val records = mutableListOf<String>()
        val tracker = RpcDiagnostics { _, _, detail, _ -> records += detail }
        tracker.begin("id", "https://mac", "thread/start", JSONObject().put("cwd", "/home/example/Desktop/project"), null)
        tracker.finish("id", "https://other", "rpc_error", "unrelated")
        assertTrue(records.last().contains("method=unknown"))
        tracker.finish("id", "https://mac", "timeout", "outcome unknown")
        assertTrue(records.last().contains("method=thread/start"))
        assertTrue(records.last().contains("cwd=/home/example/Desktop/project"))
        assertTrue(records.last().contains("outcome unknown"))
    }

    @Test fun oversizedErrorIsExplicitlyBounded() {
        val clean = DiagnosticRedaction.clean("界".repeat(100000))
        assertTrue(clean.contains("truncated"))
        assertTrue(clean.toByteArray().size < 64 * 1024)
    }
}
