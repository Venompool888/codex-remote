@file:Suppress("DEPRECATION")
package app.codexremote.android

import android.app.NotificationManager
import android.test.InstrumentationTestCase
import org.json.JSONObject

/** Exercises the real Android notification manager, not a mock host or actual agent turn. */
class TaskNotificationDeviceTest : InstrumentationTestCase() {
    fun testNotificationLifecycle() {
        val context = instrumentation.targetContext
        val manager = context.getSystemService(NotificationManager::class.java)
        assertTrue("Grant notification permission before the device test", manager.areNotificationsEnabled())
        TaskNotifications.channels(context)
        val marker = "notification-qa-${System.nanoTime()}"
        val server = "https://$marker.invalid"
        val otherHost = "$server/other"
        val thread = marker
        val request1 = "$marker-1"
        val request2 = "$marker-2"
        val privateMarker = "PRIVATE_NOTIFICATION_FIXTURE_$marker"
        fun request(id: String) = JSONObject().put("type", "codex_request")
            .put("method", "item/tool/requestUserInput").put("requestId", id)
            .put("params", JSONObject().put("threadId", thread)
                .put("command", "printf $privateMarker").put("reason", privateMarker)
                .put("text", privateMarker).put("cwd", "/private/$privateMarker"))
        fun ack(id: String, status: String) = JSONObject().put("type", "server_response_ack")
            .put("requestId", id).put("status", status)
        fun notifications() = manager.activeNotifications.filter {
            it.notification.contentIntent?.creatorPackage == context.packageName && it.id == 0
        }
        val before = notifications().map { it.key }.toSet()
        fun ours() = notifications().filter { it.key !in before }
        fun awaitCount(expected: Int) {
            val deadline = android.os.SystemClock.uptimeMillis() + 3000
            while (ours().size != expected && android.os.SystemClock.uptimeMillis() < deadline) android.os.SystemClock.sleep(50)
            assertEquals(expected, ours().size)
        }
        fun awaitTitle(expected: String) {
            val deadline = android.os.SystemClock.uptimeMillis() + 3000
            while (ours().singleOrNull()?.notification?.extras?.getString("android.title") != expected && android.os.SystemClock.uptimeMillis() < deadline) android.os.SystemClock.sleep(50)
            assertEquals(expected, ours().single().notification.extras.getString("android.title"))
        }
        try {
            TaskNotifications.event(context, server, request(request1))
            awaitCount(1)
            awaitTitle("Waiting for your input")
            val notification = ours().single().notification
            assertEquals(android.app.Notification.VISIBILITY_PRIVATE, notification.visibility)
            assertEquals("Open the task in Remote", notification.extras.getString("android.text"))
            assertFalse("Notification extras must not contain request payloads", notification.extras.toString().contains(privateMarker))
            if (android.os.Build.VERSION.SDK_INT >= 31) assertTrue(notification.contentIntent.isImmutable)
            TaskNotifications.event(context, server, request(request1))
            assertEquals("Replay must not duplicate the notification", 1, ours().size)
            TaskNotifications.event(context, server, request(request2))
            TaskNotifications.event(context, server, ack(request1, "expired"))
            assertEquals("Old acknowledgement must not remove a newer question", 1, ours().size)
            TaskNotifications.event(context, server, ack(request2, "invalid"))
            assertEquals("Invalid reply leaves the question actionable", 1, ours().size)
            TaskNotifications.event(context, otherHost, request(request2))
            awaitCount(2)
            TaskNotifications.event(context, server, ack(request2, "answered"))
            awaitCount(1)
            TaskNotifications.event(context, otherHost, ack(request2, "expired"))
            awaitCount(0)
            val late = "$marker-late"
            TaskNotifications.event(context, server, ack(late, "answered"))
            TaskNotifications.event(context, server, request(late))
            android.os.SystemClock.sleep(150)
            assertEquals("A request replayed after its acknowledgement stays dismissed", 0, ours().size)
            val restart = "$marker-restart"
            TaskNotifications.event(context, server, request(restart))
            TaskNotifications.event(context, otherHost, request(restart))
            awaitCount(2)
            TaskNotifications.event(context, server, JSONObject().put("type", "host_session_changed"))
            awaitCount(1)
            TaskNotifications.event(context, server, request(restart))
            android.os.SystemClock.sleep(150)
            assertEquals("Old host-session request must stay retired", 1, ours().size)
            TaskNotifications.event(context, otherHost, ack(restart, "answered"))
            awaitCount(0)
            val failed = JSONObject().put("type", "codex_event").put("method", "turn/completed")
                .put("params", JSONObject().put("threadId", thread).put("turn", JSONObject().put("id", marker).put("status", "failed").put("error", JSONObject().put("message", privateMarker))))
            TaskNotifications.event(context, server, failed)
            awaitCount(1)
            awaitTitle("Task failed")
            assertFalse("Failure notifications must not include raw error details", ours().single().notification.extras.toString().contains(privateMarker))
            val approvalId = "$marker-approval"
            TaskNotifications.event(context, server, request(approvalId).put("method", "item/commandExecution/requestApproval"))
            awaitCount(1)
            awaitTitle("Waiting for approval")
            val waitingKey = ours().single().key
            val waitingPostTime = ours().single().postTime
            repeat(12) {
                TaskNotifications.event(context, server, JSONObject().put("type", "codex_event").put("method", "item/agentMessage/delta")
                    .put("params", JSONObject().put("threadId", thread).put("turnId", marker).put("delta", privateMarker)))
            }
            assertEquals("Streaming must not replace/repost the waiting notification", waitingPostTime, ours().single().postTime)
            assertEquals(waitingKey, ours().single().key)
            val completed = JSONObject().put("type", "codex_event").put("method", "turn/completed")
                .put("params", JSONObject().put("threadId", thread).put("turn", JSONObject().put("id", "$marker-complete").put("status", "completed")))
            TaskNotifications.event(context, server, completed)
            awaitCount(1)
            awaitTitle("Task completed")
            assertEquals("Completion replaces the same host/task notification", waitingKey, ours().single().key)
            TaskNotifications.event(context, server, ack(approvalId, "answered"))
            awaitCount(1)
            assertEquals("Late approval acknowledgement must not remove completion", "Task completed", ours().single().notification.extras.getString("android.title"))
            val completionPostTime = ours().single().postTime
            TaskNotifications.event(context, server, completed)
            assertEquals("Completion replay must not repost", completionPostTime, ours().single().postTime)

        } finally {
            ours().forEach { manager.cancel(it.tag, it.id) }
        }
    }
}
