package app.codexremote.android

/** Main-thread fence for callbacks that belong to an earlier conversation selection. */
class NavigationRequests {
    private var generation = 0L
    data class Ticket internal constructor(val server: String?, val thread: String?, internal val generation: Long)
    fun invalidate() { generation++ }
    fun capture(server: String?, thread: String?) = Ticket(server, thread, generation)
    fun accepts(ticket: Ticket, server: String?, thread: String?) =
        ticket.generation == generation && ticket.server == server && ticket.thread == thread
}
