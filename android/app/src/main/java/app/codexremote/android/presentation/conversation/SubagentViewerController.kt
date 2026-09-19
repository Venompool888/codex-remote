package app.codexremote.android.presentation.conversation

import androidx.compose.runtime.mutableStateOf
import app.codexremote.android.SubagentReference
import app.codexremote.android.ThreadProjection
import app.codexremote.android.TimelineItem
import app.codexremote.android.LiveTimelineStore
import org.json.JSONObject

data class SubagentViewerState(
    val agent: SubagentReference? = null,
    val items: List<TimelineItem> = emptyList(),
    val loading: Boolean = false,
    val error: String? = null,
    val canAcceptDirectInput: Boolean = false,
)

/** Read-only inspector; generations reject responses from closed/replaced inspectors. */
class SubagentViewerController(
    private val onOpenAsTask: (String) -> Unit = {},
    private val read: (String, (JSONObject?, String?) -> Unit) -> Unit = { _, done -> done(null, "Not connected") },
) {
    val uiState = mutableStateOf(SubagentViewerState())
    private var generation = 0L
    private var eventRevision = 0L
    private var snapshot: JSONObject? = null
    private var live = LiveTimelineStore()

    fun open(agent: SubagentReference) {
        generation++
        snapshot = null
        live = LiveTimelineStore()
        eventRevision = 0
        uiState.value = SubagentViewerState(agent = agent)
        refresh()
    }

    fun close() {
        generation++
        snapshot = null
        live = LiveTimelineStore()
        eventRevision = 0
        uiState.value = SubagentViewerState()
    }

    fun openAsTask() {
        val state = uiState.value
        val id = state.agent?.threadId ?: return
        if (!state.canAcceptDirectInput || state.loading || state.error != null) return
        onOpenAsTask(id)
    }

    fun consumeEvent(method: String, params: JSONObject): Boolean {
        val agent = uiState.value.agent ?: return false
        if (params.optString("threadId") != agent.threadId) return false
        val changed = live.record(method, params)
        if (!changed && method != "thread/status/changed") return false
        eventRevision++
        val status = if (method == "thread/status/changed") params.optJSONObject("status")?.optString("type") else null
        uiState.value = uiState.value.copy(
            agent = agent.copy(status = status?.takeIf { it.isNotBlank() && it != "null" } ?: agent.status),
            items = ThreadProjection.timeline(snapshot ?: JSONObject().put("id", agent.threadId), live.snapshots(agent.threadId)),
        )
        return true
    }

    fun refresh() {
        val state = uiState.value
        val agent = state.agent ?: return
        if (state.loading) return
        val request = ++generation
        val revision = eventRevision
        uiState.value = state.copy(loading = true, error = null)
        read(agent.threadId) { thread, error ->
            if (generation != request || uiState.value.agent?.threadId != agent.threadId) return@read
            if (thread == null || thread.optString("id") != agent.threadId || error != null) {
                uiState.value = uiState.value.copy(loading = false,
                    error = "Could not load this subagent. It may be unavailable or outside this connection's access. Retry to check again.")
            } else {
                live.reconcileThreadSnapshot(agent.threadId, thread, revision, eventRevision)
                snapshot = JSONObject(thread.toString())
                val status = thread.optJSONObject("status")?.optString("type").orEmpty()
                uiState.value = SubagentViewerState(
                    agent = agent.copy(name = sequenceOf("agentNickname", "name", "agentRole")
                        .map { thread.optString(it) }.firstOrNull { it.isNotBlank() && it != "null" } ?: agent.name,
                        status = if (revision == eventRevision) status.ifBlank { agent.status } else uiState.value.agent?.status ?: agent.status),
                    items = ThreadProjection.timeline(thread, live.snapshots(agent.threadId)),
                    canAcceptDirectInput = thread.opt("canAcceptDirectInput") == true,
                )
            }
        }
    }
}
