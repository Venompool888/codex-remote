@file:Suppress("DEPRECATION", "UNCHECKED_CAST")
package app.codexremote.android

import android.content.Intent
import android.test.InstrumentationTestCase
import okhttp3.Request
import okhttp3.WebSocket
import okio.ByteString
import org.json.JSONArray
import org.json.JSONObject

class TurnRunningDeviceTest : InstrumentationTestCase() {
    private class Socket : WebSocket {
        val messages = mutableListOf<JSONObject>()
        override fun request() = Request.Builder().url("https://running.invalid").build()
        override fun queueSize() = 0L
        override fun send(text: String): Boolean { messages += JSONObject(text); return true }
        override fun send(bytes: ByteString) = true
        override fun close(code: Int, reason: String?) = true
        override fun cancel() = Unit
    }

    fun testColdOpenedRunningTurnCanSendInterrupt() {
        val activity = instrumentation.startActivitySync(Intent(instrumentation.targetContext, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        val socket = Socket()
        val client = RemoteClient(object : RemoteClient.Listener {
            override fun onConnected() = Unit
            override fun onDisconnected(reason: String) = Unit
            override fun onMessage(message: JSONObject) = Unit
        })
        try {
            instrumentation.runOnMainSync {
                val type = MainActivity::class.java
                fun field(name: String) = type.getDeclaredField(name).apply { isAccessible = true }
                fun method(name: String, vararg args: Class<*>) = type.getDeclaredMethod(name, *args).apply { isAccessible = true }
                RemoteClient::class.java.getDeclaredField("socket").apply { isAccessible = true }.set(client, socket)
                RemoteClient::class.java.getDeclaredField("negotiatedConnectionEpoch").apply { isAccessible = true }
                    .setLong(client, 0L)
                val server = "https://running.invalid"
                (field("connectionClients").get(activity) as MutableMap<String, RemoteClient>)[server] = client
                (field("connectedServerUrls").get(activity) as MutableSet<String>).add(server)
                field("connectedServerUrl").set(activity, server)
                val thread = JSONObject().put("id", "cold-running").put("status", JSONObject().put("type", "active"))
                    .put("turns", JSONArray().put(JSONObject().put("id", "cold-turn").put("status", "inProgress")))
                field("currentThreadId").set(activity, "cold-running")
                field("currentThread").set(activity, thread)
                method("showConversation", JSONObject::class.java).invoke(activity, thread)
                method("interruptActiveTurn").invoke(activity)
                val interrupt = socket.messages.lastOrNull { it.optString("method") == "turn/interrupt" }
                assertNotNull("A cold-opened active turn must send an interrupt", interrupt)
                assertEquals("cold-turn", interrupt!!.getJSONObject("params").getString("turnId"))
                val started = JSONObject().put("type", "codex_event").put("method", "turn/started")
                    .put("params", JSONObject().put("threadId", "cold-running")
                        .put("turn", JSONObject().put("id", "cold-turn").put("status", "inProgress")))
                method("onMessage", String::class.java, JSONObject::class.java).invoke(activity, server, started)
                method("interruptActiveTurn").invoke(activity)
                val hotInterrupt = socket.messages.last { it.optString("method") == "turn/interrupt" }
                val event = JSONObject().put("type", "codex_event").put("method", "thread/status/changed")
                    .put("params", JSONObject().put("threadId", "cold-running")
                        .put("status", JSONObject().put("type", "active")))
                method("onMessage", String::class.java, JSONObject::class.java).invoke(activity, server, event)
                val error = JSONObject().put("type", "rpc_error").put("id", hotInterrupt.getString("id"))
                    .put("error", "Temporary host error")
                method("onMessage", String::class.java, JSONObject::class.java).invoke(activity, server, error)
                val store = field("liveTimelineStore").get(activity) as LiveTimelineStore
                assertEquals("cold-turn", store.activeTurnId("cold-running"))
                (field("connectionClients").get(activity) as MutableMap<String, RemoteClient>).remove(server)
            }
        } finally { instrumentation.runOnMainSync { activity.finish(); client.close() } }
    }

    fun testOldCompletionDoesNotStopNewTurn() {
        val activity = instrumentation.startActivitySync(Intent(instrumentation.targetContext, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        try {
            instrumentation.runOnMainSync {
                val type = MainActivity::class.java
                fun field(name: String) = type.getDeclaredField(name).apply { isAccessible = true }
                val event = type.getDeclaredMethod("handleCodexEvent", JSONObject::class.java).apply { isAccessible = true }
                val threadId = "overlapping-events"
                field("currentThreadId").set(activity, threadId)
                field("currentThread").set(activity, JSONObject().put("id", threadId).put("turns", JSONArray()))
                fun emit(method: String, turn: String, status: String) = event.invoke(activity, JSONObject()
                    .put("method", method).put("params", JSONObject().put("threadId", threadId)
                        .put("turn", JSONObject().put("id", turn).put("status", status))))
                emit("turn/started", "old", "inProgress")
                emit("turn/started", "new", "inProgress")
                emit("turn/completed", "old", "completed")
                assertTrue("Old completion must leave the newer turn running", field("turnRunning").getBoolean(activity))
                emit("turn/completed", "new", "completed")
                emit("turn/started", "new", "inProgress")
                assertFalse("Late start must not restart a completed turn", field("turnRunning").getBoolean(activity))
                event.invoke(activity, JSONObject().put("method", "item/agentMessage/delta")
                    .put("params", JSONObject().put("threadId", threadId).put("turnId", "new")
                        .put("itemId", "late-answer").put("delta", "late")))
                assertFalse("Late final-answer text must not restart a completed turn", field("turnRunning").getBoolean(activity))
                assertEquals("", field("liveAssistantText").get(activity))
            }
        } finally { instrumentation.runOnMainSync { activity.finish() } }
    }

    fun testOldCompletionDoesNotStopColdOpenedNewTurn() {
        val activity = instrumentation.startActivitySync(Intent(instrumentation.targetContext, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        try {
            instrumentation.runOnMainSync {
                val type = MainActivity::class.java
                fun field(name: String) = type.getDeclaredField(name).apply { isAccessible = true }
                val threadId = "cold-new-turn"
                val thread = JSONObject().put("id", threadId).put("status", JSONObject().put("type", "active"))
                    .put("turns", JSONArray().put(JSONObject().put("id", "new").put("status", "inProgress")))
                field("currentThreadId").set(activity, threadId)
                field("currentThread").set(activity, thread)
                field("turnRunning").setBoolean(activity, true)
                val event = JSONObject().put("method", "turn/completed")
                    .put("params", JSONObject().put("threadId", threadId)
                        .put("turn", JSONObject().put("id", "old").put("status", "completed")))
                type.getDeclaredMethod("handleCodexEvent", JSONObject::class.java).apply { isAccessible = true }
                    .invoke(activity, event)
                assertTrue(field("turnRunning").getBoolean(activity))
                event.getJSONObject("params").put("turn", JSONObject().put("id", "new").put("status", "completed"))
                type.getDeclaredMethod("handleCodexEvent", JSONObject::class.java).apply { isAccessible = true }
                    .invoke(activity, event)
                assertFalse(field("turnRunning").getBoolean(activity))
                event.getJSONObject("params").put("turn", JSONObject().put("id", "old").put("status", "completed"))
                type.getDeclaredMethod("handleCodexEvent", JSONObject::class.java).apply { isAccessible = true }
                    .invoke(activity, event)
                assertFalse("Duplicate old completion must not restore finished new turn", field("turnRunning").getBoolean(activity))
                val terminal = JSONObject().put("id", threadId).put("status", JSONObject().put("type", "idle"))
                    .put("turns", JSONArray().put(JSONObject().put("id", "cold-complete").put("status", "completed")))
                field("currentThread").set(activity, terminal)
                val lateDelta = JSONObject().put("method", "item/agentMessage/delta")
                    .put("params", JSONObject().put("threadId", threadId).put("turnId", "cold-complete")
                        .put("itemId", "cold-answer").put("delta", "late"))
                type.getDeclaredMethod("handleCodexEvent", JSONObject::class.java).apply { isAccessible = true }
                    .invoke(activity, lateDelta)
                val store = field("liveTimelineStore").get(activity) as LiveTimelineStore
                assertNull("Cold terminal snapshot must not acquire a live turn", store.activeTurnId(threadId))
            }
        } finally { instrumentation.runOnMainSync { activity.finish() } }
    }

    fun testTerminalThreadReadClearsStopAfterMissedCompletion() {
        val activity = instrumentation.startActivitySync(Intent(instrumentation.targetContext, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        try {
            instrumentation.runOnMainSync {
                val store = MainActivity::class.java.getDeclaredField("liveTimelineStore").apply { isAccessible = true }.get(activity) as LiveTimelineStore
                val threadId = "qa-missed-completion-7391"
                val turnId = "qa-turn"
                val threadIdField = MainActivity::class.java.getDeclaredField("currentThreadId").apply { isAccessible = true }
                val runningField = MainActivity::class.java.getDeclaredField("turnRunning").apply { isAccessible = true }
                threadIdField.set(activity, threadId)
                runningField.setBoolean(activity, true)
                store.record("turn/started", JSONObject().put("threadId", threadId)
                    .put("turn", JSONObject().put("id", turnId).put("status", "inProgress")))
                val terminal = JSONObject().put("id", threadId).put("status", JSONObject().put("type", "idle"))
                    .put("turns", org.json.JSONArray().put(JSONObject().put("id", turnId).put("status", "completed")))
                val apply = MainActivity::class.java.getDeclaredMethod("applyThreadSnapshot", JSONObject::class.java,
                    Boolean::class.javaPrimitiveType, Long::class.javaPrimitiveType).apply { isAccessible = true }

                apply.invoke(activity, terminal, false, 0L)

                assertEquals(false, runningField.getBoolean(activity))
                assertNull(store.activeTurnId(threadId))
            }
        } finally { instrumentation.runOnMainSync { activity.finish() } }
    }

    fun testStartedEventSurvivesStaleIdleSnapshotUntilCompletion() {
        val activity = instrumentation.startActivitySync(Intent(instrumentation.targetContext, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        try {
            instrumentation.runOnMainSync {
                val store = MainActivity::class.java.getDeclaredField("liveTimelineStore").apply { isAccessible = true }.get(activity) as LiveTimelineStore
                val running = MainActivity::class.java.getDeclaredMethod("threadIsRunning", JSONObject::class.java).apply { isAccessible = true }
                val thread = JSONObject().put("id", "qa-start-event-7391").put("status", JSONObject().put("type", "idle"))
                val params = JSONObject().put("threadId", thread.getString("id")).put("turn", JSONObject().put("id", "qa-turn").put("status", "inProgress"))
                assertEquals(false, running.invoke(activity, thread))
                store.record("turn/started", params)
                assertEquals(true, running.invoke(activity, thread))
                assertEquals(false, running.invoke(activity, JSONObject().put("id", "different-task").put("status", "idle")))
                params.getJSONObject("turn").put("status", "completed")
                store.record("turn/completed", params)
                assertEquals(false, running.invoke(activity, thread))
            }
        } finally { instrumentation.runOnMainSync { activity.finish() } }
    }
}
