package app.codexremote.android

import androidx.compose.ui.text.input.TextFieldValue
import app.codexremote.android.presentation.connections.AddConnectionController
import org.junit.Assert.*
import org.junit.Test

class AddConnectionTest {
    private val link = "codexremote://pair?server=https%3A%2F%2Fhost.example%3A8443%2Fremote&code=abcDEF0123456789"
    @Test fun addressKeepsPrefixAndHandlesIpv6AndDefaults() {
        assertEquals("https://host.example:8443/remote/nested", connectionServerUrl("https", "host.example", "8443", "/remote/nested/").getOrThrow())
        assertEquals("http://[::1]/remote", connectionServerUrl("http", "::1", "80", "/remote").getOrThrow())
        assertEquals("https://host.example", connectionServerUrl("https", "host.example", "443", "").getOrThrow())
        listOf("../x", "/a/../b", "/%2e%2e/b", "//evil", "/remote?code=x", "/a#b").forEach {
            assertTrue(it, connectionServerUrl("https", "host.example", "443", it).isFailure)
        }
        assertTrue(connectionServerUrl("https", "host.example/path", "443", "/").isFailure)
        assertTrue(connectionServerUrl("http", "host.example", "65536", "/").isFailure)
    }
    @Test fun scanRequiresConfirmationAndLateScanIsIgnored() {
        var submissions = 0
        val controller = AddConnectionController { _, _, _ -> submissions++ }
        controller.open()
        val old = controller.beginScan()!!
        controller.dismiss(); controller.open()
        controller.completeScan(old, link)
        assertFalse(controller.uiState.value.isConfirming)
        val current = controller.beginScan()!!
        controller.completeScan(current, link)
        assertTrue(controller.uiState.value.isConfirming)
        assertEquals("https://host.example:8443/remote", controller.uiState.value.serverPreview)
        assertEquals(0, submissions)
        controller.edit()
        assertEquals("/remote", controller.uiState.value.basePath.text)
        controller.submit(); controller.submit()
        assertEquals(1, submissions)
    }
    @Test fun protocolPreservesCustomPortAndRequiredFieldsBlockSubmit() {
        var submissions = 0
        val c = AddConnectionController { _, _, _ -> submissions++ }
        c.open(); c.updateProtocol("http")
        assertEquals("80", c.uiState.value.port.text)
        c.updatePort(TextFieldValue("8443")); c.updateProtocol("https")
        assertEquals("8443", c.uiState.value.port.text)
        c.submit(); assertEquals(0, submissions)
        c.updateHost(TextFieldValue("host.example")); c.submit(); assertEquals(0, submissions)
        c.updateCode(TextFieldValue("test-code")); c.submit(); assertEquals(1, submissions)
    }
    @Test fun pairingFailureRetainsInputAndCompletionClearsSecret() {
        var ticket = -1L
        val c = AddConnectionController { _, _, id -> ticket = id }
        c.acceptLink(link); c.submit()
        c.finish(ticket - 1); assertTrue(c.uiState.value.isBusy)
        c.finish(ticket, "Failed"); assertFalse(c.uiState.value.isBusy)
        assertEquals("abcDEF0123456789", c.uiState.value.code.text)
        c.submit(); c.finish(ticket)
        assertFalse(c.uiState.value.isOpen); assertEquals("", c.uiState.value.code.text)
    }
    @Test fun standaloneConnectionsSurviveWithoutInventingProjects() {
        val saved = listOf(RemoteConnection("Host", "https://host.example", emptyList()))
        assertEquals(saved, RemoteProjectStore.decodeConnections(RemoteProjectStore.encodeConnections(saved)))
        assertTrue(RemoteProjectStore.mergeConnections(emptyList(), saved).single().projects.isEmpty())
        val project = RemoteProject("id", "Project", "Host", "https://host.example", "/work")
        val merged = RemoteProjectStore.mergeConnections(RemoteProjectStore.groupConnections(listOf(project)), saved)
        assertEquals(1, merged.size); assertEquals(listOf(project), merged.single().projects)
    }
    @Test fun malformedScanDoesNotReplaceValidFields() {
        val c = AddConnectionController(); c.open(); c.updateHost(TextFieldValue("host.example"))
        c.completeScan(c.beginScan()!!, "https://example.test")
        assertEquals("host.example", c.uiState.value.host.text)
        assertNotNull(c.uiState.value.error)
        assertFalse(c.uiState.value.isConfirming)
    }
}
