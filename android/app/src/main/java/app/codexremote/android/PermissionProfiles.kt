package app.codexremote.android

import org.json.JSONObject

/** IDs and availability come from the host's permissionProfile/list response. */
internal data class PermissionProfile(val id: String, val description: String, val allowed: Boolean)

internal data class PermissionMenuEntry(val id: String, val label: String, val description: String, val enabled: Boolean)

internal object PermissionProfiles {
    const val READ_ONLY = ":read-only"
    const val WORKSPACE = ":workspace"
    const val FULL_ACCESS = ":danger-full-access"
    const val AUTO_REVIEW = "local:auto-review"
    const val CUSTOM_CONFIG = "local:config"

    fun menu(profiles: List<PermissionProfile>): List<PermissionMenuEntry> {
        fun host(id: String, label: String, description: String): PermissionMenuEntry {
            val profile = profiles.firstOrNull { it.id == id }
            return PermissionMenuEntry(id, label, if (profile == null) "Unavailable on this host" else description, profile?.allowed == true)
        }
        val custom = profiles.filterNot { it.id in setOf(READ_ONLY, WORKSPACE, FULL_ACCESS, AUTO_REVIEW, CUSTOM_CONFIG) }
        return buildList {
            add(host(WORKSPACE, "Default permissions", "Runs commands in a sandbox"))
            val autoEnabled = profiles.any { it.id == WORKSPACE && it.allowed } && profiles.any { it.id == AUTO_REVIEW && it.allowed }
            add(PermissionMenuEntry(AUTO_REVIEW, "Auto-review", if (autoEnabled) "Reviews elevated requests automatically" else "Unavailable on this host", autoEnabled))
            add(host(READ_ONLY, "Read only", "Requires approval to edit files or run commands"))
            add(host(FULL_ACCESS, "Full access", "Full computer access (elevated risk)"))
            val configEnabled = profiles.any { it.id == CUSTOM_CONFIG && it.allowed }
            add(PermissionMenuEntry(CUSTOM_CONFIG, "Custom (config.toml)", if (configEnabled) "Codex uses the permission defined in config.toml" else "Unavailable on this host", configEnabled))
            custom.forEach { profile -> add(PermissionMenuEntry(profile.id, "Custom: ${profile.id}", profile.description.ifBlank { "Configured in config.toml" }, profile.allowed)) }
        }
    }

    fun submissionId(selected: String?, profiles: List<PermissionProfile>): String? =
        selected?.takeIf { id -> menu(profiles).any { it.id == id && it.enabled } }

    /** A selected profile owns its sandbox, reviewer and approval policy. */
    fun applySelection(params: JSONObject, profileId: String?) {
        when (profileId) {
            AUTO_REVIEW -> params.put("permissions", WORKSPACE).put("approvalsReviewer", "auto_review")
            null -> Unit
            CUSTOM_CONFIG -> params.put("permissions", CUSTOM_CONFIG)
            READ_ONLY, WORKSPACE, FULL_ACCESS -> params.put("permissions", profileId).put("approvalsReviewer", "user")
            else -> params.put("permissions", profileId)
        }
    }
}
