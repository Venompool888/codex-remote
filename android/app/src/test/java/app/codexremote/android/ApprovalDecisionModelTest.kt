package app.codexremote.android

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class ApprovalDecisionModelTest {
    @Test fun persistentCommandChoicesRequireExactHostOffersAndProposals() {
        val exec = JSONArray().put("git").put("status")
        val network = JSONObject().put("host", "example.test").put("action", "allow")
        val params = JSONObject()
            .put("proposedExecpolicyAmendment", exec)
            .put("proposedNetworkPolicyAmendments", JSONArray().put(network))
            .put("availableDecisions", JSONArray()
                .put("accept")
                .put("acceptForSession")
                .put(JSONObject().put("acceptWithExecpolicyAmendment", JSONObject().put("execpolicy_amendment", exec)))
                .put(JSONObject().put("applyNetworkPolicyAmendment", JSONObject().put("network_policy_amendment", network)))
                .put("decline"))

        val model = ApprovalDecisionModel.from("item/commandExecution/requestApproval", params)

        assertEquals(listOf("accept", "acceptForSession", "execpolicy-2", "network-policy-3", "decline"), model.choices.map { it.id })
        assertEquals("acceptForSession", model.result("acceptForSession").getString("decision"))
        assertTrue(model.choices.all { it.consequence.isNotBlank() })
    }

    @Test fun alteredOrUnproposedAmendmentsAreNotExposed() {
        val params = JSONObject()
            .put("proposedExecpolicyAmendment", JSONArray().put("git"))
            .put("availableDecisions", JSONArray().put(JSONObject()
                .put("acceptWithExecpolicyAmendment", JSONObject().put("execpolicy_amendment", JSONArray().put("rm")))))
        assertTrue(ApprovalDecisionModel.from("item/commandExecution/requestApproval", params).choices.isEmpty())
    }

    @Test fun permissionsOfferOnlyExactTopLevelSubsetsForThisTurn() {
        val permissions = JSONObject()
            .put("network", JSONObject().put("enabled", true))
            .put("fileSystem", JSONObject().put("read", JSONArray().put("/repo")).put("write", JSONArray()))
        val model = ApprovalDecisionModel.from(
            "item/permissions/requestApproval",
            JSONObject().put("permissions", permissions),
        )

        assertEquals(listOf("all", "files", "network", "deny"), model.choices.map { it.id })
        val networkOnly = model.result("network")
        assertEquals("turn", networkOnly.getString("scope"))
        assertEquals(setOf("network"), networkOnly.getJSONObject("permissions").keys().asSequence().toSet())
        assertFalse(networkOnly.toString().contains("fileSystem"))
    }
}
