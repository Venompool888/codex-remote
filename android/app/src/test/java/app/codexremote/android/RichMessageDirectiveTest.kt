package app.codexremote.android

import org.junit.Assert.*
import org.junit.Test

class RichMessageDirectiveTest {
    @Test fun extractsTypedCommentAndPreservesQuotedPrompt() {
        val comment = RichMessageDirective.parseLine("""::code-comment{title="Fix" body="Check the boundary" file="src/A.kt" start=\"12\"}""".replace("\\\"", "\"")) as RichMessageDirective.CodeComment
        assertEquals(12, comment.startLine)
        val follow = RichMessageDirective.parseLine("""- :codex-followup[Try again]{prompt="Use \"small\" fixtures"}""") as RichMessageDirective.FollowUp
        assertEquals("Use \"small\" fixtures", follow.prompt)
    }
    @Test fun ignoresCodeAndRejectsMalformedAttributes() {
        val directive = """::code-comment{title="Fix" body="body" file="x"}"""
        assertTrue(RichMessageDirective.extract("```text\n$directive\n```\n").isEmpty())
        assertNull(RichMessageDirective.parseLine("""::code-comment{title="a" title="b" body="c" file="d"}"""))
        assertNull(RichMessageDirective.parseLine("""::code-comment{title="a" body="c" file="d" start="0"}"""))
        assertEquals(1, RichMessageDirective.extract("$directive\n```\n$directive\n```").size)
    }
}
