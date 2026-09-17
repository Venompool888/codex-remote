package app.codexremote.android

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class WorkspaceRoutingNegotiationTest {
    private fun offer() = JSONObject().put("capabilities", JSONObject()
        .put("routing", JSONObject().put("opaqueWorkspaceReferences", true))
        .put("rpcMethods", JSONArray().put("host/workspace/migrate")))
    private fun ack(value: Any) = JSONObject().put("features", JSONObject().put("opaqueWorkspaceRouting", value))

    @Test fun pinsLegacyModeSoOutstandingMessagesKeepTheirFormat() {
        val state = WorkspaceRoutingNegotiation(true)
        assertFalse(state.request(JSONObject()))
        assertFalse(state.acknowledge(JSONObject()))
        assertFalse(state.request(offer()))
        assertFalse(state.acknowledge(ack(false)))
    }

    @Test fun optedInModeRequiresExactConfirmationAndCannotSilentlyDowngrade() {
        val state = WorkspaceRoutingNegotiation(true)
        assertTrue(state.request(offer()))
        for (value in listOf(false, "true", 1)) {
            try { state.acknowledge(ack(value)); fail("must reject") } catch (_: IllegalStateException) { }
        }
        assertTrue(state.acknowledge(ack(true)))
        assertTrue(state.request(offer()))
        try { state.request(JSONObject()); fail("must reject downgrade") } catch (_: IllegalStateException) { }
        try { state.legacy(); fail("must reject v1 downgrade") } catch (_: IllegalStateException) { }
    }

    @Test fun defaultDisabledAndLegacyFallbackRemainAvailable() {
        assertFalse(WorkspaceRoutingNegotiation(false).request(offer()))
        val state = WorkspaceRoutingNegotiation(true)
        state.legacy()
        assertFalse(state.request(offer()))
        val incomplete = offer()
        incomplete.getJSONObject("capabilities").remove("rpcMethods")
        assertFalse(WorkspaceRoutingNegotiation(true).request(incomplete))
    }
}
