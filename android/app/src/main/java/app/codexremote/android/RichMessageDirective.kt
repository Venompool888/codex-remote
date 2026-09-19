package app.codexremote.android

/** Display metadata, never instructions to execute automatically. */
sealed interface RichMessageDirective {
    data class CodeComment(val title: String, val body: String, val file: String, val startLine: Int?, val endLine: Int?, val priority: Int?) : RichMessageDirective
    data class FollowUp(val label: String, val prompt: String) : RichMessageDirective
    data class Parsed(val before: String, val directive: RichMessageDirective, val after: String)

    companion object {
        private val attribute = Regex("([A-Za-z][A-Za-z0-9_]*)=\"((?:[^\"\\\\]|\\\\.)*)\"")
        /** Bounded line parser; fenced code is left to Markdown and never yields actionable UI. */
        fun parseLine(line: String): RichMessageDirective? {
            val value = line.trim().removePrefix("- ")
            if (value.length > 32_768 || !value.endsWith('}')) return null
            val open = value.indexOf('{')
            if (open < 0) return null
            val header = value.substring(0, open)
            val raw = value.substring(open + 1, value.lastIndex)
            val matches = attribute.findAll(raw).toList()
            if (matches.isEmpty() || matches.size > 12) return null
            var cursor = 0
            val attrs = linkedMapOf<String, String>()
            for (match in matches) {
                if (raw.substring(cursor, match.range.first).isNotBlank()) return null
                val key = match.groupValues[1]
                if (key in attrs) return null
                attrs[key] = unescape(match.groupValues[2])
                cursor = match.range.last + 1
            }
            if (raw.substring(cursor).isNotBlank()) return null
            return when {
                header == "::code-comment" -> {
                    val title = attrs["title"]?.takeIf { it.isNotBlank() } ?: return null
                    val body = attrs["body"]?.takeIf { it.isNotBlank() } ?: return null
                    val file = attrs["file"]?.takeIf { it.isNotBlank() } ?: return null
                    val start = attrs["start"]?.toIntOrNull()?.takeIf { it > 0 }
                    val end = attrs["end"]?.toIntOrNull()?.takeIf { it > 0 }
                    if ((attrs.containsKey("start") && start == null) || (attrs.containsKey("end") && end == null) ||
                        (start != null && end != null && end < start)) return null
                    CodeComment(title, body, file, start, end, attrs["priority"]?.toIntOrNull()?.takeIf { it in 0..3 })
                }
                header.startsWith(":codex-followup[") && header.endsWith(']') -> {
                    val label = header.removePrefix(":codex-followup[").dropLast(1)
                    val prompt = attrs["prompt"]?.takeIf { it.isNotBlank() } ?: return null
                    if (label.isBlank()) null else FollowUp(label, prompt)
                }
                else -> null
            }
        }
        fun extract(markdown: String): List<RichMessageDirective> {
            var fence: Char? = null
            var fenceLength = 0
            return buildList {
                markdown.lineSequence().forEach { line ->
                    val trimmed = line.trimStart()
                    val marker = trimmed.firstOrNull()
                    if (marker == '`' || marker == '~') {
                        val count = trimmed.takeWhile { it == marker }.length
                        if (count >= 3) {
                            if (fence == null) { fence = marker; fenceLength = count }
                            else if (fence == marker && count >= fenceLength && trimmed.drop(count).isBlank()) fence = null
                            return@forEach
                        }
                    }
                    if (fence == null) parseLine(line)?.let(::add)
                }
            }
        }
        private fun unescape(text: String): String = buildString {
            var index = 0
            while (index < text.length) {
                if (text[index] == '\\' && index + 1 < text.length && text[index + 1] in charArrayOf('\\', '"')) index++
                append(text[index++])
            }
        }
    }
}
