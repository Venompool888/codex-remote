package app.codexremote.android

import java.security.MessageDigest

/** Length-prefixed identity keeps private source paths out of cache names. */
internal object RemoteImageIdentity {
    fun key(server: String, device: String, thread: String?, source: String): String {
        val fields = listOf(server, device, thread.orEmpty(), source)
        val identity = fields.joinToString("") { "${it.length}:$it" }
        return "generated-v2:" + MessageDigest.getInstance("SHA-256")
            .digest(identity.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }
    }
}
