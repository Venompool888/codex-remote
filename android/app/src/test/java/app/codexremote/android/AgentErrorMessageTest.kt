package app.codexremote.android

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class AgentErrorMessageTest {
    @Test fun unwrapsActualProviderErrorInsideTurnMessage() {
        val provider = JSONObject().put("type", "error").put("status", 400)
            .put("error", JSONObject().put("type", "invalid_request_error").put("message", "This model is unavailable."))
        val turnError = JSONObject().put("message", provider.toString()).put("additionalDetails", JSONObject.NULL)
        assertEquals("This model is unavailable.", AgentErrorMessage.text(turnError))
    }
    @Test fun preservesPlainMessagesAndDoesNotExposeUnknownMetadata() {
        assertEquals("Connection lost", AgentErrorMessage.text("Connection lost"))
        assertEquals("{incomplete diagnostic", AgentErrorMessage.text("{incomplete diagnostic"))
        assertFalse(AgentErrorMessage.text(JSONObject().put("internal_path", "private")).contains("private"))
        assertTrue(AgentErrorMessage.text(null).isNotBlank())
    }
}
