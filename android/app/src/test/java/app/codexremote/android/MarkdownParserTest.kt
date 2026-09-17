package app.codexremote.android

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MarkdownParserTest {
    @Test
    fun parsesHeadingsListsImagesAndFencedCode() {
        val blocks = MarkdownParser.parse("""
            ## Result

            - first
            - second

            ![proxy result](/tmp/result.png)

            ```text
            Accept-Encoding: identity
            ```
        """.trimIndent())

        assertEquals(MarkdownBlock.Heading(2, "Result"), blocks[0])
        assertEquals(MarkdownBlock.ListBlock(false, listOf("first", "second")), blocks[1])
        assertEquals(MarkdownBlock.Image("proxy result", "/tmp/result.png"), blocks[2])
        assertEquals(MarkdownBlock.Code("text", "Accept-Encoding: identity"), blocks[3])
    }

    @Test
    fun leavesInlineMarkdownInParagraphForSpanRendering() {
        val block = MarkdownParser.parse("Use `gzip` and **verify** it.").single()
        assertTrue(block is MarkdownBlock.Paragraph)
        assertEquals("Use `gzip` and **verify** it.", (block as MarkdownBlock.Paragraph).text)
    }

    @Test
    fun inlineCommandsRemainAtomicNodesWithTheirArguments() {
        assertEquals(
            listOf(
                InlineMarkdownNode.Text("Run "),
                InlineMarkdownNode.Code("uname -a"),
                InlineMarkdownNode.Text(" then "),
                InlineMarkdownNode.Code("df -h /"),
                InlineMarkdownNode.Text("."),
            ),
            InlineMarkdownParser.parse("Run `uname -a` then `df -h /`."),
        )
    }

    @Test
    fun inlineParserPreservesTextStrongAndLinksInProtocolOrder() {
        assertEquals(
            listOf(
                InlineMarkdownNode.Text("See "),
                InlineMarkdownNode.Strong("result"),
                InlineMarkdownNode.Text(" at "),
                InlineMarkdownNode.Link("docs", "https://example.com/docs"),
            ),
            InlineMarkdownParser.parse("See **result** at [docs](https://example.com/docs)"),
        )
    }

    @Test fun longerFencePreservesNestedCodeExample() {
        val body = "Before\n```kotlin\nval n = 1\n```\nAfter"
        assertEquals(listOf(MarkdownBlock.Code("text", body), MarkdownBlock.Paragraph("Outside")),
            MarkdownParser.parse("````text\n$body\n`````\nOutside"))
    }

    @Test fun fenceCloserRequiresMatchingTypeLengthAndWhitespaceSuffix() {
        val body = "~~~\n``` trailing\n``\n    ```\nkeep"
        assertEquals(MarkdownBlock.Code("", body), MarkdownParser.parse("```\n$body\n```  \t").single())
    }

    @Test fun tildeFencePreservesBackticksAndShorterTildes() {
        val body = "```\n~~~\n**literal**"
        assertEquals(MarkdownBlock.Code("text", body), MarkdownParser.parse("~~~~text\n$body\n~~~~").single())
    }

    @Test fun openStreamingFencePreservesIncompleteClosingMarker() {
        assertEquals(MarkdownBlock.Code("text", "line\n```"), MarkdownParser.parse("````text\nline\n```").single())
    }

    @Test fun fenceIndentRemovesOnlyAvailableOpeningIndent() {
        assertEquals(MarkdownBlock.Code("text", "one\n two\nthree"),
            MarkdownParser.parse("  ```text\n  one\n   two\nthree\n ```").single())
    }

    @Test fun invalidFenceOpenersRemainText() {
        assertTrue(MarkdownParser.parse("```bad`info\nbody").none { it is MarkdownBlock.Code })
        assertTrue(MarkdownParser.parse("    ```\nbody\n    ```").none { it is MarkdownBlock.Code })
    }
    @Test fun blankSeparatedListKeepsItsStartAndLooseSpacing() {
        assertEquals(MarkdownBlock.ListBlock(true, listOf("one", "two", "three"), 9, true),
            MarkdownParser.parse("9. one\n\n1. two\n1. three").single())
    }
    @Test fun blankBeforeFollowingParagraphDoesNotLoosenList() {
        assertEquals(listOf(MarkdownBlock.ListBlock(false, listOf("one", "two")), MarkdownBlock.Paragraph("Outside")),
            MarkdownParser.parse("- one\n- two\n\nOutside"))
    }
    @Test fun changedListMarkersStartSeparateLists() {
        assertEquals(4, MarkdownParser.parse("- one\n+ two\n9. three\n1) four").size)
    }
    @Test fun thematicBreakIsNotConsumedAsListItem() {
        assertEquals(listOf(MarkdownBlock.ListBlock(false, listOf("one")), MarkdownBlock.Rule,
            MarkdownBlock.ListBlock(false, listOf("two"))), MarkdownParser.parse("* one\n* * *\n* two"))
    }

}
