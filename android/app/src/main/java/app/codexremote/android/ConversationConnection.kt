package app.codexremote.android

internal fun conversationConnectionText(server: String?, projects: List<RemoteProject>, connected: Boolean, offlineStatus: String? = null): String {
    val normalized = server?.trim()?.trimEnd('/').orEmpty()
    val name = projects.firstOrNull { it.serverUrl.trim().trimEnd('/') == normalized }
        ?.connectionName?.trim()?.takeIf(String::isNotEmpty)
        ?: runCatching { java.net.URI(normalized).host }.getOrNull()?.takeIf(String::isNotEmpty)
        ?: "Remote host"
    val status = if (connected) "Connected" else offlineStatus ?: "Offline · reconnecting"
    return "$status · $name"
}
