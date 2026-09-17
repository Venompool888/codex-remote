@file:Suppress("DEPRECATION")
package app.codexremote.android

import android.test.InstrumentationTestCase
import app.codexremote.android.presentation.interactions.InteractionsController
import app.codexremote.android.ui.interactions.InteractionDialogCompose
import org.json.JSONObject

class ElicitationSelectionDeviceTest : InstrumentationTestCase() {
    fun testTitlesAreVisibleAndOnlyProtocolValuesAreSubmitted() {
        val replies = mutableListOf<JSONObject>()
        val controller = InteractionsController(onSubmitReply = { _, reply, _ -> replies += reply })
        val params = JSONObject("""{"mode":"form","serverName":"QA","message":"Choose how to prepare the report.","requestedSchema":{"type":"object","required":["format","delivery"],"properties":{"format":{"type":"string","title":"Report format","oneOf":[{"const":"internal_pdf_v2","title":"PDF document"},{"const":"internal_text_v1","title":"Plain text"}]},"delivery":{"type":"string","title":"Delivery","enum":["local","remote"],"enumNames":["Keep on this host","Upload to service"]},"optional":{"type":"string","title":"Optional choice","enum":["extra"]}}}}""")
        instrumentation.runOnMainSync { controller.showInteraction("request", "mcpServer/elicitation/request", params) }
        val activity = instrumentation.composeFixture { InteractionDialogCompose(controller) }
        try {
            instrumentation.awaitUiText("PDF document")
            instrumentation.runOnMainSync { controller.submit(); assertTrue(replies.isEmpty()) }
            instrumentation.clickUi("PDF document")
            instrumentation.runOnMainSync { controller.submit(); assertTrue(replies.isEmpty()) }
            instrumentation.clickUi("Keep on this host")
            instrumentation.runOnMainSync {
                controller.submit()
                assertEquals(1, replies.size)
                val content = replies.single().getJSONObject("content")
                assertEquals("internal_pdf_v2", content.getString("format")); assertEquals("local", content.getString("delivery"))
                assertFalse(content.has("optional")); assertTrue(controller.uiState.value!!.isBusy)
            }
            instrumentation.awaitUi("choices disabled") { it.uiDescendants().filter { it.isCheckable }.all { !it.isEnabled } }
        } finally { instrumentation.runOnMainSync { activity.finish() } }
    }
}
