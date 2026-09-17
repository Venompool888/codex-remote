package app.codexremote.android

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class ApprovalChoicesTest {
    @Test fun legacyDecisionsRemainCompatible() {
        assertEquals(ApprovalChoices(true, "decline"), ApprovalChoices.from(JSONObject()))
        assertEquals(ApprovalChoices(true, "decline"), ApprovalChoices.from(JSONObject("{\"availableDecisions\":null}")))
    }
    @Test fun restrictedChoicesNeverBecomePersistentGrants() {
        assertEquals(ApprovalChoices(false, "cancel"), ApprovalChoices.from(JSONObject("{\"availableDecisions\":[\"acceptForSession\",\"cancel\"]}")))
        assertEquals(ApprovalChoices(false, null), ApprovalChoices.from(JSONObject("{\"availableDecisions\":[{\"acceptWithExecpolicyAmendment\":{}}]}")))
        assertEquals(ApprovalChoices(false, null), ApprovalChoices.from(JSONObject("{\"availableDecisions\":[]}")))
        assertEquals(ApprovalChoices(true, "decline"), ApprovalChoices.from(JSONObject("{\"availableDecisions\":[\"accept\",\"decline\",\"cancel\"]}")))
    }
}
