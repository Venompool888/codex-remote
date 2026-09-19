package app.codexremote.android

import org.json.JSONArray
import org.json.JSONObject

/** Maintains chronological loaded pages while a latest-page refresh replaces overlapping turns. */
class ThreadHistoryWindow {
    private val turns = mutableListOf<JSONObject>()
    var nextCursor: String? = null
        private set
    private var hasInitialPage = false
    fun reset() { turns.clear(); nextCursor = null; hasInitialPage = false }
    fun mergeDescendingPage(page: JSONArray, cursor: String?, older: Boolean): JSONArray {
        val incoming = (page.length() - 1 downTo 0).mapNotNull { page.optJSONObject(it) }
            .filter { it.optString("id").isNotBlank() }.distinctBy { it.optString("id") }
            .map { JSONObject(it.toString()) }
        val incomingIds = incoming.map { it.optString("id") }.toSet()
        if (older) {
            val existingIds = turns.map { it.optString("id") }.toSet()
            turns.addAll(0, incoming.filter { it.optString("id") !in existingIds })
            nextCursor = cursor
        } else {
            // A long absence can create an unseen gap. Restart from the new head instead
            // of presenting disconnected pages as continuous history.
            if (turns.isNotEmpty() && incoming.isNotEmpty() && turns.none { it.optString("id") in incomingIds }) {
                turns.clear()
                hasInitialPage = false
            }
            turns.removeAll { it.optString("id") in incomingIds }
            turns.addAll(incoming)
            if (!hasInitialPage) nextCursor = cursor
        }
        hasInitialPage = true
        return JSONArray(turns.map { JSONObject(it.toString()) })
    }
}
