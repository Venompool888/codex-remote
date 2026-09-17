@file:Suppress("DEPRECATION")
package app.codexremote.android

import android.test.InstrumentationTestCase
import app.codexremote.android.presentation.interactions.InteractionsController
import app.codexremote.android.ui.interactions.InteractionDialogCompose
import org.json.JSONObject

class ElicitationMultipleSelectionDeviceTest : InstrumentationTestCase() {
    fun testRequiredArrayAllowsEmptyAndUsesDisplayNamesWithoutChangingValues() {
        val replies = mutableListOf<JSONObject>()
        val controller = InteractionsController(onSubmitReply = { _, reply, _ -> replies += reply })
        val params = JSONObject("""{"mode":"form","serverName":"QA","message":"Choose optional report sections.","requestedSchema":{"type":"object","required":["sections","formats"],"properties":{"sections":{"type":"array","items":{"type":"string","enum":["internal_summary","internal_chart"],"enumNames":["Summary","Charts"]}},"formats":{"type":"array","minItems":1,"maxItems":1,"items":{"anyOf":[{"const":"pdf_v2","title":"PDF document"},{"const":"txt_v1","title":"Plain text"}]}}}}}""")
        instrumentation.runOnMainSync { controller.showInteraction("request", "mcpServer/elicitation/request", params) }
        val activity = instrumentation.composeFixture { InteractionDialogCompose(controller) }
        try {
            instrumentation.awaitUiText("Summary")
            instrumentation.runOnMainSync { controller.submit(); assertTrue(replies.isEmpty()) }
            instrumentation.clickUi("PDF document"); instrumentation.clickUi("Plain text")
            instrumentation.runOnMainSync { controller.submit(); assertTrue(replies.isEmpty()) }
            instrumentation.clickUi("Plain text")
            instrumentation.runOnMainSync { controller.submit(); assertEquals(1, replies.size)
                assertEquals(0, replies[0].getJSONObject("content").getJSONArray("sections").length())
                assertEquals("pdf_v2", replies[0].getJSONObject("content").getJSONArray("formats").getString(0))
                assertTrue(controller.uiState.value!!.isBusy); controller.rejected("Retry the test response") }
            instrumentation.clickUi("Summary")
            instrumentation.runOnMainSync { controller.submit(); assertEquals("internal_summary", replies[1].getJSONObject("content").getJSONArray("sections").getString(0)) }
        } finally { instrumentation.runOnMainSync { activity.finish() } }
    }
}
