package app.codexremote.android

/** Display-only fenced code ranges. The timeline and draft always retain the original source. */
internal object UserCodePresentation {
    data class Display(val text: String, val codeRanges: List<IntRange>)
    fun format(source: String): Display {
        val opening = Regex("(?m)^ {0,3}(`{3,})([^`\\r\\n]*)\\r?\\n")
        val output = StringBuilder()
        val ranges = mutableListOf<IntRange>()
        var cursor = 0
        for (start in opening.findAll(source)) {
            if (start.range.first < cursor) continue
            val length = start.groupValues[1].length
            val closing = Regex("(?m)^ {0,3}`{$length,}[ \\t]*(?=\\r?$)")
                .find(source, start.range.last + 1) ?: continue
            output.append(source, cursor, start.range.first)
            val codeStart = output.length
            val language = start.groupValues[2].trim()
            if (language.isNotEmpty()) output.append(language).append('\n')
            output.append(source.substring(start.range.last + 1, closing.range.first)
                .removeSuffix("\n").removeSuffix("\r"))
            if (output.length > codeStart) ranges += codeStart until output.length
            cursor = closing.range.last + 1
        }
        output.append(source, cursor, source.length)
        return Display(output.toString(), ranges)
    }
}
