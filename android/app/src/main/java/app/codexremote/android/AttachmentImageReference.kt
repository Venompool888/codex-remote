package app.codexremote.android

/** Host-redacted image references contain an attachment ID and a display-only filename. */
internal object AttachmentImageReference {
    private val reference = Regex("^remote-attachment://([a-fA-F0-9]{8}-[a-fA-F0-9]{4}-[a-fA-F0-9]{4}-[a-fA-F0-9]{4}-[a-fA-F0-9]{12})-([^/\\\\\\u0000-\\u001f]{1,255})$")
    fun parse(source: String): MessageAttachment? {
        val match = reference.matchEntire(source) ?: return null
        return MessageAttachment(match.groupValues[1].lowercase(java.util.Locale.ROOT), match.groupValues[2], "image")
    }
}
