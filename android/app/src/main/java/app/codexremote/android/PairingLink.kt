package app.codexremote.android

import java.net.URI
import java.net.URLDecoder
import java.nio.charset.StandardCharsets

internal data class PairingLink(val serverUrl: String, val code: String)

internal fun parsePairingLink(value: String?): PairingLink? {
    if (value.isNullOrBlank() || value.length > 4_096) return null
    val uri = runCatching { URI(value) }.getOrNull() ?: return null
    if (!uri.scheme.equals("codexremote", ignoreCase = true) ||
        !uri.host.equals("pair", ignoreCase = true) ||
        (!uri.path.isNullOrEmpty() && uri.path != "/") ||
        uri.userInfo != null || uri.fragment != null
    ) return null

    val parameters = linkedMapOf<String, String>()
    val query = uri.rawQuery ?: return null
    for (part in query.split('&')) {
        if (part.isEmpty()) continue
        val separator = part.indexOf('=')
        val rawKey = if (separator >= 0) part.substring(0, separator) else part
        val rawValue = if (separator >= 0) part.substring(separator + 1) else ""
        val key = decodeQueryPart(rawKey) ?: return null
        val decoded = decodeQueryPart(rawValue) ?: return null
        if (parameters.put(key, decoded) != null) return null
    }
    if (parameters.keys != setOf("server", "code")) return null

    val server = parameters.getValue("server").trim().trimEnd('/')
    val code = parameters.getValue("code").trim()
    if (server.length > 2_048 || !code.matches(Regex("[A-Za-z0-9_-]{16,256}"))) return null
    if (projectInputError(server, "/") != null) return null
    val endpoint = URI(server)
    if (connectionServerUrl(endpoint.scheme, endpoint.host,
            (if (endpoint.port == -1) if (endpoint.scheme == "https") 443 else 80 else endpoint.port).toString(),
            endpoint.rawPath.orEmpty()).isFailure) return null
    return PairingLink(server, code)
}

private fun decodeQueryPart(value: String): String? = runCatching {
    URLDecoder.decode(value, StandardCharsets.UTF_8.name())
}.getOrNull()
