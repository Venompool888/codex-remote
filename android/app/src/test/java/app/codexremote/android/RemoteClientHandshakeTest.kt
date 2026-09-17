package app.codexremote.android

import okhttp3.Request
import okhttp3.WebSocket
import okio.ByteString
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import kotlin.concurrent.thread

class RemoteClientHandshakeTest {
    private class RecordingSocket(val beforeSend: () -> Unit = {}) : WebSocket {
        val frames = mutableListOf<String>()
        override fun request() = Request.Builder().url("http://localhost/v2/ws").build()
        override fun queueSize() = 0L
        override fun send(text: String): Boolean { beforeSend(); frames += text; return true }
        override fun send(bytes: ByteString) = false
        override fun close(code: Int, reason: String?) = true
        override fun cancel() = Unit
    }

    private fun client() = RemoteClient(object : RemoteClient.Listener {
        override fun onConnected() = Unit
        override fun onDisconnected(reason: String) = Unit
        override fun onMessage(message: JSONObject) = Unit
    })

    private fun RemoteClient.set(name: String, value: Any) {
        javaClass.getDeclaredField(name).apply { isAccessible = true }.set(this, value)
    }

    private fun RemoteClient.pending(name: String): Int =
        (javaClass.getDeclaredField(name).apply { isAccessible = true }.get(this) as Map<*, *>).size

    private fun rejected(action: () -> Unit) {
        try { action(); fail("An unnegotiated socket must reject business messages") }
        catch (error: IllegalStateException) { assertTrue(error.message!!.contains("connected")) }
    }

    @Test fun openTransportDoesNotAllowRequestsOrAnswersBeforeHandshake() {
        val client = client()
        val socket = RecordingSocket()
        client.set("socket", socket)
        client.set("generation", 2L)
        // Stale capabilities must not cause rejected operations to be replayed later.
        client.set("negotiatedProtocolVersion", 2)
        client.set("negotiatedCapabilities", JSONObject().put("interactions", JSONObject().put("acknowledgement", true)))
        rejected { client.rpc("read", "thread/list") }
        rejected { client.rpc("write", "turn/start") }
        rejected { client.answer("approval", JSONObject()) }
        rejected { client.answerError("unsupported", "Unsupported") }
        assertTrue(socket.frames.isEmpty())
        assertEquals(0, client.pending("pendingWriteRpcs"))
        assertEquals(0, client.pending("pendingAnswers"))
    }

    @Test fun previousConnectionHandshakeCannotAuthorizeReplacementSocket() {
        val client = client()
        val socket = RecordingSocket()
        client.set("socket", socket)
        client.set("generation", 4L)
        client.set("negotiatedConnectionEpoch", 2L)
        rejected { client.rpc("read", "thread/list") }
        assertTrue(socket.frames.isEmpty())
    }

    @Test fun negotiatedConnectionSendsAndCloseImmediatelyRevokesReadiness() {
        for (version in listOf(1, 2)) {
            val client = client()
            val socket = RecordingSocket()
            client.set("socket", socket)
            client.set("generation", 2L)
            client.set("negotiatedConnectionEpoch", 2L)
            client.set("negotiatedProtocolVersion", version)
            client.rpc("read", "thread/list")
            client.answer("approval", JSONObject().put("approved", true))
            assertEquals(listOf("rpc", "server_response"), socket.frames.map { JSONObject(it).getString("type") })
            client.close()
            rejected { client.rpc("late", "thread/list") }
            assertEquals(2, socket.frames.size)
        }
    }
    @Test fun closeCannotReplaceConnectionBetweenReadinessCheckAndSend() {
        val enteredSend = CountDownLatch(1)
        val releaseSend = CountDownLatch(1)
        val closing = CountDownLatch(1)
        val closed = CountDownLatch(1)
        val failure = AtomicReference<Throwable?>()
        val client = client()
        val socket = RecordingSocket {
            enteredSend.countDown()
            check(releaseSend.await(5, TimeUnit.SECONDS))
        }
        client.set("socket", socket)
        client.set("generation", 2L)
        client.set("negotiatedConnectionEpoch", 2L)
        val sender = thread {
            try { client.rpc("read", "thread/list") } catch (error: Throwable) { failure.set(error) }
        }
        var closer: Thread? = null
        try {
            assertTrue(enteredSend.await(5, TimeUnit.SECONDS))
            closer = thread { closing.countDown(); client.close(); closed.countDown() }
            assertTrue(closing.await(5, TimeUnit.SECONDS))
            assertFalse("close must wait for the accepted send", closed.await(100, TimeUnit.MILLISECONDS))
        } finally {
            releaseSend.countDown()
            sender.join(5_000)
            closer?.join(5_000)
        }
        assertNull(failure.get())
        assertEquals(0L, closed.count)
        assertEquals(1, socket.frames.size)
        rejected { client.rpc("late", "thread/list") }
    }

}
