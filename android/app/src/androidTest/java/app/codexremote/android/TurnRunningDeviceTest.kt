@file:Suppress("DEPRECATION")
package app.codexremote.android

import android.content.Intent
import android.test.InstrumentationTestCase
import org.json.JSONObject

class TurnRunningDeviceTest : InstrumentationTestCase() {
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
