package app.codexremote.android

internal data class CapabilityCacheScope(val server: String, val device: String, val cwd: String)

/** Short-lived UI snapshots only. The host remains authority when a capability is sent. */
internal class CapabilityPresentationCache(
    private val now: () -> Long = { System.nanoTime() / 1_000_000 },
    private val ttlMs: Long = 30_000,
) {
    private data class Entry(val saved: Long, val value: String)
    private val entries = LinkedHashMap<CapabilityCacheScope, Entry>()
    var generation: Long = 0
        private set

    fun get(scope: CapabilityCacheScope): String? {
        val value = entries.remove(scope) ?: return null
        if (scope.device.isBlank() || now() - value.saved !in 0 until ttlMs) return null
        entries[scope] = value
        return value.value
    }
    fun put(scope: CapabilityCacheScope, value: String, expectedGeneration: Long): Boolean {
        if (expectedGeneration != generation || scope.device.isBlank() || value.length > 262_144) return false
        entries.remove(scope)
        entries[scope] = Entry(now(), value)
        while (entries.size > 8) entries.remove(entries.keys.first())
        return true
    }
    fun invalidate(scope: CapabilityCacheScope) { entries.remove(scope); generation++ }
    fun invalidateServer(server: String) { entries.keys.removeAll { it.server == server }; generation++ }
}
