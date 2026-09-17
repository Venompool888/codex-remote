package app.codexremote.android

import org.junit.Assert.*
import org.junit.Test

class UserCodePresentationTest {
    @Test fun completeBlocksUseDisplayRangesWithoutChangingSurroundingProse() {
        val source = "说明\n```python\nprint(\"你好\")\n```\n结束"
        val display = UserCodePresentation.format(source)
        assertEquals("说明\npython\nprint(\"你好\")\n结束", display.text)
        assertEquals("python\nprint(\"你好\")", display.text.substring(display.codeRanges.single()))
    }
    @Test fun longerFencesProtectLiteralShorterFencesAndMultipleBlocks() {
        val display = UserCodePresentation.format("````\n```\n````\ntext\n```\nx\n```")
        assertEquals("```\ntext\nx", display.text)
        assertEquals(listOf("```", "x"), display.codeRanges.map { display.text.substring(it) })
    }
    @Test fun incompleteOrInlineFencesRemainLiteralAndCrLfIsSupported() {
        for (source in listOf("hello ```world```", "```py\nx", "    ```py\nx\n```"))
            assertEquals(source, UserCodePresentation.format(source).text)
        assertEquals("json\n{}", UserCodePresentation.format("```json\r\n{}\r\n```").text)
    }
}
