package app.codexremote.android

import org.junit.Assert.*
import org.junit.Test

class ConnectionFailureTextTest {
    @Test fun authFailuresOfferRecoveryInsteadOfGenericNetworkAdvice() {
        assertTrue(ConnectionFailureText.closed(4001).contains("authorization changed"))
        assertTrue(ConnectionFailureText.http(401).contains("Pair this host again"))
        assertTrue(ConnectionFailureText.http(403).contains("tunnel access"))
        assertEquals("Device authorization required", ConnectionFailureText.status(ConnectionFailureText.http(401)))
        assertEquals("Access denied", ConnectionFailureText.status(ConnectionFailureText.http(403)))
        assertNull(ConnectionFailureText.status("private-path-or-token"))
        assertEquals("Device authorization required · example.com", conversationConnectionText("https://example.com", emptyList(), false, ConnectionFailureText.status(ConnectionFailureText.http(401))))
        assertEquals("Connected · example.com", conversationConnectionText("https://example.com", emptyList(), true, "stale failure"))
    }
    @Test fun protocolRateLimitAndRestartAreDistinct() {
        assertTrue(ConnectionFailureText.closed(1002).contains("versions"))
        assertTrue(ConnectionFailureText.closed(1012).contains("restarting"))
        assertTrue(ConnectionFailureText.http(429).contains("Waiting"))
        assertEquals(ConnectionFailureText.closed(4999), ConnectionFailureText.closed(1000))
        assertEquals(ConnectionFailureText.http(null), ConnectionFailureText.http(502))
    }
}
