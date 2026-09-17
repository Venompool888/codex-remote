package app.codexremote.android

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.net.ServerSocket
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

class ConnectionPrefixTest {
    @Test fun pairingAndLegacyFallbackKeepConfiguredBasePath() {
        ServerSocket(0, 2, java.net.InetAddress.getLoopbackAddress()).use { server ->
            server.soTimeout = 10_000
            val requests = java.util.Collections.synchronizedList(mutableListOf<String>())
            val complete = CountDownLatch(1)
            val responder = thread(isDaemon = true) {
                repeat(2) { index ->
                    server.accept().use { socket ->
                        socket.soTimeout = 5000
                        val input = socket.getInputStream().bufferedReader()
                        requests += input.readLine()
                        var length = 0
                        while (true) {
                            val line = input.readLine() ?: break
                            if (line.isEmpty()) break
                            if (line.startsWith("Content-Length:", true)) length = line.substringAfter(':').trim().toInt()
                        }
                        repeat(length) { input.read() }
                        val body = if (index == 0) "{}" else "{\"token\":\"synthetic-test-token\"}"
                        val status = if (index == 0) "404 Not Found" else "200 OK"
                        socket.getOutputStream().write("HTTP/1.1 $status\r\nContent-Type: application/json\r\nContent-Length: ${body.length}\r\nConnection: close\r\n\r\n$body".toByteArray())
                    }
                }
            }
            val client = RemoteClient(object : RemoteClient.Listener {
                override fun onConnected() {}
                override fun onDisconnected(reason: String) {}
                override fun onMessage(message: JSONObject) {}
            })
            var success = false
            client.pair("http://127.0.0.1:${server.localPort}/remote/nested", "synthetic-code", "Test") {
                success = it.isSuccess; complete.countDown()
            }
            assertTrue(complete.await(10, TimeUnit.SECONDS))
            responder.join(1000)
            assertTrue(success)
            assertEquals(listOf("POST /remote/nested/v2/pair HTTP/1.1", "POST /remote/nested/v1/pair HTTP/1.1"), requests)
            client.close()
        }
    }
}
