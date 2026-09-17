package app.codexremote.android.presentation.conversation

import app.codexremote.android.TimelineItem

/** UI-only delivery receipts, isolated by the existing server/credential/conversation scope. */
enum class MessageDeliveryStatus { SENDING, SENT }

internal data class DeliveryTurn(val userItemIds: Set<String>, val finished: Boolean)
internal data class DeliveredTimeline(val items: List<TimelineItem>, val statuses: Map<String, MessageDeliveryStatus>)

internal class OutgoingMessageTracker {
    private data class Entry(
        val local: TimelineItem,
        val baseline: Set<String>,
        var canonicalId: String? = null,
        var turnId: String? = null,
        var confirmed: Boolean = false,
        var finished: Boolean = false,
    )
    private val scopes = mutableMapOf<String, MutableList<Entry>>()
    private val completed = mutableMapOf<String, MutableSet<String>>()

    fun begin(scope: String, message: TimelineItem, existing: List<TimelineItem>) {
        scopes.getOrPut(scope) { mutableListOf() }.add(Entry(message, existing.map { it.id }.toSet()))
    }

    fun move(from: String, to: String) {
        scopes.remove(from)?.let { scopes.getOrPut(to) { mutableListOf() }.addAll(it) }
        completed.remove(from)?.let { completed.getOrPut(to) { mutableSetOf() }.addAll(it) }
    }

    fun confirm(scope: String, id: String, turnId: String?) {
        scopes[scope]?.firstOrNull { it.local.id == id }?.let {
            it.confirmed = true
            if (!turnId.isNullOrBlank()) it.turnId = turnId
            if (it.turnId in completed[scope].orEmpty()) it.finished = true
        }
    }

    fun finish(scope: String, turnId: String) {
        if (turnId.isBlank()) return
        completed.getOrPut(scope) { mutableSetOf() }.add(turnId)
        scopes[scope]?.filter { it.turnId == turnId }?.forEach { it.finished = true }
    }

    fun isConfirmed(scope: String, id: String): Boolean = scopes[scope]?.any { it.local.id == id && it.confirmed } == true

    /** A timeout cannot retract a message already observed on the host. */
    fun reject(scope: String, id: String) {
        scopes[scope]?.removeAll { it.local.id == id && !it.confirmed }
    }

    fun project(scope: String, source: List<TimelineItem>, turns: Map<String, DeliveryTurn>): DeliveredTimeline {
        val items = source.toMutableList()
        val statuses = mutableMapOf<String, MessageDeliveryStatus>()
        val preceding = mutableSetOf<String>()
        scopes[scope].orEmpty().forEach { entry ->
            val candidate = source.firstOrNull { item ->
                item.kind == TimelineItem.Kind.USER && (if (entry.canonicalId != null) item.id == entry.canonicalId else
                    (item.id !in entry.baseline && if (entry.turnId != null) {
                        item.id in turns[entry.turnId]?.userItemIds.orEmpty()
                    } else {
                        item.text == entry.local.text && item.attachments.map { it.name } == entry.local.attachments.map { it.name }
                    }))
            }
            if (candidate != null) {
                entry.canonicalId = candidate.id
                entry.confirmed = true
                entry.turnId = turns.entries.firstOrNull { candidate.id in it.value.userItemIds }?.key ?: entry.turnId
                val index = items.indexOfFirst { it.id == candidate.id }
                if (index >= 0) items[index] = candidate.copy(id = entry.local.id)
            } else {
                // Keep the optimistic message ahead of execution events that may precede its echo.
                val index = items.indexOfLast { it.id in entry.baseline || it.id in preceding } + 1
                items.add(index, entry.local)
            }
            preceding += entry.local.id
            if (turns[entry.turnId]?.finished == true || entry.turnId in completed[scope].orEmpty()) entry.finished = true
            if (!entry.finished) statuses[entry.local.id] = if (entry.confirmed) MessageDeliveryStatus.SENT else MessageDeliveryStatus.SENDING
        }
        return DeliveredTimeline(items, statuses)
    }
}
