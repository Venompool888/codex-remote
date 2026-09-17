@file:Suppress("DEPRECATION")
package app.codexremote.android

import android.content.Intent
import android.test.InstrumentationTestCase
import org.json.JSONArray
import org.json.JSONObject

/** Disposable emulator only: validates saved upgrade state without real credentials. */
class WorkspaceCompatibilityDeviceTest : InstrumentationTestCase() {
    fun testMigratedAliasesRetainRecordsAndRecycleTogether() {
        val store = RemoteProjectStore(instrumentation.targetContext)
        val original = RemoteProject("compat-old", "root", "Compatibility", "https://compat.invalid", "remote-workspace://" + "a".repeat(64))
        val alias = original.copy(id = "compat-alias")
        store.save(original); store.save(alias); store.setActiveProject(alias.id)
        assertEquals(listOf(alias), store.visibleProjects().filter { it.serverUrl == original.serverUrl })
        store.trashProject(alias.id)
        assertTrue(store.visibleProjects().none { it.serverUrl == original.serverUrl })
        assertEquals(listOf(alias), store.trashedProjects().filter { it.serverUrl == original.serverUrl })
        store.restoreProject(original.id)
        assertTrue(store.trashedProjects().none { it.serverUrl == original.serverUrl })
        assertEquals(listOf(alias), store.visibleProjects().filter { it.serverUrl == original.serverUrl })
        assertEquals(2, store.list().count { it.serverUrl == original.serverUrl })
    }

    fun testActivityNegotiatesSavedOpaqueFormatAndKeepsNewHostDirect() {
        val store = RemoteProjectStore(instrumentation.targetContext)
        store.save(RemoteProject("compat-route", "Mac", "Mac", "https://compat-route.invalid", "remote-workspace://" + "c".repeat(64)))
        val activity = instrumentation.startActivitySync(Intent(instrumentation.targetContext, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        try {
            instrumentation.runOnMainSync {
                val factory = MainActivity::class.java.getDeclaredMethod("createConnectionClient", String::class.java).apply { isAccessible = true }
                val routing = RemoteClient::class.java.getDeclaredField("workspaceRouting").apply { isAccessible = true }
                val offer = JSONObject().put("capabilities", JSONObject()
                    .put("routing", JSONObject().put("opaqueWorkspaceReferences", true))
                    .put("rpcMethods", JSONArray().put("host/workspace/migrate")))
                val saved = factory.invoke(activity, "https://compat-route.invalid") as RemoteClient
                val fresh = factory.invoke(activity, "https://fresh-route.invalid") as RemoteClient
                assertTrue((routing.get(saved) as WorkspaceRoutingNegotiation).request(offer))
                assertFalse((routing.get(fresh) as WorkspaceRoutingNegotiation).request(offer))
                saved.close(); fresh.close()
            }
        } finally { instrumentation.runOnMainSync { activity.finish() } }
    }
}
