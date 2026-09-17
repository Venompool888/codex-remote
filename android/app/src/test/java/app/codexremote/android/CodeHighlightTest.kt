package app.codexremote.android

import org.junit.Assert.*
import org.junit.Test

class CodeHighlightTest {
    @Test fun pythonIdentifiersAndOperatorsKeepDistinctUnchangedRanges() {
        val code = "def remind(count=3):\n    message = \"a + b\"\n    print(message, count + 1) # x = y"
        val tokens = CodeHighlight.tokens(code, "python")
        fun parts(kind: CodeHighlight.Kind) = tokens.filter { it.kind == kind }.map { code.substring(it.start, it.end) }
        assertEquals(listOf("def"), parts(CodeHighlight.Kind.KEYWORD))
        assertEquals(listOf("remind", "print"), parts(CodeHighlight.Kind.CALL))
        assertEquals(listOf("count", "message", "message", "count"), parts(CodeHighlight.Kind.IDENTIFIER))
        assertEquals(listOf("=", "=", "+"), parts(CodeHighlight.Kind.OPERATOR))
        assertEquals(listOf("\"a + b\""), parts(CodeHighlight.Kind.STRING))
        assertEquals(listOf("# x = y"), parts(CodeHighlight.Kind.COMMENT))
        assertTrue(tokens.zipWithNext().all { (a, b) -> a.end <= b.start })
    }
    @Test fun quotedCommentMarkersAndNumbersRemainStrings() {
        val code = "print(\"https://x.test/#123\") # comment 42"
        val tokens = CodeHighlight.tokens(code, "python")
        assertEquals(listOf(CodeHighlight.Kind.CALL, CodeHighlight.Kind.STRING, CodeHighlight.Kind.COMMENT), tokens.map { it.kind })
        assertEquals("\"https://x.test/#123\"", code.substring(tokens[1].start, tokens[1].end))
    }
    @Test fun incompleteAndEscapedStringsKeepValidOffsets() {
        val code = "const x = \"你好\\\" still open"
        val tokens = CodeHighlight.tokens(code, "typescript")
        assertEquals(CodeHighlight.Kind.STRING, tokens.last().kind)
        assertEquals(code.length, tokens.last().end)
        assertTrue(tokens.zipWithNext().all { (a, b) -> a.end <= b.start })
    }
    @Test fun multilineCommentsAndTripleStringsAreNotRetokenized() {
        assertEquals(listOf(CodeHighlight.Kind.COMMENT), CodeHighlight.tokens("/* if 12\n print() */", "js").map { it.kind })
        assertEquals(listOf(CodeHighlight.Kind.STRING), CodeHighlight.tokens("\"\"\"# text\nreturn 42\"\"\"", "py").map { it.kind })
    }
    @Test fun jsonAndUnknownLanguageRemainConservative() {
        val code = "{\"ok\": true, \"count\": 42}"
        assertEquals(listOf(CodeHighlight.Kind.STRING, CodeHighlight.Kind.KEYWORD, CodeHighlight.Kind.STRING, CodeHighlight.Kind.NUMBER), CodeHighlight.tokens(code, "json").map { it.kind })
        assertTrue(CodeHighlight.tokens(code, "text").isEmpty())
        assertTrue(CodeHighlight.tokens("x".repeat(100_001), "python").isEmpty())
    }
}
