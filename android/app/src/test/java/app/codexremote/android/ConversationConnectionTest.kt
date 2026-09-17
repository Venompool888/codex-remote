package app.codexremote.android
import org.junit.Assert.*
import org.junit.Test

class ConversationConnectionTest {
    @Test fun activeTransportSelectsOnlyItsOwnHostName() {
        val projects = listOf(
            RemoteProject("one", "Project", "Other host", "https://other.test", "/private/a"),
            RemoteProject("two", "Project", "Remote host", "https://public.test/", "/private/b"))
        assertEquals("Connected · Remote host", conversationConnectionText("https://public.test", projects, true))
        assertEquals("Offline · reconnecting · Other host", conversationConnectionText("https://other.test", projects, false))
    }
    @Test fun fallbackIncludesOnlyHostnameNotCredentialsOrPath() {
        assertEquals("Connected · public.test", conversationConnectionText("https://user:secret@public.test/private?token=secret", emptyList(), true))
        assertEquals("Offline · reconnecting · Remote host", conversationConnectionText(null, emptyList(), false))
    }
}
