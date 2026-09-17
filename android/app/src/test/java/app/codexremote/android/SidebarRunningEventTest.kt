package app.codexremote.android

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SidebarRunningEventTest {
    private val background = RemoteThread("background", "Background task", "/project", "idle", 0L, false)
    private val current = RemoteThread("current", "Current task", "/project", "idle", 0L, false)

    @Test
    fun backgroundTurnStartAndFinishUpdateOnlyTheirThread() {
        val started = applySidebarRunningEvent(
            listOf(background, current),
            "turn/started",
            JSONObject().put("threadId", background.id)
        )!!
        assertTrue(started.first().isRunning)
        assertEquals("active", started.first().status)
        assertFalse(started.last().isRunning)

        val completed = applySidebarRunningEvent(
            started,
            "turn/completed",
            JSONObject().put("threadId", background.id)
        )!!
        assertFalse(completed.first().isRunning)
        assertEquals("completed", completed.first().status)
        assertFalse(completed.last().isRunning)
    }

    @Test
    fun threadStatusChangedUsesTheReportedState() {
        val active = applySidebarRunningEvent(
            listOf(background),
            "thread/status/changed",
            JSONObject()
                .put("threadId", background.id)
                .put("status", JSONObject().put("type", "running"))
        )!!
        assertTrue(active.single().isRunning)

        val idle = applySidebarRunningEvent(
            active,
            "thread/status/changed",
            JSONObject()
                .put("threadId", background.id)
                .put("status", JSONObject().put("type", "idle"))
        )!!
        assertFalse(idle.single().isRunning)
    }

    @Test
    fun unrelatedOrUnknownEventsDoNotRewriteTheCachedList() {
        assertNull(applySidebarRunningEvent(listOf(background), "item/started", JSONObject().put("threadId", background.id)))
        assertNull(applySidebarRunningEvent(listOf(background), "turn/started", JSONObject().put("threadId", "missing")))
        assertNull(applySidebarRunningEvent(listOf(background), "turn/started", JSONObject()))
    }
}
