@file:Suppress("DEPRECATION")
package app.codexremote.android

import android.content.Context
import android.graphics.Bitmap
import android.test.InstrumentationTestCase
import app.codexremote.android.presentation.connections.AddConnectionController
import app.codexremote.android.presentation.connections.ConnectionsController
import app.codexremote.android.ui.connections.AddConnectionScreen
import app.codexremote.android.ui.connections.ConnectionsScreen

/** Synthetic endpoints only, and destructive setup restricted to the disposable QA AVD. */
class AddConnectionDeviceTest : InstrumentationTestCase() {
    private val link = "codexremote://pair?server=https%3A%2F%2Fhost.example%3A8443%2Fremote&code=abcDEF0123456789"
    override fun setUp() {
        super.setUp()
        val avd = instrumentation.uiAutomation.executeShellCommand("getprop ro.boot.qemu.avd_name").use {
            java.io.FileInputStream(it.fileDescriptor).bufferedReader().readText().trim()
        }
        check(avd == "Codex_Deletion_QA") { "Only the disposable QA emulator is allowed" }
        listOf("remote_projects", "remote_credentials", "remote_settings", "remote_navigation").forEach {
            instrumentation.targetContext.getSharedPreferences(it, Context.MODE_PRIVATE).edit().clear().commit()
        }
    }
    private fun capture(name: String) {
        instrumentation.waitForIdleSync()
        instrumentation.uiAutomation.takeScreenshot()?.let { image ->
            instrumentation.targetContext.openFileOutput(name, Context.MODE_PRIVATE).use { image.compress(Bitmap.CompressFormat.PNG, 100, it) }
            image.recycle()
        }
    }
    fun testEmptyFormAndScanRequireExplicitConfirmation() {
        var submitted: String? = null
        val add = AddConnectionController { server, _, _ -> submitted = server }
        val connections = ConnectionsController()
        instrumentation.runOnMainSync { connections.openManager(true) }
        val activity = instrumentation.composeFixture {
            ConnectionsScreen(connections, onOpenAddProject = {}, onOpenAddConnection = add::open)
            AddConnectionScreen(add, onScan = { add.completeScan(add.beginScan()!!, link) })
        }
        try {
            val root = instrumentation.awaitUiText("No connections yet")
            assertTrue(root.findUiText("New remote project").isEmpty())
            capture("qa-connection-empty.png")
            instrumentation.clickUi("Add Connection")
            instrumentation.awaitUiText("Scan QR Code")
            assertTrue(instrumentation.uiAutomation.rootInActiveWindow.findUiText("Project name").isEmpty())
            capture("qa-connection-form.png")
            instrumentation.clickUi("Scan QR Code")
            instrumentation.awaitUiText("Confirm & Connect")
            assertNull(submitted)
            assertEquals("https://host.example:8443/remote", add.uiState.value.serverPreview)
            capture("qa-connection-confirm.png")
            instrumentation.clickUi("Edit details")
            instrumentation.awaitUiText("Pair & Connect")
            assertEquals("/remote", add.uiState.value.basePath.text)
            instrumentation.clickUi("Pair & Connect")
            instrumentation.awaitUi("Explicit action submits") { submitted != null }
            assertEquals("https://host.example:8443/remote", submitted)
        } finally { instrumentation.runOnMainSync { activity.finish() } }
    }
    fun testConnectionPersistsBeforeAnyProjectAndDeletionRemovesIt() {
        val context = instrumentation.targetContext
        val store = RemoteProjectStore(context)
        store.saveConnection("https://host.example:8443/remote")
        assertTrue(store.list().isEmpty())
        assertEquals("https://host.example:8443/remote", RemoteProjectStore(context).connections().single().serverUrl)
        store.updateConnection("https://host.example:8443/remote", "Host", "https://host.example/other")
        assertEquals("https://host.example/other", store.connections().single().serverUrl)
        ConnectionDeletion(context).delete(setOf("https://host.example/other"))
        assertTrue(RemoteProjectStore(context).connections().isEmpty())
    }
}
