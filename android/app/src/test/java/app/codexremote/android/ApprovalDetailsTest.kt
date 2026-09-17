package app.codexremote.android

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class ApprovalDetailsTest {
    @Test fun replacementRetiresSilentlyButTimeoutAndLegacyExpiryRemainVisible() {
        val ack = JSONObject().put("status", "expired")
        assertTrue(shouldNotifyInteractionExpiry(ack))
        assertTrue(shouldNotifyInteractionExpiry(ack.put("reason", "timeout")))
        assertFalse(shouldNotifyInteractionExpiry(ack.put("reason", "superseded")))
        assertFalse(shouldNotifyInteractionExpiry(JSONObject().put("status", "answered")))
    }
    @Test fun permissionLabelsKeepSameNamesDistinctWithoutMutatingTheReplyObject() {
        val a = "remote-path://" + "a".repeat(64)
        val b = "remote-path://" + "b".repeat(64)
        val params = JSONObject().put("permissions", JSONObject().put("fileSystem", JSONObject().put(a, true).put(b, false)))
            .put("permissionsPathNames", JSONObject().put(a, "report").put(b, "report"))
        val original = params.toString()
        val display = ApprovalDetails.format("item/permissions/requestApproval", params)
        assertTrue(display.contains("report (location 1)"))
        assertTrue(display.contains("report (location 2)"))
        assertFalse(display.contains("remote-path"))
        assertEquals(original, params.toString())
        params.getJSONObject("permissionsPathNames").put(a, "/private/host")
        assertFalse(ApprovalDetails.format("item/permissions/requestApproval", params).contains("/private/host"))
    }
    @Test fun additionalAuthorizationScopeIsNotHiddenBehindTheCommand() {
        val params = JSONObject().put("command", "printf ok")
            .put("additionalPermissions", JSONObject().put("network", JSONObject().put("enabled", true)))
            .put("networkApprovalContext", JSONObject().put("host", "qa.example").put("protocol", "https"))
        val detail = ApprovalDetails.format("item/commandExecution/requestApproval", params)
        assertTrue(detail.contains("Additional permissions requested:"))
        assertTrue(detail.contains("Network access requested:"))
        assertTrue(detail.contains("qa.example"))
        assertTrue(detail.contains("printf ok"))
        val file = ApprovalDetails.format("item/fileChange/requestApproval",
            JSONObject().put("reason", "Write report").put("grantRoot", "/workspace/export"))
        assertTrue(file.contains("/workspace/export"))
        assertTrue(file.contains("rest of the session"))
    }
    @Test fun filePatchIncludesNameChangeKindAndFullContent() {
        val review = JSONObject().put("status", "available").put("changes", org.json.JSONArray().put(
            JSONObject().put("path", "src/report.txt").put("kind", "update").put("diff", "-old\n+new")))
        val detail = ApprovalDetails.format("item/fileChange/requestApproval", JSONObject().put("fileChangeReview", review))
        assertTrue(detail.contains("Update: src/report.txt"))
        assertTrue(detail.contains("-old\n+new"))
    }
    @Test fun commandReasonAndActualCommandRemainVisibleTogether() {
        val command = "printf 'first\\nsecond'"
        val result = ApprovalDetails.format("item/commandExecution/requestApproval",
            JSONObject().put("reason", "Print the QA marker only").put("command", command))
        assertEquals("Print the QA marker only\n\n$command", result)
    }
    @Test fun optionalNullReasonIsNotPresentedAsLiteralNull() {
        assertEquals("printf ok", ApprovalDetails.format("item/commandExecution/requestApproval",
            JSONObject().put("reason", JSONObject.NULL).put("command", "printf ok")))
        assertTrue(ApprovalDetails.format("item/fileChange/requestApproval", JSONObject().put("reason", JSONObject.NULL)).contains("File patch details are unavailable"))
    }
    @Test fun absentWriteRootDoesNotSuggestSessionAuthorization() {
        for (root in listOf(JSONObject.NULL, 42, false)) {
            val params = JSONObject().put("grantRoot", root)
            val detail = ApprovalDetails.format("item/fileChange/requestApproval", params)
            assertFalse(detail.contains("Requested write root"))
            assertFalse(detail.contains("rest of the session"))
        }
        assertEquals("", JSONObject().put("movePath", JSONObject.NULL).displayPath("movePath"))
    }
}
