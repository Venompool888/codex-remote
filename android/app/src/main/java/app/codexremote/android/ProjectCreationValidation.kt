package app.codexremote.android

import java.net.URI

/** Validate both initial pairing paths and host-issued opaque workspace references. */
internal fun projectInputError(server: String, workspace: String): String? {
    val uri = runCatching { URI(server) }.getOrNull()
    if (uri == null || uri.scheme !in setOf("http", "https") || uri.host.isNullOrBlank() ||
        uri.userInfo != null || uri.query != null || uri.fragment != null) {
        return "Enter a valid HTTP or HTTPS server URL"
    }
    if (!workspace.startsWith('/') && !workspace.matches(Regex("remote-workspace://[a-f0-9]{64}"))) {
        return "Enter an absolute remote folder path or choose a known project"
    }
    return null
}

/** A late validation reply may not save a cancelled project or select a different host. */
internal fun acceptsProjectValidation(pending: RemoteProject?, expected: RemoteProject, selectedServer: String?): Boolean =
    pending?.id == expected.id && pending.serverUrl == expected.serverUrl && selectedServer == expected.serverUrl &&
        pending.workspace == expected.workspace
