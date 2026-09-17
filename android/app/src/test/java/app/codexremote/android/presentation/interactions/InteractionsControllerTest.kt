package app.codexremote.android.presentation.interactions

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class InteractionsControllerTest {
    private val method = "item/tool/requestUserInput"
    private fun params(question: String = "Answer") = JSONObject().put("questions", org.json.JSONArray()
        .put(JSONObject().put("id", "answer").put("question", question)))

    @Test fun submittingWaitsForAcknowledgementAndBlocksDuplicateActions() {
        val replies = mutableListOf<JSONObject>()
        lateinit var acknowledge: (Boolean) -> Unit
        val controller = InteractionsController(onSubmitReply = { _, reply, done -> replies.add(reply); acknowledge = done },
            onCancelReply = { _, _, _ -> error("Cannot cancel while awaiting acknowledgement") })
        controller.showInteraction("request-a", method, params())
        controller.setText("answer", "draft")
        controller.submit(); controller.submit(); controller.cancel()
        assertEquals(1, replies.size)
        assertTrue(controller.uiState.value!!.isSubmitting)
        acknowledge(true)
        assertNull(controller.uiState.value)
    }

    @Test fun failedAcknowledgementKeepsDraftForRetry() {
        lateinit var acknowledge: (Boolean) -> Unit
        val controller = InteractionsController(onSubmitReply = { _, _, done -> acknowledge = done })
        controller.showInteraction("request-a", method, params())
        controller.setText("answer", "keep this")
        controller.submit(); acknowledge(false)
        assertFalse(controller.uiState.value!!.isBusy)
        assertEquals("keep this", controller.uiState.value!!.form!!.text("answer"))
    }

    @Test fun cancellationAlsoWaitsForAcknowledgement() {
        lateinit var acknowledge: (Boolean) -> Unit
        var reply: JSONObject? = null
        val controller = InteractionsController(onCancelReply = { _, value, done -> reply = value; acknowledge = done })
        controller.showInteraction("request-a", method, params())
        controller.cancel()
        assertEquals(0, reply!!.getJSONObject("answers").length())
        assertTrue(controller.uiState.value!!.isBusy)
        acknowledge(true)
        assertNull(controller.uiState.value)
    }

    @Test fun oldRejectionCannotUnlockAnotherPendingRequest() {
        val acknowledgements = mutableListOf<(Boolean) -> Unit>()
        val controller = InteractionsController(onSubmitReply = { _, _, done -> acknowledgements.add(done) })
        controller.showInteraction("request-a", method, params())
        controller.setText("answer", "A"); controller.submit()
        controller.showInteraction("request-b", method, params())
        controller.setText("answer", "B"); controller.submit()
        acknowledgements.first()(false)
        assertEquals("request-b", controller.uiState.value!!.activeKey)
        assertTrue("Old callback cannot re-enable the new request", controller.uiState.value!!.isBusy)
    }

    @Test fun oldSuccessCannotDismissReplacedFormWithReusedKey() {
        lateinit var acknowledge: (Boolean) -> Unit
        val controller = InteractionsController(onSubmitReply = { _, _, done -> acknowledge = done })
        controller.showInteraction("same-key", method, params("First question"))
        controller.setText("answer", "A"); controller.submit()
        controller.showInteraction("same-key", method, params("Different question"))
        controller.setText("answer", "B")
        acknowledge(true)
        assertNotNull("Acknowledgement belongs to the original form instance", controller.uiState.value)
        assertEquals("B", controller.uiState.value!!.form!!.text("answer"))
    }
}
