package app.codexremote.android

/** Peer close reasons and exception messages may contain sensitive transport metadata. */
internal object ConnectionFailureText {
    fun status(reason: String?): String? = when (reason) {
        http(401), closed(4001) -> "Device authorization required"
        http(403), closed(1008) -> "Access denied"
        closed(1002) -> "Protocol mismatch"
        else -> null
    }
    fun closed(code: Int): String = when (code) {
        4001 -> "Device authorization changed. Reconnect with current credentials or pair again."
        1002 -> "Protocol negotiation failed. Check the host and client versions."
        1003 -> "The host rejected this connection's message format."
        1008 -> "The host denied this connection. Check device access on the host."
        1009 -> "A connection message exceeded the host limit."
        1001, 1012 -> "Host restarting or unavailable · reconnecting"
        else -> "Connection closed · reconnecting"
    }
    fun http(status: Int?): String = when (status) {
        401 -> "Device authorization expired or was revoked. Pair this host again."
        403 -> "Access denied. Check device and tunnel access on the host."
        429 -> "Too many connection attempts. Waiting before retrying."
        else -> "Connection lost · retrying securely"
    }
}
