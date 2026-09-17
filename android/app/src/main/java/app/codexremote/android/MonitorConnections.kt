package app.codexremote.android

/** Owns one monitor connection per configured host and credential generation. */
internal class MonitorConnections<T : Any> {
    private data class Entry<T>(val credential: String, val client: T)
    private val owned = mutableMapOf<String, Entry<T>>()

    fun isCurrent(server: String, client: T): Boolean = owned[server]?.client === client

    fun reconcile(desired: Map<String, String>, create: (String) -> T, close: (T) -> Unit): Map<String, T> {
        val retired = owned.filter { (server, entry) -> desired[server] != entry.credential }
        // Invalidate ownership before close can deliver a disconnect callback.
        retired.keys.forEach(owned::remove)
        retired.values.forEach { close(it.client) }
        return buildMap {
            desired.forEach { (server, credential) ->
                if (server !in owned) {
                    val client = create(server)
                    owned[server] = Entry(credential, client)
                    put(server, client)
                }
            }
        }
    }

    fun closeAll(close: (T) -> Unit) {
        val retired = owned.values.toList()
        owned.clear()
        retired.forEach { close(it.client) }
    }
}
