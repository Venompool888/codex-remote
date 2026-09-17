package app.codexremote.android

import org.junit.Assert.*
import org.junit.Test

class RemoteImageIdentityTest {
    @Test fun samePathCannotReuseAnotherHostDeviceOrTasksImage() {
        val source = "/private/project/output.png"
        val original = RemoteImageIdentity.key("https://a.example", "phone-a", "task-a", source)
        assertEquals(original, RemoteImageIdentity.key("https://a.example", "phone-a", "task-a", source))
        assertNotEquals(original, RemoteImageIdentity.key("https://b.example", "phone-a", "task-a", source))
        assertNotEquals(original, RemoteImageIdentity.key("https://a.example", "phone-b", "task-a", source))
        assertNotEquals(original, RemoteImageIdentity.key("https://a.example", "phone-a", "task-b", source))
        assertNotEquals(original, RemoteImageIdentity.key("https://a.example", "phone-a", "task-a", source + "2"))
        assertFalse(original.contains("private"))
    }
    @Test fun fieldBoundariesCannotCollide() {
        assertNotEquals(RemoteImageIdentity.key("a", "bc", "d", "e"), RemoteImageIdentity.key("ab", "c", "d", "e"))
        assertNotEquals(RemoteImageIdentity.key("a\u0000b", "c", "d", "e"), RemoteImageIdentity.key("a", "b\u0000c", "d", "e"))
    }
}
