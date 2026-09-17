package app.codexremote.android.presentation.conversation

import org.junit.Assert.assertEquals
import org.junit.Test

class UserMessageTextTest {
    @Test fun desktopLeadingSpaceIsDisplayedAsWhitespace() {
        assertEquals(" 对的，这次成功做到了。", UserMessageText.display("&#x20;对的，这次成功做到了。"))
        assertEquals("\n  内容", UserMessageText.display("\n&#32;&#X0020;内容"))
    }

    @Test fun codeAndInlineEntityExamplesRemainLiteral() {
        for (text in listOf("`&#x20;`", "```html\n&#x20;\n```", "字符引用是 &#x20;", "&amp;#x20;", "&#x41;abc", "ordinary text"))
            assertEquals(text, UserMessageText.display(text))
    }
}
