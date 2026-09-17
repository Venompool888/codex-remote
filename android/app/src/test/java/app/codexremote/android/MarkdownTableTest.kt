package app.codexremote.android

import org.junit.Assert.*
import org.junit.Test

class MarkdownTableTest {
    @Test fun parsesAlignmentAndRetainsCopySource() {
        val source = "| Name | Type | Size |\n| :- | :-: | -: |\n| **Note** | `text` | 12 KB |"
        val table = MarkdownParser.parse(source).single() as MarkdownBlock.Table
        assertEquals(listOf(MarkdownBlock.Alignment.LEFT, MarkdownBlock.Alignment.CENTER, MarkdownBlock.Alignment.RIGHT), table.alignment)
        assertEquals(listOf("**Note**", "`text`", "12 KB"), table.rows.single())
        assertEquals(source, table.source)
    }
    @Test fun escapedPipesAndMissingOrExtraCellsFollowGfm() {
        val table = MarkdownParser.parse("| f\\|oo | other |\n| - | - |\n| `a\\|b` |\n| x | y | ignored |\nplain").single() as MarkdownBlock.Table
        assertEquals(listOf("f|oo", "other"), table.headers)
        assertEquals(listOf(listOf("`a|b`", ""), listOf("x", "y"), listOf("plain", "")), table.rows)
    }
    @Test fun incompleteDelimiterAndMismatchedColumnsStayReadable() {
        for (source in listOf("| a | b |\n| --", "| a | b |\n| - |"))
            assertTrue(MarkdownParser.parse(source).single() is MarkdownBlock.Paragraph)
    }
    @Test fun tableStopsBeforeFollowingBlockAndIsIgnoredInsideCode() {
        val blocks = MarkdownParser.parse("a | b\n- | -\nx | y\n> quote\n\n```md\na | b\n- | -\n```")
        assertTrue(blocks[0] is MarkdownBlock.Table)
        assertEquals(MarkdownBlock.Quote("quote"), blocks[1])
        assertTrue(blocks[2] is MarkdownBlock.Code)
    }
}
