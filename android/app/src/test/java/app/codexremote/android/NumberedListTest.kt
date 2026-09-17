package app.codexremote.android

import org.junit.Assert.*
import org.junit.Test

class NumberedListTest {
    @Test fun retainsStartAndUsesConsecutiveNumbersRegardlessOfLaterSourceMarkers() {
        val block = MarkdownParser.parse("9. First\n1. Second\n15. Third").single() as MarkdownBlock.ListBlock
        assertEquals(9, block.start)
        assertEquals(listOf("First", "Second", "Third"), block.items)
    }
    @Test fun handlesZeroAndParenthesisAndDoesNotInventNumbersForProse() {
        assertEquals(0, (MarkdownParser.parse("0) Zero\n1) Next").single() as MarkdownBlock.ListBlock).start)
        assertTrue(MarkdownParser.parse("1234567890. Too long to be a Markdown list").single() is MarkdownBlock.Paragraph)
    }
    @Test fun unorderedListAndTableBoundaryRemainIntact() {
        assertEquals(MarkdownBlock.ListBlock(false, listOf("One", "Two")), MarkdownParser.parse("- One\n- Two").single())
        val blocks = MarkdownParser.parse("a | b\n- | -\nx | y\n9. Next")
        assertTrue(blocks[0] is MarkdownBlock.Table)
        assertEquals(9, (blocks[1] as MarkdownBlock.ListBlock).start)
    }
}
