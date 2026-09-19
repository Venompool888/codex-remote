package app.codexremote.android

import org.json.JSONArray
import org.json.JSONObject

data class ApprovalDecisionChoice(
    val id: String,
    val label: String,
    val consequence: String,
)

/** Builds only replies that are subsets of, or byte-for-byte equivalents of, host offers. */
class ApprovalDecisionModel private constructor(
    val choices: List<ApprovalDecisionChoice>,
    private val results: Map<String, String>,
) {
    fun result(choiceId: String): JSONObject = results[choiceId]
        ?.let(::JSONObject)
        ?: throw IllegalArgumentException("Approval choice is no longer available")

    companion object {
        fun from(method: String, params: JSONObject): ApprovalDecisionModel = when (method) {
            "item/commandExecution/requestApproval" -> command(params)
            "item/fileChange/requestApproval" -> fileChange(params)
            "item/permissions/requestApproval" -> permissions(params)
            else -> create(emptyList())
        }

        private fun command(params: JSONObject): ApprovalDecisionModel {
            val available = params.optJSONArray("availableDecisions")
            if (!params.has("availableDecisions") || params.isNull("availableDecisions")) {
                return create(listOf(
                    decision("accept", "Allow once", "Runs only this command once", "accept"),
                    decision("decline", "Deny", "The command will not run", "decline"),
                    decision("cancel", "Cancel task", "Stops the current task", "cancel"),
                ))
            }
            if (available == null) return create(emptyList())
            val proposedExec = params.optJSONArray("proposedExecpolicyAmendment")
            val proposedNetwork = params.optJSONArray("proposedNetworkPolicyAmendments")
            val result = mutableListOf<WireChoice>()
            for (index in 0 until available.length()) {
                when (val offered = available.opt(index)) {
                    is String -> when (offered) {
                        "accept" -> result += decision("accept", "Allow once", "Runs only this command once", offered)
                        "acceptForSession" -> result += decision(
                            "acceptForSession", "Allow for session", "Allows matching requests until this task session ends", offered,
                        )
                        "decline" -> result += decision("decline", "Deny", "The command will not run", offered)
                        "cancel" -> result += decision("cancel", "Cancel task", "Stops the current task", offered)
                    }
                    is JSONObject -> when {
                        offered.has("acceptWithExecpolicyAmendment") -> {
                            val amendment = offered.optJSONObject("acceptWithExecpolicyAmendment")
                                ?.optJSONArray("execpolicy_amendment")
                            if (amendment != null && proposedExec != null && jsonEqual(amendment, proposedExec)) {
                                result += wireChoice(
                                    "execpolicy-$index",
                                    "Allow matching commands",
                                    "Adds the exact command rule proposed by the host; matching future commands may run without another prompt",
                                    JSONObject().put("decision", JSONObject(offered.toString())),
                                )
                            }
                        }
                        offered.has("applyNetworkPolicyAmendment") -> {
                            val amendment = offered.optJSONObject("applyNetworkPolicyAmendment")
                                ?.optJSONObject("network_policy_amendment")
                            val matched = amendment != null && proposedNetwork != null &&
                                (0 until proposedNetwork.length()).any { jsonEqual(amendment, proposedNetwork.opt(it)) }
                            if (matched) result += wireChoice(
                                "network-policy-$index",
                                "Apply network rule",
                                "Applies the exact host and action proposed by the host for future requests",
                                JSONObject().put("decision", JSONObject(offered.toString())),
                            )
                        }
                    }
                }
            }
            return create(result.distinctBy { it.choice.id })
        }

        private fun fileChange(params: JSONObject): ApprovalDecisionModel {
            val reviewed = params.optJSONObject("fileChangeReview")?.optString("status") == "available"
            val grantRoot = params.opt("grantRoot")?.takeUnless { it == JSONObject.NULL } as? String
            val available = params.optJSONArray("availableDecisions")
            fun offered(id: String) = available == null || (0 until available.length()).any { available.opt(it) == id }
            return create(buildList {
                if (reviewed && grantRoot.isNullOrBlank() && offered("accept"))
                    add(decision("accept", "Allow changes", "Applies these file changes once", "accept"))
                if (offered("decline")) add(decision("decline", "Deny", "The file changes will not be applied", "decline"))
                if (offered("cancel")) add(decision("cancel", "Cancel task", "Stops the current task", "cancel"))
            })
        }

        private fun permissions(params: JSONObject): ApprovalDecisionModel {
            val requested = params.optJSONObject("permissions") ?: JSONObject()
            val result = mutableListOf(
                wireChoice(
                    "deny",
                    "Deny",
                    "No additional permissions are granted",
                    JSONObject().put("permissions", JSONObject()).put("scope", "turn"),
                ),
            )
            val network = requested.optJSONObject("network")
            val fileSystem = requested.optJSONObject("fileSystem")
            if (network != null) result.add(0, permissionChoice("network", "Allow network only", "Grants only the requested network access for this turn", "network", network))
            if (fileSystem != null) result.add(0, permissionChoice("files", "Allow files only", "Grants only the requested file access for this turn", "fileSystem", fileSystem))
            if (network != null && fileSystem != null) result.add(0, wireChoice(
                "all", "Allow requested permissions", "Grants all listed permissions for this turn",
                JSONObject().put("permissions", JSONObject(requested.toString())).put("scope", "turn"),
            ))
            return create(result)
        }

        private fun decision(id: String, label: String, consequence: String, decision: String) =
            wireChoice(id, label, consequence, JSONObject().put("decision", decision))

        private fun permissionChoice(
            id: String,
            label: String,
            consequence: String,
            key: String,
            value: JSONObject,
        ) = wireChoice(
            id,
            label,
            consequence,
            JSONObject().put("permissions", JSONObject().put(key, JSONObject(value.toString()))).put("scope", "turn"),
        )

        private data class WireChoice(val choice: ApprovalDecisionChoice, val result: String)

        private fun wireChoice(id: String, label: String, consequence: String, result: JSONObject) =
            WireChoice(ApprovalDecisionChoice(id, label, consequence), result.toString())

        private fun create(values: List<WireChoice>): ApprovalDecisionModel = ApprovalDecisionModel(
            values.map(WireChoice::choice),
            values.associate { it.choice.id to it.result },
        )

        private fun jsonEqual(left: Any?, right: Any?): Boolean = when {
            left === right -> true
            left is JSONObject && right is JSONObject -> {
                val keys = left.keys().asSequence().toSet()
                keys == right.keys().asSequence().toSet() && keys.all { jsonEqual(left.opt(it), right.opt(it)) }
            }
            left is JSONArray && right is JSONArray -> left.length() == right.length() &&
                (0 until left.length()).all { jsonEqual(left.opt(it), right.opt(it)) }
            else -> left == right
        }
    }
}
