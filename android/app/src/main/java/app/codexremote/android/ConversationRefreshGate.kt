package app.codexremote.android

internal data class ConversationRefreshTicket(
    val serverUrl: String,
    val threadId: String,
    val requestGeneration: Long,
    val connectionGeneration: Long,
)

/** Owns refresh coalescing without letting an obsolete transport callback release a newer request. */
internal class ConversationRefreshGate {
    private val connectionGenerations = mutableMapOf<String, Long>()
    private var nextRequestGeneration = 0L
    private var active: ConversationRefreshTicket? = null
    private var followUpRequested = false

    fun begin(serverUrl: String, threadId: String): ConversationRefreshTicket? {
        if (active != null) {
            followUpRequested = true
            return null
        }
        return ConversationRefreshTicket(
            serverUrl = serverUrl,
            threadId = threadId,
            requestGeneration = ++nextRequestGeneration,
            connectionGeneration = connectionGenerations[serverUrl] ?: 0L,
        ).also { active = it }
    }

    fun isCurrent(ticket: ConversationRefreshTicket): Boolean =
        active == ticket && (connectionGenerations[ticket.serverUrl] ?: 0L) == ticket.connectionGeneration

    fun complete(ticket: ConversationRefreshTicket): Boolean {
        if (!isCurrent(ticket)) return false
        active = null
        return true
    }

    fun connectionChanged(serverUrl: String) {
        connectionGenerations[serverUrl] = (connectionGenerations[serverUrl] ?: 0L) + 1L
        if (active?.serverUrl == serverUrl) {
            active = null
            followUpRequested = false
        }
    }

    fun consumeFollowUp(): Boolean {
        if (!followUpRequested) return false
        followUpRequested = false
        return true
    }

    fun reset() {
        active = null
        followUpRequested = false
    }
}
