package app.codexremote.android

import org.json.JSONObject

/** A client instance keeps its wire format across reconnects, including queued
 * approvals and idempotent writes. Upgrade on a fresh client, never mid-replay. */
internal class WorkspaceRoutingNegotiation(private val enabled: Boolean) {
    private var pinned: Boolean? = null
    private var requested = false

    fun request(hello: JSONObject): Boolean {
        val capabilities = hello.optJSONObject("capabilities")
        val methods = capabilities?.optJSONArray("rpcMethods")
        val supported = capabilities?.optJSONObject("routing")?.opt("opaqueWorkspaceReferences") == true &&
            (0 until (methods?.length() ?: 0)).any { methods?.optString(it) == "host/workspace/migrate" }
        requested = pinned ?: (enabled && supported)
        check(!requested || supported) { "Host no longer supports private workspace routing; reconnect after updating the host" }
        return requested
    }

    fun acknowledge(message: JSONObject): Boolean {
        val value = message.optJSONObject("features")?.opt("opaqueWorkspaceRouting")
        check(value == null || value is Boolean) { "Invalid workspace routing acknowledgement" }
        val accepted = value == true
        check(accepted == requested) { "Host did not confirm the requested workspace routing mode" }
        pinned = accepted
        return accepted
    }

    fun legacy() {
        check(pinned != true) { "Private workspace routing cannot downgrade during reconnect" }
        pinned = false
    }
}
