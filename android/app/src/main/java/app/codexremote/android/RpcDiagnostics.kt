package app.codexremote.android

import org.json.JSONArray
import org.json.JSONObject

/** UI-thread request correlation, with no changes to RPC execution or retry behavior. */
class RpcDiagnostics(private val write: (event: String, server: String, detail: String, secrets: Collection<String>) -> Unit) {
    private data class Pending(val server: String, val method: String, val context: String,
        val started: Long, val secrets: List<String>)
    private val pending = mutableMapOf<String, Pending>()

    fun begin(id: String, server: String, method: String, params: JSONObject, token: String?) {
        val secrets = mutableListOf<String>()
        token?.takeIf(String::isNotBlank)?.let(secrets::add)
        fun collect(value: Any?, key: String = "") {
            when (value) {
                is JSONObject -> value.keys().forEach { collect(value.opt(it), it) }
                is JSONArray -> (0 until value.length()).forEach { collect(value.opt(it), key) }
                is String -> if (key.lowercase() in setOf("text", "prompt", "instructions", "developermessage", "developerinstructions", "baseinstructions", "token", "password", "code", "data", "content")) {
                    secrets += value
                    secrets += value.lines().filter { it.isNotBlank() }
                    secrets += JSONObject.quote(value).removeSurrounding("\"")
                }
            }
        }
        collect(params)
        val context = listOf("cwd", "threadId").mapNotNull { key ->
            (params.opt(key) as? String)?.let { "$key=$it" }
        }.joinToString(" ")
        pending[id] = Pending(server, method, context, System.nanoTime(), secrets)
        write("rpc.request", server, "id=$id method=$method $context", secrets)
    }

    fun finish(id: String, server: String, outcome: String, error: String = "", code: String = "", secrets: Collection<String> = emptyList()) {
        val found = pending[id]?.takeIf { it.server == server }
        if (found != null) pending.remove(id)
        val detail = "id=$id method=${found?.method ?: "unknown"} outcome=$outcome" +
            (found?.let { " durationMs=${(System.nanoTime() - it.started) / 1_000_000} ${it.context}" } ?: "") +
            (if (code.isBlank()) "" else " code=$code") + (if (error.isBlank()) "" else "\n$error")
        write("rpc.$outcome", server, detail, found?.secrets.orEmpty() + secrets)
    }
}
