package app.codexremote.android

/** Per-host pagination tickets discard replies superseded by a fresh list request. */
internal class ThreadPages {
    data class Ticket(val host: String, val generation: Long, val cursor: String?)
    private data class Page(var generation: Long = 0, var cursor: String? = null,
        var loading: Boolean = false, var failed: Boolean = false, var requestedOlder: Boolean = false)
    private val pages = mutableMapOf<String, Page>()
    private var generation = 0L
    fun begin(host: String, older: Boolean): Ticket? {
        val page = pages.getOrPut(host) { Page() }
        if (older && (page.loading || page.cursor == null)) return null
        page.generation = ++generation
        page.loading = true
        page.failed = false
        page.requestedOlder = older
        return Ticket(host, page.generation, if (older) page.cursor else null)
    }
    fun accepts(ticket: Ticket) = pages[ticket.host]?.generation == ticket.generation
    fun finish(ticket: Ticket, next: String?) {
        if (!accepts(ticket)) return
        pages.getValue(ticket.host).apply {
            cursor = next?.takeIf { it.isNotBlank() && it != ticket.cursor }
            loading = false
            failed = false
        }
    }
    fun fail(ticket: Ticket) {
        if (accepts(ticket)) pages.getValue(ticket.host).apply { loading = false; failed = true }
    }
    fun remove(host: String) { pages.remove(host) }
    fun hasMore(host: String?) = pages[host]?.cursor != null
    fun loading(host: String?) = pages[host]?.loading == true
    fun failed(host: String?) = pages[host]?.failed == true
    fun requestOlder(host: String?): Boolean = pages[host]?.let {
        if (it.failed || it.loading) it.requestedOlder else it.cursor != null
    } ?: false
    fun merge(ticket: Ticket, existing: List<RemoteThread>, incoming: List<RemoteThread>): List<RemoteThread> =
        (if (ticket.cursor == null) incoming else existing + incoming).distinctBy { it.id }
}
