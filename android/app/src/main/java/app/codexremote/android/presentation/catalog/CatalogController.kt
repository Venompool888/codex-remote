package app.codexremote.android.presentation.catalog

import androidx.compose.runtime.State
import androidx.compose.runtime.mutableStateOf

class CatalogController(
    private val onFetchCatalog: (String, (List<CatalogCapabilityItem>, List<String>) -> Unit) -> Unit = { _, _ -> },
    private val onFetchApps: ((List<ConnectedAppItem>, String?) -> Unit) -> Unit = { _ -> },
    private val onSelectCapability: (CatalogCapabilityItem) -> Unit = {}
) {
    private val _uiState = mutableStateOf(CatalogUiState())
    val uiState: State<CatalogUiState> = _uiState

    private var generation = 0L
    private var activeScope: String? = null

    fun openCatalog(serverUrl: String, cwd: String) {
        val scope = "$serverUrl:$cwd"
        activeScope = scope
        _uiState.value = _uiState.value.copy(
            isOpen = true,
            serverUrl = serverUrl,
            cwd = cwd,
            isLoading = true,
            catalogErrors = emptyList(),
            explanationDialogItem = null
        )
        refresh()
    }

    fun openCatalog(cwd: String) {
        openCatalog(_uiState.value.serverUrl, cwd)
    }

    fun closeCatalog() {
        generation++
        activeScope = null
        _uiState.value = _uiState.value.copy(isOpen = false, explanationDialogItem = null)
    }

    fun updateSearch(query: String) {
        _uiState.value = _uiState.value.copy(searchQuery = query)
    }

    fun selectCategory(category: String) {
        _uiState.value = _uiState.value.copy(activeKind = category)
    }

    fun selectKind(kind: String) {
        _uiState.value = _uiState.value.copy(activeKind = kind)
    }

    fun selectCapability(item: CatalogCapabilityItem) {
        if (!item.isEnabled) {
            _uiState.value = _uiState.value.copy(explanationDialogItem = item)
        } else {
            onSelectCapability(item)
            closeCatalog()
        }
    }

    fun dismissExplanation() {
        _uiState.value = _uiState.value.copy(explanationDialogItem = null)
    }

    fun refresh() {
        val scope = activeScope ?: return
        val ticket = ++generation
        val cwd = _uiState.value.cwd
        _uiState.value = _uiState.value.copy(isLoading = true, catalogErrors = emptyList(), appsLoaded = false, capabilities = emptyList(), apps = emptyList(), appsError = null)

        onFetchCatalog(cwd) { items, errors ->
            if (activeScope == scope && generation == ticket) {
                _uiState.value = _uiState.value.copy(
                    capabilities = items,
                    catalogErrors = errors,
                    isLoading = false
                )
            }
        }

        onFetchApps { appsList, error ->
            if (activeScope == scope && generation == ticket) {
                _uiState.value = _uiState.value.copy(
                    apps = appsList,
                    appsLoaded = true,
                    appsError = error
                )
            }
        }
    }
}
