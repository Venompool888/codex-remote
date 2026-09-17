@file:Suppress("DEPRECATION", "UNCHECKED_CAST")
package app.codexremote.android

import android.content.Intent
import android.graphics.Bitmap
import android.test.InstrumentationTestCase
import android.view.View
import android.view.accessibility.AccessibilityNodeInfo
import org.json.JSONArray
import org.json.JSONObject

/** UI-only negotiated-host fixture: never connects to a local server or changes stored drafts. */
class AttachmentDowngradeDeviceTest : InstrumentationTestCase() {
    fun testKnownUnsupportedHostExplainsBeforeOpeningPickerAndPreservesDraft() {
        val activity = instrumentation.startActivitySync(Intent(instrumentation.targetContext, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        fun field(name: String) = MainActivity::class.java.getDeclaredField(name).apply { isAccessible = true }
        val clients = field("connectionClients").get(activity) as MutableMap<String, RemoteClient>
        val originalServer = field("connectedServerUrl").get(activity)
        val originalDraft = field("composerDraft").get(activity)
        val originalAttachments = field("composerAttachments").get(activity).toString()
        val host = "https://attachment-downgrade.invalid"
        val client = RemoteClient(object : RemoteClient.Listener {
            override fun onConnected() = Unit
            override fun onDisconnected(reason: String) = Unit
            override fun onMessage(message: JSONObject) = Unit
        })
        fun capture(name: String) {
            instrumentation.uiAutomation.takeScreenshot().let { bitmap ->
                instrumentation.targetContext.openFileOutput(name, 0).use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
                bitmap.recycle()
            }
        }
        try {
            for (version in listOf(1, 2)) {
                instrumentation.runOnMainSync {
                    clients[host] = client
                    field("connectedServerUrl").set(activity, host)
                    RemoteClient::class.java.getDeclaredField("negotiatedProtocolVersion").apply { isAccessible = true }.setInt(client, version)
                    RemoteClient::class.java.getDeclaredField("negotiatedCapabilities").apply { isAccessible = true }.set(client,
                        JSONObject().put("rpcMethods", JSONArray(if (version == 2) listOf("host/capabilities/list") else emptyList<String>())))
                    MainActivity::class.java.getDeclaredMethod("pickAttachments", Boolean::class.javaPrimitiveType)
                        .apply { isAccessible = true }.invoke(activity, false)
                }
                instrumentation.waitForIdleSync()
                val root = instrumentation.awaitUiText("Your existing draft and attachments are kept.")
                assertEquals(instrumentation.targetContext.packageName, root.packageName.toString())
                instrumentation.clickUi("OK")
                assertEquals(originalDraft, field("composerDraft").get(activity))
                assertEquals(originalAttachments, field("composerAttachments").get(activity).toString())
            }
        } finally {
            instrumentation.runOnMainSync {
                clients.remove(host)
                field("connectedServerUrl").set(activity, originalServer)
                activity.finish()
            }
        }
    }
}
