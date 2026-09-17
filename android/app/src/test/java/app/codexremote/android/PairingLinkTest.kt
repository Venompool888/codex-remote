package app.codexremote.android

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.net.URLEncoder
import java.nio.charset.StandardCharsets

class PairingLinkTest {
    @Test fun acceptsEncodedHttpsHostAndOpaqueCode() {
        val server = "https://remote.example.test:18788"
        val code = "abcDEF0123456789_-opaque"
        val link = "codexremote://pair?server=${encode(server)}&code=${encode(code)}"

        assertEquals(PairingLink(server, code), parsePairingLink(link))
    }

    @Test fun normalizesOneOrMoreTrailingServerSlashes() {
        val link = "codexremote://pair?server=${encode("http://127.0.0.1:8787///")}&code=abcDEF0123456789"
        assertEquals("http://127.0.0.1:8787", parsePairingLink(link)?.serverUrl)
    }

    @Test fun rejectsMalformedOrBroaderLinks() {
        val validServer = encode("https://remote.example.test")
        listOf(
            null,
            "https://remote.example.test/pair?code=abcDEF0123456789",
            "codexremote://other?server=$validServer&code=abcDEF0123456789",
            "codexremote://pair/path?server=$validServer&code=abcDEF0123456789",
            "codexremote://pair?server=$validServer",
            "codexremote://pair?server=$validServer&code=short",
            "codexremote://pair?server=${encode("ftp://remote.example.test")}&code=abcDEF0123456789",
            "codexremote://pair?server=${encode("https://user:secret@remote.example.test")}&code=abcDEF0123456789",
            "codexremote://pair?server=$validServer&server=$validServer&code=abcDEF0123456789",
            "codexremote://pair?server=$validServer&code=abcDEF0123456789&extra=value",
        ).forEach { assertNull(it, parsePairingLink(it)) }
    }

    private fun encode(value: String): String = URLEncoder.encode(value, StandardCharsets.UTF_8.name())
}
