package app.codexremote.android

sealed interface MarkdownBlock {
    data class Paragraph(val text: String) : MarkdownBlock
    data class Heading(val level: Int, val text: String) : MarkdownBlock
    data class ListBlock(val ordered: Boolean, val items: List<String>, val start: Int = 1, val loose: Boolean = false) : MarkdownBlock
    data class Code(val language: String, val text: String) : MarkdownBlock
    data class Quote(val text: String) : MarkdownBlock
    data class Image(val alt: String, val source: String) : MarkdownBlock
    enum class Alignment { LEFT, CENTER, RIGHT }
    data class Table(val headers: List<String>, val alignment: List<Alignment>, val rows: List<List<String>>, val source: String) : MarkdownBlock
    data object Rule : MarkdownBlock
}

sealed interface InlineMarkdownNode {
    data class Text(val value: String) : InlineMarkdownNode
    data class Code(val value: String) : InlineMarkdownNode
    data class Strong(val value: String) : InlineMarkdownNode
    data class Link(val label: String, val url: String) : InlineMarkdownNode
}

object InlineMarkdownParser {
    private val token = Regex("`([^`]+)`|\\*\\*([^*]+)\\*\\*|(?<!!)\\[([^]]+)]\\(((?:https?|remote-artifact)://[^)]+)\\)")

    fun parse(source: String): List<InlineMarkdownNode> = buildList {
        var cursor = 0
        token.findAll(source).forEach { match ->
            if (match.range.first > cursor) add(InlineMarkdownNode.Text(source.substring(cursor, match.range.first)))
            when {
                match.groupValues[1].isNotEmpty() -> add(InlineMarkdownNode.Code(match.groupValues[1]))
                match.groupValues[2].isNotEmpty() -> add(InlineMarkdownNode.Strong(match.groupValues[2]))
                else -> add(InlineMarkdownNode.Link(match.groupValues[3], match.groupValues[4]))
            }
            cursor = match.range.last + 1
        }
        if (cursor < source.length) add(InlineMarkdownNode.Text(source.substring(cursor)))
    }
}

object MarkdownParser {
    private val codeFence = Regex("^( {0,3})(`{3,}|~{3,})(.*)$")
    private val heading = Regex("^(#{1,6})\\s+(.+)$")
    private val bullet = Regex("^\\s*[-+*]\\s+(.+)$")
    private val ordered = Regex("^\\s*(\\d{1,9})[.)]\\s+(.+)$")
    private val image = Regex("^\\s*!\\[([^]]*)]\\(([^)]+)\\)\\s*$")
    private val rule = Regex("^ {0,3}(?:(?:-[ \\t]*){3,}|(?:_[ \\t]*){3,}|(?:\\*[ \\t]*){3,})$")

    fun parse(markdown: String): List<MarkdownBlock> {
        val result = mutableListOf<MarkdownBlock>()
        val paragraph = mutableListOf<String>()
        val lines = markdown.replace("\r\n", "\n").split('\n')
        var index = 0

        fun flushParagraph() {
            if (paragraph.isNotEmpty()) {
                result += MarkdownBlock.Paragraph(paragraph.joinToString("\n").trim())
                paragraph.clear()
            }
        }

        while (index < lines.size) {
            val line = lines[index]
            val fence = codeFence.matchEntire(line)?.takeIf {
                it.groupValues[2][0] != '`' || '`' !in it.groupValues[3]
            }
            if (fence != null) {
                flushParagraph()
                val marker = fence.groupValues[2]
                val indent = fence.groupValues[1].length
                val language = fence.groupValues[3].trim()
                val code = mutableListOf<String>()
                index++
                while (index < lines.size) {
                    val content = lines[index]
                    val leading = content.takeWhile { it == ' ' }.length
                    val candidate = content.drop(leading)
                    val count = candidate.takeWhile { it == marker[0] }.length
                    if (leading <= 3 && count >= marker.length && candidate.drop(count).all { it == ' ' || it == '\t' }) {
                        index++
                        break
                    }
                    code += content.drop(minOf(indent, leading))
                    index++
                }
                result += MarkdownBlock.Code(language, code.joinToString("\n"))
                continue
            }
            val headers = if ('|' in line) tableCells(line) else emptyList()
            val separators = lines.getOrNull(index + 1)?.let(::tableCells).orEmpty()
            if (headers.isNotEmpty() && separators.size == headers.size && separators.all { it.matches(Regex(":?-+:?")) }) {
                flushParagraph()
                val start = index
                val alignment = separators.map {
                    when {
                        it.startsWith(':') && it.endsWith(':') -> MarkdownBlock.Alignment.CENTER
                        it.endsWith(':') -> MarkdownBlock.Alignment.RIGHT
                        else -> MarkdownBlock.Alignment.LEFT
                    }
                }
                index += 2
                val rows = mutableListOf<List<String>>()
                while (index < lines.size && !endsTable(lines[index])) {
                    val cells = tableCells(lines[index++])
                    rows += List(headers.size) { cells.getOrElse(it) { "" } }
                }
                result += MarkdownBlock.Table(headers, alignment, rows, lines.subList(start, index).joinToString("\n"))
                continue
            }
            image.matchEntire(line)?.let {
                flushParagraph()
                result += MarkdownBlock.Image(it.groupValues[1], it.groupValues[2].trim())
                index++
                continue
            }
            heading.matchEntire(line)?.let {
                flushParagraph()
                result += MarkdownBlock.Heading(it.groupValues[1].length, it.groupValues[2])
                index++
                continue
            }
            if (rule.matches(line)) {
                flushParagraph()
                result += MarkdownBlock.Rule
                index++
                continue
            }
            val firstBullet = bullet.matchEntire(line)
            val firstOrdered = ordered.matchEntire(line)
            if (firstBullet != null || firstOrdered != null) {
                flushParagraph()
                val isOrdered = firstOrdered != null
                val items = mutableListOf<String>()
                var loose = false
                // A blank separator loosens a list; it does not restart numbering.
                val markerType = if (isOrdered) line.trimStart().dropWhile { it.isDigit() }.first() else line.trimStart().first()
                fun sameList(candidate: String): MatchResult? {
                    if (rule.matches(candidate)) return null
                    val match = (if (isOrdered) ordered else bullet).matchEntire(candidate) ?: return null
                    val type = if (isOrdered) candidate.trimStart().dropWhile { it.isDigit() }.first() else candidate.trimStart().first()
                    return match.takeIf { type == markerType }
                }
                while (index < lines.size) {
                    val match = sameList(lines[index]) ?: break
                    items += match.groupValues[if (isOrdered) 2 else 1]
                    index++
                    if (lines.getOrNull(index)?.isBlank() == true) {
                        var next = index
                        while (lines.getOrNull(next)?.isBlank() == true) next++
                        if (lines.getOrNull(next)?.let(::sameList) != null) {
                            loose = true
                            index = next
                        } else break
                    }
                }
                result += MarkdownBlock.ListBlock(isOrdered, items, firstOrdered?.groupValues?.get(1)?.toInt() ?: 1, loose)
                continue
            }
            if (line.trimStart().startsWith(">")) {
                flushParagraph()
                val quote = mutableListOf<String>()
                while (index < lines.size && lines[index].trimStart().startsWith(">")) {
                    quote += lines[index].trimStart().removePrefix(">").trimStart()
                    index++
                }
                result += MarkdownBlock.Quote(quote.joinToString("\n"))
                continue
            }
            if (line.isBlank()) flushParagraph() else paragraph += line
            index++
        }
        flushParagraph()
        return result
    }
    private fun endsTable(line: String): Boolean = line.isBlank() ||
        line.trimStart().startsWith("```") || line.trimStart().startsWith(">") ||
        heading.matches(line) || rule.matches(line) || bullet.matches(line) || ordered.matches(line) || image.matches(line)

    /** Escaped pipes remain literal, including inside inline code (GFM table extension). */
    private fun tableCells(line: String): List<String> {
        val input = line.trim()
        val cells = mutableListOf<String>()
        val cell = StringBuilder()
        var index = 0
        while (index < input.length) {
            val ch = input[index]
            if (ch == '\\' && index + 1 < input.length) {
                val next = input[index + 1]
                if (next == '|') { cell.append('|'); index += 2; continue }
                cell.append(ch).append(next); index += 2; continue
            }
            if (ch == '|') { cells += cell.toString().trim(); cell.clear() } else cell.append(ch)
            index++
        }
        cells += cell.toString().trim()
        if (input.startsWith('|')) cells.removeAt(0)
        if (cells.size > 1 && cells.last().isEmpty() && input.endsWith('|')) cells.removeAt(cells.lastIndex)
        return cells
    }

}
