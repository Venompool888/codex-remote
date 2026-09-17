@file:Suppress("DEPRECATION")
package app.codexremote.android

import android.content.Context
import android.content.Intent
import android.test.InstrumentationTestCase
import android.view.accessibility.AccessibilityNodeInfo

/** Run only on the disposable Codex_Deletion_QA emulator, never a paired client. */
class ConnectionDeletionDeviceTest : InstrumentationTestCase() {
    private val a = "https://delete-a.invalid"
    private val b = "https://delete-b.invalid"
    private val prefs = listOf("remote_projects", "remote_credentials", "remote_settings", "remote_navigation")

    private fun clearFixture() {
        val avd = instrumentation.uiAutomation.executeShellCommand("getprop ro.boot.qemu.avd_name").use { descriptor ->
            java.io.FileInputStream(descriptor.fileDescriptor).bufferedReader().readText().trim()
        }
        check(avd == "Codex_Deletion_QA") { "Refusing to clear data outside the disposable deletion emulator: $avd" }
        prefs.forEach { instrumentation.targetContext.getSharedPreferences(it, Context.MODE_PRIVATE).edit().clear().commit() }
    }

    private fun seed(): RemoteProjectStore {
        clearFixture()
        return RemoteProjectStore(instrumentation.targetContext).apply {
            save(RemoteProject("a1", "API", "Host A", a, "/api"))
            save(RemoteProject("a2", "Web", "Host A", a, "/web"))
            save(RemoteProject("b1", "Other", "Host B", b, "/other"))
            setActiveProject("a1")
        }
    }

    fun testPersistenceCredentialsAndLegacyDoNotResurrectDeletedHosts() {
        val context = instrumentation.targetContext
        val store = seed()
        try {
            val tokens = SecureTokenStore(context)
            tokens.save(a, "fixture-a")
            tokens.save(b, "fixture-b")
            context.getSharedPreferences("remote_settings", Context.MODE_PRIVATE).edit()
                .putString("server_url", a).putString("workspace", "/api").commit()
            context.getSharedPreferences("remote_credentials", Context.MODE_PRIVATE).edit()
                .putString("server_url", a).putString("token_iv", "legacy").putString("token_ciphertext", "legacy").commit()
            ConnectionDeletion(context).delete(setOf("$a/"))
            instrumentation.awaitUi("only other host remains") { store.list().map { it.id } == listOf("b1") }
            assertEquals(listOf("b1"), store.list().map { it.id })
            assertNull(store.activeProjectId())
            assertNull(tokens.load(a))
            assertEquals("fixture-b", tokens.load(b))
            assertFalse(context.getSharedPreferences("remote_credentials", Context.MODE_PRIVATE).contains("token_iv"))
            assertFalse(context.getSharedPreferences("remote_settings", Context.MODE_PRIVATE).contains("server_url"))
            ConnectionDeletion(context).delete(setOf(b))
            assertTrue(RemoteProjectStore(context).list().isEmpty())
            assertNull(SecureTokenStore(context).load(b))
            assertNull(store.migrateLegacy("", ""))
        } finally { clearFixture() }
    }

    private fun nodes(node: AccessibilityNodeInfo): List<AccessibilityNodeInfo> =
        listOf(node) + (0 until node.childCount).flatMap { node.getChild(it)?.let(::nodes).orEmpty() }

    fun testActiveDeletionChoosesRemainingHostAndClearsConversation() {
        val store = seed()
        val activity = instrumentation.startActivitySync(Intent(instrumentation.targetContext, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        val remove = MainActivity::class.java.getDeclaredMethod("deleteConnections", Set::class.java, Boolean::class.javaPrimitiveType).apply { isAccessible = true }
        fun field(name: String) = MainActivity::class.java.getDeclaredField(name).apply { isAccessible = true }
        try {
            instrumentation.runOnMainSync {
                val create = MainActivity::class.java.getDeclaredMethod("createConnectionClient", String::class.java).apply { isAccessible = true }
                val oldClient = create.invoke(activity, a) as RemoteClient
                @Suppress("UNCHECKED_CAST")
                val clients = field("connectionClients").get(activity) as MutableMap<String, RemoteClient>
                clients[a] = oldClient
                val listener = RemoteClient::class.java.getDeclaredField("listener").apply { isAccessible = true }.get(oldClient) as RemoteClient.Listener
                field("currentThreadId").set(activity, "old-thread")
                remove.invoke(activity, setOf(a), true)
                assertEquals("b1", store.activeProjectId())
                assertEquals(b, field("connectedServerUrl").get(activity))
                assertNull(field("currentThreadId").get(activity))
                instrumentation.awaitUi("only other host remains") { store.list().map { it.id } == listOf("b1") }
            assertEquals(listOf("b1"), store.list().map { it.id })
                // A socket event already queued at deletion time must not restart the host.
                listener.onConnected()
                listener.onDisconnected("late disconnect")
                assertFalse((field("connectedServerUrls").get(activity) as Set<*>).contains(a))
                assertFalse((field("reconnectAttempts").get(activity) as Map<*, *>).containsKey(a))
                remove.invoke(activity, setOf(b), true)
                assertNull(field("connectedServerUrl").get(activity))
                assertNull(store.activeProjectId())
            }
            find("No connections yet")
        } finally {
            instrumentation.runOnMainSync { activity.finish() }
            clearFixture()
        }
    }

    private fun find(label: String): AccessibilityNodeInfo {
        val deadline = android.os.SystemClock.uptimeMillis() + 4000
        while (android.os.SystemClock.uptimeMillis() < deadline) {
            instrumentation.uiAutomation.rootInActiveWindow?.let { root ->
                nodes(root).firstOrNull { it.text?.toString() == label || it.text?.toString()?.startsWith("$label (") == true || it.contentDescription?.toString()?.startsWith(label) == true }?.let { return it }
            }
            android.os.SystemClock.sleep(100)
        }
        error("Missing UI node: $label")
    }

    private fun click(label: String, long: Boolean = false) {
        var node = find(label)
        while (if (long) !node.isLongClickable else !node.isClickable) node = node.parent ?: error("No action for $label")
        assertTrue(node.performAction(if (long) AccessibilityNodeInfo.ACTION_LONG_CLICK else AccessibilityNodeInfo.ACTION_CLICK))
        instrumentation.waitForIdleSync()
    }

    private fun capture(name: String) {
        android.os.SystemClock.sleep(350)
        val bitmap = instrumentation.uiAutomation.takeScreenshot() ?: return
        val file = java.io.File(instrumentation.targetContext.getExternalFilesDir(null), "$name.png")
        file.outputStream().use { bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
        bitmap.recycle()
    }

    fun testSelectionBackAndSingleDeletePreserveOtherHost() {
        val store = seed()
        val activity = instrumentation.startActivitySync(Intent(instrumentation.targetContext, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        try {
            click("Connection Host A", long = true)
            click("Select")
            find("1 selected")
            click("Host A")
            find("0 selected")
            instrumentation.awaitUi("selection disabled") {
                var node: AccessibilityNodeInfo? = find("Delete selected")
                var disabled = false
                repeat(8) { if (node?.isEnabled == false) disabled = true; node = node?.parent }
                disabled
            }
            instrumentation.sendKeyDownUpSync(android.view.KeyEvent.KEYCODE_BACK)
            find("Connection management")
            assertEquals(3, store.list().size)
            click("Connection Host A", long = true)
            click("Delete")
            find("Delete connection?")
            click("Delete")
            instrumentation.awaitUi("only other host remains") { store.list().map { it.id } == listOf("b1") }
            assertEquals(listOf("b1"), store.list().map { it.id })
            find("Connection Host B")
        } finally {
            instrumentation.runOnMainSync { activity.finish() }
            clearFixture()
        }
    }

    fun testLongPressCancelThenBatchDeleteAndRelaunch() {
        val store = seed()
        var activity = instrumentation.startActivitySync(Intent(instrumentation.targetContext, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        try {
            click("Connection Host A", long = true)
            capture("connection-menu")
            click("Delete")
            capture("connection-confirm")
            click("Cancel")
            assertEquals(3, store.list().size)
            click("Connection Host A", long = true)
            click("Select")
            click("Select all")
            find("2 selected")
            capture("connection-selection")
            click("Delete selected")
            // Confirmation is the only place that is allowed to mutate saved data.
            assertEquals(3, store.list().size)
            click("Delete")
            assertTrue(store.list().isEmpty())
            find("No connections yet")
            instrumentation.runOnMainSync { activity.finish() }
            instrumentation.waitForIdleSync()
            activity = instrumentation.startActivitySync(Intent(instrumentation.targetContext, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            find("No connections yet")
        } finally {
            instrumentation.runOnMainSync { activity.finish() }
            clearFixture()
        }
    }
}
