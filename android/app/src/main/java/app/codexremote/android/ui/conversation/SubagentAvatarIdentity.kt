package app.codexremote.android.ui.conversation

import java.security.MessageDigest

internal data class SubagentAvatarSpec(
    val shape: Int,
    val palette: Int,
)

internal object SubagentAvatarIdentity {
    private val FALLBACK = SubagentAvatarSpec(shape = 0, palette = 0)

    fun forId(threadId: String): SubagentAvatarSpec {
        if (threadId.isBlank()) {
            return FALLBACK
        }
        val digest = MessageDigest.getInstance("SHA-256")
            .digest(threadId.toByteArray(Charsets.UTF_8))
        val shape = (digest[0].toInt() and 0xFF) % 8
        val palette = (digest[1].toInt() and 0xFF) % 8
        return SubagentAvatarSpec(shape = shape, palette = palette)
    }
}
