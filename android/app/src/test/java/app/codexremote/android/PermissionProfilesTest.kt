package app.codexremote.android

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class PermissionProfilesTest {
    @Test fun menuKeepsUnavailableHostModesVisibleAndDisabled() {
        val options = PermissionProfiles.menu(listOf(
            PermissionProfile(PermissionProfiles.WORKSPACE, "", true),
            PermissionProfile(PermissionProfiles.READ_ONLY, "", false),
            PermissionProfile(PermissionProfiles.FULL_ACCESS, "", true),
            PermissionProfile(PermissionProfiles.AUTO_REVIEW, "", true),
            PermissionProfile(PermissionProfiles.CUSTOM_CONFIG, "", true),
        ))
        assertEquals(listOf("Default permissions", "Auto-review", "Read only", "Full access", "Custom (config.toml)"), options.map { it.label })
        assertEquals(listOf(true, true, false, true, true), options.map { it.enabled })
    }

    @Test fun customProfileIsSelectableOnlyWhenAdvertisedByHost() {
        val options = PermissionProfiles.menu(listOf(PermissionProfile("team-policy", "Team rules", true),
            PermissionProfile(PermissionProfiles.CUSTOM_CONFIG, "", true)))
        assertEquals("team-policy", options.last().id)
        assertEquals("Custom: team-policy", options.last().label)
        assertTrue(options.last().enabled)
    }

    @Test fun olderHostDoesNotAdvertiseLocalPermissionRouting() {
        val hostProfiles = listOf(PermissionProfile(PermissionProfiles.WORKSPACE, "", true))
        val options = PermissionProfiles.menu(hostProfiles)
        assertFalse(options.first { it.id == PermissionProfiles.AUTO_REVIEW }.enabled)
        assertFalse(options.first { it.id == PermissionProfiles.CUSTOM_CONFIG }.enabled)
        assertNull(PermissionProfiles.submissionId(PermissionProfiles.CUSTOM_CONFIG, hostProfiles))
        assertEquals(PermissionProfiles.WORKSPACE, PermissionProfiles.submissionId(PermissionProfiles.WORKSPACE, hostProfiles))
    }

    @Test fun selectionPassesProfilesWithoutOverridingTheirConfiguredApprovalPolicy() {
        val custom = JSONObject()
        PermissionProfiles.applySelection(custom, "team-policy")
        assertEquals("team-policy", custom.getString("permissions"))
        assertFalse(custom.has("approvalPolicy"))
        assertFalse(custom.has("approvalsReviewer"))

        val autoReview = JSONObject()
        PermissionProfiles.applySelection(autoReview, PermissionProfiles.AUTO_REVIEW)
        assertEquals(PermissionProfiles.WORKSPACE, autoReview.getString("permissions"))
        assertEquals("auto_review", autoReview.getString("approvalsReviewer"))
        assertFalse(autoReview.has("approvalPolicy"))
    }
}
