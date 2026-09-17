package app.codexremote.android

import android.content.Context

/** Device-local removal, deliberately available even when the host is offline. */
internal class ConnectionDeletion(private val context: Context) {
    fun delete(serverUrls: Set<String>): List<RemoteProject> {
        val targets = serverUrls.map { it.trim().trimEnd('/') }.filter(String::isNotBlank).toSet()
        val tokens = SecureTokenStore(context)
        targets.forEach(tokens::clear)
        val settings = context.getSharedPreferences("remote_settings", Context.MODE_PRIVATE)
        if (settings.getString("server_url", null)?.trim()?.trimEnd('/') in targets) {
            // Otherwise deleting the last connection would migrate it back at next launch.
            settings.edit().remove("server_url").remove("workspace").apply()
        }
        context.getSharedPreferences("remote_navigation", Context.MODE_PRIVATE).edit().apply {
            targets.forEach { server -> remove("thread:$server"); remove("cwd:$server") }
        }.apply()
        return RemoteProjectStore(context).deleteConnections(targets)
    }
}
