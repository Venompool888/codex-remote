@file:Suppress("DEPRECATION")
package app.codexremote.android
import android.test.InstrumentationTestCase
import app.codexremote.android.presentation.interactions.InteractionsController
import app.codexremote.android.ui.interactions.InteractionDialogCompose
import org.json.JSONObject

/** Real Compose fields; replies remain local to this fixture. */
class InteractionDialogDeviceTest : InstrumentationTestCase() {
    fun testMultipleQuestionsKeepValidationAndStructuredAnswers() {
        val submissions = mutableListOf<JSONObject>()
        val controller = InteractionsController(onSubmitReply = { _, reply, _ -> submissions += reply })
        instrumentation.runOnMainSync { controller.showInteraction("request", "item/tool/requestUserInput", JSONObject("""{"questions":[{"id":"color","header":"Color","question":"Choose the accent color.","options":[{"label":"Blue"},{"label":"Green"}]},{"id":"notes","header":"Notes","question":"Add a short explanation."}]}""")) }
        val activity = instrumentation.composeFixture { InteractionDialogCompose(controller) }
        try {
            instrumentation.awaitUiText("Blue")
            instrumentation.runOnMainSync { controller.submit(); assertTrue(submissions.isEmpty()) }
            instrumentation.clickUi("Blue")
            // Text goes through the same presentation field API used by Compose's onValueChange.
            instrumentation.runOnMainSync { controller.setText("notes", "Readable on Pixel"); controller.submit()
                assertEquals(1, submissions.size)
                val answers = submissions.single().getJSONObject("answers")
                assertEquals("Blue", answers.getJSONObject("color").getJSONArray("answers").getString(0))
                assertEquals("Readable on Pixel", answers.getJSONObject("notes").getJSONArray("answers").getString(0))
                assertTrue(controller.uiState.value!!.isBusy); controller.rejected("The host needs a revised answer.") }
            instrumentation.awaitUiText("The host needs a revised answer.")
            instrumentation.runOnMainSync { assertFalse(controller.uiState.value!!.isBusy) }
        } finally { instrumentation.runOnMainSync { activity.finish() } }
    }
}
