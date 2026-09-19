package app.codexremote.android

import org.json.JSONObject

data class SubagentDirectoryEntry(
    val id: String,
    val name: String,
    val status: String,
    val parentId: String? = null,
    val canAcceptDirectInput: Boolean? = null,
    val role: String? = null,
)

data class SubagentReference(
    val threadId: String,
    val name: String = "Subagent",
    val status: String = "unknown",
    val message: String = "",
    val model: String = "",
) {
    companion object {
        fun fromItem(item: JSONObject): List<SubagentReference> {
            fun text(value: JSONObject, key: String) = value.optString(key).takeUnless { it == "null" }.orEmpty()
            val states = item.optJSONObject("agentsStates") ?: JSONObject()
            val ids = linkedSetOf<String>()
            item.optJSONArray("receiverThreadIds")?.let { array ->
                for (i in 0 until array.length()) if (array.opt(i) is String) ids += array.getString(i)
            }
            states.keys().forEach { ids += it }
            text(item, "agentThreadId").takeIf(String::isNotBlank)?.let(ids::add)
            return ids.filter { it.isNotBlank() && it != "null" && it != text(item, "senderThreadId") }.map { id ->
                val state = states.optJSONObject(id) ?: JSONObject()
                SubagentReference(id, text(item, "agentPath").ifBlank { "Subagent" },
                    text(state, "status").ifBlank { "unknown" }, text(state, "message"), text(item, "model"))
            }
        }

        fun collect(items: List<TimelineItem>): List<SubagentReference> {
            val agents = linkedMapOf<String, SubagentReference>()
            fun visit(item: TimelineItem) {
                item.subagents.forEach { next ->
                    val previous = agents[next.threadId]
                    agents[next.threadId] = next.copy(
                        status = next.status.takeUnless { it == "unknown" } ?: previous?.status ?: "unknown",
                        name = next.name.takeUnless { it == "Subagent" } ?: previous?.name ?: "Subagent",
                        model = next.model.ifBlank { previous?.model.orEmpty() },
                        message = next.message.ifBlank { previous?.message.orEmpty() },
                    )
                }
                item.children.forEach(::visit)
            }
            items.forEach(::visit)
            return agents.values.toList()
        }
    }
}
