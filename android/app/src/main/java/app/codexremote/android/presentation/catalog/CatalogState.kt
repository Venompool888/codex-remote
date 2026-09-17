package app.codexremote.android.presentation.catalog

data class CatalogCapabilityItem(
    val id: String,
    val name: String,
    val description: String,
    val kind: String = "skill", // "plugin" or "skill"
    val state: String = "enabled", // "enabled", "disabled", etc.
    val detail: String = "",
    val source: String = ""
) {
    val isEnabled: Boolean get() = state.equals("enabled", ignoreCase = true)
}

// Backward-compatibility alias if needed
typealias CatalogSkillItem = CatalogCapabilityItem

data class ConnectedAppItem(
    val name: String,
    val callable: Boolean = false,
    val enabled: Boolean = false,
    val statusText: String = when {
        callable -> "Tools available on this host"
        enabled -> "Connected · no callable tools reported"
        else -> "Disabled · manage on host"
    }
)

data class CatalogUiState(
    val isOpen: Boolean = false,
    val serverUrl: String = "",
    val cwd: String = "",
    val capabilities: List<CatalogCapabilityItem> = emptyList(),
    val catalogErrors: List<String> = emptyList(),
    val apps: List<ConnectedAppItem> = emptyList(),
    val appsLoaded: Boolean = false,
    val appsError: String? = null,
    val searchQuery: String = "",
    val activeKind: String = "All", // "All", "Plugins", "Skills"
    val isLoading: Boolean = false,
    val errorMessage: String? = null,
    val explanationDialogItem: CatalogCapabilityItem? = null
) {
    val categories: List<String>
        get() = listOf("All", "Plugins", "Skills")

    val filteredCapabilities: List<CatalogCapabilityItem>
        get() {
            val q = searchQuery.trim().lowercase()
            return capabilities.filter { item ->
                val matchesKind = when (activeKind) {
                    "Plugins" -> item.kind.equals("plugin", ignoreCase = true)
                    "Skills" -> item.kind.equals("skill", ignoreCase = true)
                    else -> true
                }
                val matchesQuery = q.isBlank() ||
                    item.name.lowercase().contains(q) ||
                    item.description.lowercase().contains(q)
                matchesKind && matchesQuery
            }
        }

    val filteredApps: List<ConnectedAppItem>
        get() {
            val q = searchQuery.trim().lowercase()
            return if (q.isBlank()) apps else apps.filter { it.name.lowercase().contains(q) }
        }

    // Compatibility for existing callers
    val skills: List<CatalogCapabilityItem> get() = capabilities
    val filteredSkills: List<CatalogCapabilityItem> get() = filteredCapabilities
}
