package app.codexremote.android

import org.junit.Assert.*
import org.junit.Test

class ProjectCreationValidationTest {
    @Test fun acceptsInitialPairingAndOpaqueKnownProjects() {
        assertNull(projectInputError("https://host.example", "/project"))
        assertNull(projectInputError("http://127.0.0.1:8787", "remote-workspace://" + "a".repeat(64)))
    }
    @Test fun rejectsMissingHostAndInvalidWorkspace() {
        for (server in listOf("", "hostname", "ftp://host", "https://user:password@host", "https://host?token=x")) {
            assertNotNull(projectInputError(server, "/project"))
        }
        for (path in listOf("", "relative/path", "remote-workspace://invalid")) {
            assertNotNull(projectInputError("https://host", path))
        }
    }
    @Test fun staleCancelledOrOtherHostReplyCannotSaveProject() {
        val original = RemoteProject("one", "Project", "Host A", "https://a", "/project")
        assertTrue(acceptsProjectValidation(original, original, "https://a"))
        assertFalse(acceptsProjectValidation(null, original, "https://a"))
        assertFalse(acceptsProjectValidation(original.copy(id = "two"), original, "https://a"))
        assertFalse(acceptsProjectValidation(original, original, "https://b"))
        assertFalse(acceptsProjectValidation(original.copy(workspace = "/other"), original, "https://a"))
    }
}
