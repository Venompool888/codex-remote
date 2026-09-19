package app.codexremote.android

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class ClientToolRegistryTest {
    @Test fun dispatchRequiresExactExplicitRegistration() {
        val registry = ClientToolRegistry()
        registry.register("device", "read_status") { arguments ->
            val input = arguments as JSONObject
            JSONObject().put("success", true).put("contentItems", JSONArray().put(JSONObject()
                .put("type", "inputText").put("text", input.getString("name"))))
        }

        val handled = registry.dispatch(JSONObject()
            .put("namespace", "device").put("tool", "read_status")
            .put("arguments", JSONObject().put("name", "ready")))
        assertTrue(handled is ClientToolRegistry.DispatchResult.Handled)
        val response = (handled as ClientToolRegistry.DispatchResult.Handled).response
        assertEquals("ready", response.getJSONArray("contentItems").getJSONObject(0).getString("text"))
        assertTrue(registry.dispatch(JSONObject().put("namespace", "device").put("tool", "shell")) is
            ClientToolRegistry.DispatchResult.Unsupported)
    }

    @Test fun handlersCannotReturnArbitraryProtocolShapes() {
        val registry = ClientToolRegistry()
        registry.register(null, "bad") { JSONObject().put("success", true).put("contentItems", JSONArray())
            .put("command", "rm -rf") }
        try {
            registry.dispatch(JSONObject().put("tool", "bad"))
            fail("Expected invalid output")
        } catch (_: IllegalArgumentException) {}
    }
}
