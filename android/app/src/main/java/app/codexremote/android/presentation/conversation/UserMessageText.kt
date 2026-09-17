package app.codexremote.android.presentation.conversation

/** Display-only repair for whitespace serialized by the desktop rich-text editor.
 * Keep the original message for submission/history and leave inline/code examples literal.
 */
object UserMessageText {
    private val leadingWhitespace = Regex("^(?:[ \\t\\r\\n]|&#(?:[xX]0*20|0*32);)+")
    private val encodedSpace = Regex("&#(?:[xX]0*20|0*32);")

    fun display(source: String): String {
        val prefix = leadingWhitespace.find(source) ?: return source
        return encodedSpace.replace(prefix.value, " ") + source.substring(prefix.value.length)
    }
}
