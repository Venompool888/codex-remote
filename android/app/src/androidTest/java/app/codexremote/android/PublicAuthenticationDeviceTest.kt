package app.codexremote.android

import android.test.InstrumentationTestCase
import org.json.JSONObject
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

/** Explicit public integration probe; never reads or replaces the application's saved credential. */
@Suppress("DEPRECATION")
class PublicAuthenticationDeviceTest : InstrumentationTestCase() {
    fun testInvalidCredentialIsRejectedWithSafeRecoveryMessage() {
        val config = java.io.File(instrumentation.targetContext.filesDir, "qa-public-auth-endpoint.txt")
        if (!config.exists()) return // Opt in by supplying a test endpoint; ordinary device suites stay offline.
        val endpoint = config.readText().trim()
        config.delete()
        val uri = java.net.URI(endpoint)
        assertEquals("https", uri.scheme)
        assertNull(uri.userInfo)
        assertNull(uri.query)
        val done = CountDownLatch(1)
        val authenticated = AtomicBoolean(false)
        val reason = AtomicReference<String>()
        val client = RemoteClient(object : RemoteClient.Listener {
            override fun onConnected() { authenticated.set(true); done.countDown() }
            override fun onDisconnected(value: String) { reason.set(value); done.countDown() }
            override fun onMessage(message: JSONObject) = Unit
        })
        try {
            client.connect(endpoint, "qa-deliberately-invalid-token")
            assertTrue("Public host did not respond within 20 seconds", done.await(20, TimeUnit.SECONDS))
            assertFalse("Public host accepted an invalid credential", authenticated.get())
            assertEquals(ConnectionFailureText.http(401), reason.get())
            assertEquals("Device authorization required", ConnectionFailureText.status(reason.get()))
            assertFalse(reason.get().contains(endpoint))
            assertFalse(reason.get().contains("qa-deliberately-invalid-token"))
        } finally { client.close(); config.delete() }
    }
}
