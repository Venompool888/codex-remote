package app.codexremote.android

import org.json.JSONArray
import org.json.JSONObject

class ClientToolRegistry {
    fun interface Handler { fun invoke(arguments: Any?): JSONObject }

    sealed interface DispatchResult {
        data class Handled(val response: JSONObject) : DispatchResult
        data class Unsupported(val message: String = "Unsupported client tool; handle it on the host") : DispatchResult
    }

    private val handlers = linkedMapOf<Pair<String?, String>, Handler>()

    fun register(namespace: String?, tool: String, handler: Handler) {
        require(tool.isNotBlank() && tool.length <= 128 && (namespace == null || namespace.length <= 128))
        val key = namespace to tool
        require(key !in handlers) { "Client tool already registered" }
        handlers[key] = handler
    }

    fun dispatch(params: JSONObject): DispatchResult {
        val tool = params.optString("tool")
        val namespace = params.optString("namespace").takeUnless { it.isBlank() || it == "null" }
        val handler = handlers[namespace to tool] ?: return DispatchResult.Unsupported()
        return DispatchResult.Handled(validateResponse(handler.invoke(params.opt("arguments"))))
    }

    private fun validateResponse(response: JSONObject): JSONObject {
        require(response.has("success") && response.opt("success") is Boolean)
        require(response.keys().asSequence().toSet() == setOf("success", "contentItems"))
        val content = response.optJSONArray("contentItems") ?: throw IllegalArgumentException("Invalid client tool response")
        for (index in 0 until content.length()) {
            val item = content.optJSONObject(index) ?: throw IllegalArgumentException("Invalid client tool output")
            val keys = item.keys().asSequence().toSet()
            val valid = when (item.optString("type")) {
                "inputText" -> keys == setOf("type", "text") && item.opt("text") is String
                "inputImage" -> keys == setOf("type", "imageUrl") && item.opt("imageUrl") is String
                "inputAudio" -> keys == setOf("type", "audioUrl") && item.opt("audioUrl") is String
                else -> false
            }
            require(valid) { "Invalid client tool output" }
        }
        return JSONObject(response.toString())
    }
}
