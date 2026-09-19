package app.codexremote.android

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class ThreadHistoryWindowTest {
    @Test fun liveRefreshPreservesOlderPagesAndDoesNotRegressLoadedTurns() {
        val window = ThreadHistoryWindow()
        fun page(vararg ids: String) = JSONArray(ids.map { JSONObject().put("id", it) })
        window.mergeDescendingPage(page("c", "b"), "older", false)
        val full = window.mergeDescendingPage(page("b", "a"), null, true)
        assertEquals(listOf("a", "b", "c"), (0 until full.length()).map { full.getJSONObject(it).getString("id") })
        val refreshed = window.mergeDescendingPage(JSONArray().put(JSONObject().put("id", "d")).put(JSONObject().put("id", "c").put("status", "completed")), "ignored", false)
        assertEquals(listOf("a", "b", "c", "d"), (0 until refreshed.length()).map { refreshed.getJSONObject(it).getString("id") })
        assertEquals("completed", refreshed.getJSONObject(2).getString("status"))
        assertNull(window.nextCursor)
        window.reset()
        assertEquals(1, window.mergeDescendingPage(page("new"), "cursor", false).length())
        assertEquals("cursor", window.nextCursor)
    }
}
