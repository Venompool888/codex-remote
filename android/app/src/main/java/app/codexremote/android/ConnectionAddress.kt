package app.codexremote.android

import java.net.URI

/** Compose an HTTP endpoint without allowing user-info, URL queries or path traversal. */
internal fun connectionServerUrl(protocol: String, host: String, port: String, basePath: String): Result<String> = runCatching {
    require(protocol in setOf("http", "https")) { "Choose HTTP or HTTPS" }
    val hostname = host.trim().removePrefix("[").removeSuffix("]")
    require(hostname.isNotBlank() && hostname.none { it.isWhitespace() || it in "/?#@\\" }) { "Enter a domain or IP without a protocol or path" }
    val number = port.trim().toIntOrNull()
    require(number != null && number in 1..65535) { "Enter a port between 1 and 65535" }
    val authority = if (':' in hostname) "[$hostname]" else hostname
    val raw = basePath.trim().ifEmpty { "/" }
    require(raw.startsWith('/') && !raw.startsWith("//") && raw.none { it in "?#\\" || it.isWhitespace() }) { "Base path must start with / and contain no query or fragment" }
    val url = URI("$protocol://$authority:$number$raw")
    require(!url.host.isNullOrBlank() && url.userInfo == null && url.port == number) { "Enter a valid domain or IP" }
    require(url.path.split('/').none { it == "." || it == ".." }) { "Base path cannot contain . or .. segments" }
    val defaultPort = if (protocol == "https") 443 else 80
    "$protocol://$authority${if (number == defaultPort) "" else ":$number"}${url.rawPath.trimEnd('/')}"
}
