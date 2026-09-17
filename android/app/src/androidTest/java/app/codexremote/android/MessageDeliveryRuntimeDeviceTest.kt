@file:Suppress("DEPRECATION", "UNCHECKED_CAST")
package app.codexremote.android

import android.content.Intent
import android.test.InstrumentationTestCase
import app.codexremote.android.presentation.composer.ComposerController
import app.codexremote.android.presentation.conversation.ConversationController
import app.codexremote.android.presentation.conversation.MessageDeliveryStatus
import okhttp3.Request
import okhttp3.WebSocket
import okio.ByteString
import org.json.JSONArray
import org.json.JSONObject

/** Emulator-only in-memory transport: no remote prompt is sent. */
class MessageDeliveryRuntimeDeviceTest : InstrumentationTestCase() {
    private class Socket : WebSocket {
        val messages = mutableListOf<JSONObject>()
        override fun request() = Request.Builder().url("https://delivery.invalid").build()
        override fun queueSize() = 0L
        override fun send(text: String): Boolean { messages += JSONObject(text); return true }
        override fun send(bytes: ByteString) = true
        override fun close(code: Int, reason: String?) = true
        override fun cancel() = Unit
    }

    fun testOptimisticSendAckEchoCompletionAndFailureRestore() {
        val activity = instrumentation.startActivitySync(Intent(instrumentation.targetContext, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        fun field(name: String) = MainActivity::class.java.getDeclaredField(name).apply { isAccessible = true }
        fun method(name: String, vararg types: Class<*>) = MainActivity::class.java.getDeclaredMethod(name, *types).apply { isAccessible = true }
        val server = "https://delivery.invalid"
        val socket = Socket()
        val client = RemoteClient(object : RemoteClient.Listener {
            override fun onConnected() = Unit
            override fun onDisconnected(reason: String) = Unit
            override fun onMessage(message: JSONObject) = Unit
        })
        val thread = JSONObject().put("id", "delivery-runtime").put("cwd", "/delivery").put("turns", JSONArray())
        val composer = method("getComposerController").invoke(activity) as ComposerController
        val conversation = method("getConversationController").invoke(activity) as ConversationController
        fun receive(message: JSONObject) { method("onMessage", String::class.java, JSONObject::class.java).invoke(activity, server, message) }
        fun result(request: JSONObject, result: JSONObject) = receive(JSONObject().put("type", "rpc_result").put("id", request.getString("id")).put("result", result))
        fun event(name: String, turn: JSONObject) = receive(JSONObject().put("type", "codex_event").put("method", name)
            .put("params", JSONObject().put("threadId", thread.getString("id")).put("turn", turn)))
        try {
            var failure: Throwable? = null
            instrumentation.runOnMainSync {
                try {
                RemoteClient::class.java.getDeclaredField("socket").apply { isAccessible = true }.set(client, socket)
                (field("connectionClients").get(activity) as MutableMap<String, RemoteClient>)[server] = client
                (field("connectedServerUrls").get(activity) as MutableSet<String>).add(server)
                field("connectedServerUrl").set(activity, server)
                field("currentThreadId").set(activity, thread.getString("id"))
                field("currentThread").set(activity, thread)
                method("showConversation", JSONObject::class.java).invoke(activity, thread)
                composer.updateText("DELIVERY_FIRST")
                method("performComposerSend", JSONObject::class.java).invoke(activity, thread)
                assertEquals("", composer.uiState.value.text)
                val optimistic = conversation.uiState.value.items.single { it.kind == TimelineItem.Kind.USER }
                assertEquals("DELIVERY_FIRST", optimistic.text)
                assertEquals(MessageDeliveryStatus.SENDING, conversation.uiState.value.messageDeliveries[optimistic.id])
                val scope = method("getComposerScope").invoke(activity) as String
                val drafts = field("draftStore").get(activity) as DraftStore
                assertEquals("DELIVERY_FIRST", drafts.read(scope).optString("text"))
                val request = socket.messages.last { it.optString("method") == "turn/start" }
                result(request, JSONObject().put("turn", JSONObject().put("id", "delivery-turn").put("status", "inProgress")))
                assertEquals(MessageDeliveryStatus.SENT, conversation.uiState.value.messageDeliveries[optimistic.id])
                val user = JSONObject().put("id", "host-user").put("type", "userMessage")
                    .put("content", JSONArray().put(JSONObject().put("type", "text").put("text", "DELIVERY_FIRST")))
                val turn = JSONObject().put("id", "delivery-turn").put("status", "inProgress").put("items", JSONArray().put(user))
                event("turn/started", turn)
                assertEquals(listOf(optimistic.id), conversation.uiState.value.items.filter { it.kind == TimelineItem.Kind.USER }.map { it.id })
                event("turn/completed", turn.put("status", "completed"))
                assertTrue(conversation.uiState.value.messageDeliveries.isEmpty())
                assertEquals("", drafts.read(scope).optString("text"))

                composer.updateText("DELIVERY_REJECTED")
                method("performComposerSend", JSONObject::class.java).invoke(activity, thread)
                val rejected = socket.messages.last { it.optString("method") == "turn/start" }
                assertEquals("", composer.uiState.value.text)
                receive(JSONObject().put("type", "rpc_error").put("id", rejected.getString("id")).put("error", "Selected capability is unavailable"))
                assertEquals("DELIVERY_REJECTED", composer.uiState.value.text)
                assertFalse(conversation.uiState.value.items.any { it.text == "DELIVERY_REJECTED" })
                assertEquals("DELIVERY_REJECTED", drafts.read(scope).optString("text"))

                composer.updateText("DELIVERY_NEW")
                method("performComposerSend", JSONObject::class.java).invoke(activity, thread)
                val next = socket.messages.last { it.optString("method") == "turn/start" }
                composer.updateText("NEXT_DRAFT_KEEP")
                result(next, JSONObject().put("turn", JSONObject().put("id", "next-turn")))
                assertEquals("NEXT_DRAFT_KEEP", composer.uiState.value.text)
                assertEquals("NEXT_DRAFT_KEEP", drafts.read(scope).optString("text"))

                // New-chat creation must transfer the same optimistic bubble to the created task.
                val draft = JSONObject().put("cwd", "/delivery-new").put("turns", JSONArray())
                field("currentThreadId").set(activity, null)
                field("currentThread").set(activity, draft)
                field("draftWorkspace").set(activity, "/delivery-new")
                method("showConversation", JSONObject::class.java).invoke(activity, draft)
                composer.updateText("NEW_CHAT_MESSAGE")
                method("performComposerSend", JSONObject::class.java).invoke(activity, draft)
                val creating = socket.messages.last { it.optString("method") == "thread/start" }
                val newLocal = conversation.uiState.value.items.single { it.kind == TimelineItem.Kind.USER }.id
                assertEquals(MessageDeliveryStatus.SENDING, conversation.uiState.value.messageDeliveries[newLocal])
                val created = JSONObject().put("id", "created-delivery").put("cwd", "/delivery-new").put("turns", JSONArray())
                result(creating, JSONObject().put("thread", created))
                assertEquals("", composer.uiState.value.text)
                assertEquals(listOf(newLocal), conversation.uiState.value.items.filter { it.kind == TimelineItem.Kind.USER }.map { it.id })
                val starting = socket.messages.last { it.optString("method") == "turn/start" }
                receive(JSONObject().put("type", "codex_event").put("method", "turn/completed")
                    .put("params", JSONObject().put("threadId", "created-delivery").put("turn", JSONObject().put("id", "fast-turn").put("status", "completed"))))
                result(starting, JSONObject().put("turn", JSONObject().put("id", "fast-turn").put("status", "inProgress")))
                assertTrue("Late acknowledgement must not resurrect sent", conversation.uiState.value.messageDeliveries.isEmpty())
                assertEquals("", composer.uiState.value.text)
                } catch (error: Throwable) { failure = error }
            }
            failure?.let { throw it }
        } finally {
            instrumentation.runOnMainSync {
                (field("connectionClients").get(activity) as MutableMap<String, RemoteClient>).remove(server)
                activity.finish()
                client.close()
            }
        }
    }
}
