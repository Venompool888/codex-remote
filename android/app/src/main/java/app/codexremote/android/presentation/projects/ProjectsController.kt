package app.codexremote.android.presentation.projects

import androidx.compose.runtime.State
import androidx.compose.runtime.mutableStateOf
import app.codexremote.android.ui.projects.ProjectFolder
import app.codexremote.android.ui.projects.ProjectHost
import app.codexremote.android.ui.projects.ProjectSubmission

class ProjectsController(
    private val onBrowse: (String, String, (List<ProjectFolder>, String?) -> Unit) -> Unit = { _, _, _ -> },
    private val onSubmit: (ProjectSubmission) -> Unit = {}
) {
    private val _uiState = mutableStateOf(ProjectsUiState())
    val uiState: State<ProjectsUiState> = _uiState

    private var browseGeneration = 0L

    fun openDialog(hosts: List<ProjectHost>, initialServer: String? = null) {
        browseGeneration++
        val selected = hosts.firstOrNull { it.serverUrl == initialServer } ?: hosts.firstOrNull()
        val isNew = (selected == null)
        _uiState.value = ProjectsUiState(
            isOpen = true,
            hosts = hosts,
            selectedHost = selected,
            isNewHost = isNew,
            serverUrl = if (isNew && !initialServer.isNullOrBlank()) initialServer else selected?.serverUrl.orEmpty(),
            connectionName = selected?.name.orEmpty(),
            folderPath = "/",
            folders = emptyList(),
            isLoadingFolders = false,
            isBusy = false,
            formError = null,
            browseError = null
        )
        if (currentServerUrl().isNotBlank()) {
            browseFolders("/")
        }
    }

    fun openPairingLink(hosts: List<ProjectHost>, serverUrl: String, pairingCode: String) {
        browseGeneration++
        _uiState.value = ProjectsUiState(
            isOpen = true,
            hosts = hosts,
            selectedHost = null,
            isNewHost = true,
            serverUrl = serverUrl,
            connectionName = "",
            pairingCode = pairingCode,
            folderPath = "/",
            folders = emptyList(),
            isLoadingFolders = false,
            isBusy = false,
            formError = null,
            browseError = null
        )
    }

    fun dismissDialog() {
        if (_uiState.value.isBusy) return
        browseGeneration++
        _uiState.value = _uiState.value.copy(isOpen = false)
    }

    fun selectHost(host: ProjectHost?) {
        if (_uiState.value.isBusy) return
        browseGeneration++
        val isNew = (host == null)
        _uiState.value = _uiState.value.copy(
            selectedHost = host,
            isNewHost = isNew,
            serverUrl = if (isNew) _uiState.value.serverUrl else host?.serverUrl.orEmpty(),
            connectionName = if (isNew) _uiState.value.connectionName else host?.name.orEmpty(),
            folderPath = "/",
            pairingCode = "",
            folders = emptyList(),
            isEditingFolderPath = false,
            isShowingFolderSuggestions = false,
            isLoadingFolders = false,
            browseError = null,
            formError = null
        )
        browseFolders("/")
    }

    fun updateProjectName(name: String) {
        _uiState.value = _uiState.value.copy(projectName = name, formError = null)
    }

    fun updateServerUrl(url: String) {
        browseGeneration++
        _uiState.value = _uiState.value.copy(
            serverUrl = url,
            folders = emptyList(),
            isLoadingFolders = false,
            browseError = null,
            formError = null
        )
    }

    fun updateConnectionName(name: String) {
        _uiState.value = _uiState.value.copy(connectionName = name, formError = null)
    }

    fun updatePairingCode(code: String) {
        _uiState.value = _uiState.value.copy(pairingCode = code, formError = null)
    }

    fun updateFolderPath(path: String) {
        browseGeneration++
        _uiState.value = _uiState.value.copy(
            folderPath = path,
            folders = emptyList(),
            isEditingFolderPath = true,
            isShowingFolderSuggestions = false,
            isLoadingFolders = path.trim().startsWith('/'),
            browseError = null,
            formError = null
        )
    }

    fun completeFolderPath(path: String) {
        val server = currentServerUrl()
        val input = path.trim()
        if (server.isBlank() || !input.startsWith('/')) {
            _uiState.value = _uiState.value.copy(
                folders = emptyList(),
                isShowingFolderSuggestions = false,
                isLoadingFolders = false
            )
            return
        }
        val prefix = if (input.endsWith('/')) "" else input.substringAfterLast('/')
        val directory = if (prefix.isEmpty()) input else input.substringBeforeLast('/', "").ifBlank { "/" }
        val gen = ++browseGeneration
        _uiState.value = _uiState.value.copy(
            isLoadingFolders = true,
            browseError = null,
            isShowingFolderSuggestions = prefix.isNotEmpty()
        )
        onBrowse(server, directory) { folders, error ->
            if (gen == browseGeneration) {
                _uiState.value = _uiState.value.copy(
                    folders = if (error == null && prefix.isNotEmpty()) {
                        folders.filter { it.name.startsWith(prefix, ignoreCase = true) }
                    } else folders,
                    browseError = error,
                    isLoadingFolders = false
                )
            }
        }
    }

    fun selectFolder(path: String) {
        browseGeneration++
        _uiState.value = _uiState.value.copy(
            folderPath = path,
            folders = emptyList(),
            isEditingFolderPath = false,
            isShowingFolderSuggestions = false,
            isLoadingFolders = false,
            browseError = null,
            formError = null
        )
        browseFolders(path)
    }

    fun currentServerUrl(): String = if (_uiState.value.isNewHost) {
        _uiState.value.serverUrl.trim().trimEnd('/')
    } else {
        _uiState.value.selectedHost?.serverUrl.orEmpty().trim().trimEnd('/')
    }

    fun navigateUp() {
        if (_uiState.value.isBusy) return
        val current = _uiState.value.folderPath.trim()
        val parent = if (current.startsWith("remote-workspace://")) {
            val trimmed = current.trimEnd('/')
            val before = trimmed.substringBeforeLast('/', "")
            if (before == "remote-workspace:" || before.isBlank()) current else before
        } else {
            val trimmed = current.trimEnd('/')
            trimmed.substringBeforeLast('/', "").ifBlank { "/" }
        }
        _uiState.value = _uiState.value.copy(folderPath = parent)
        browseFolders(parent)
    }

    fun browseFolders(path: String) {
        val server = currentServerUrl()
        if (server.isBlank()) {
            _uiState.value = _uiState.value.copy(
                folders = emptyList(),
                browseError = "Enter or select a host to browse folders",
                isEditingFolderPath = false,
                isShowingFolderSuggestions = false,
                isLoadingFolders = false
            )
            return
        }
        val gen = ++browseGeneration
        _uiState.value = _uiState.value.copy(
            isEditingFolderPath = false,
            isShowingFolderSuggestions = false,
            isLoadingFolders = true,
            browseError = null
        )
        onBrowse(server, path) { folders, error ->
            if (gen == browseGeneration) {
                _uiState.value = _uiState.value.copy(
                    folders = folders,
                    browseError = error,
                    isLoadingFolders = false
                )
            }
        }
    }

    fun submit() {
        if (_uiState.value.isBusy) return
        val server = currentServerUrl()
        val path = _uiState.value.folderPath.trim()
        val pairing = if (_uiState.value.isNewHost) _uiState.value.pairingCode.trim() else ""
        val connName = if (_uiState.value.isNewHost) {
            _uiState.value.connectionName.trim().ifBlank {
                server.substringAfter("://").substringBefore('/').substringBefore(':').ifBlank { "Remote" }
            }
        } else {
            _uiState.value.selectedHost?.name.orEmpty().ifBlank {
                server.substringAfter("://").substringBefore('/').substringBefore(':').ifBlank { "Remote" }
            }
        }

        val name = _uiState.value.projectName.trim().ifBlank {
            if (path.startsWith("remote-workspace://")) {
                path.removePrefix("remote-workspace://").trim('/').substringAfterLast('/').ifBlank { "Workspace" }
            } else {
                path.trimEnd('/').substringAfterLast('/').ifBlank { "Remote project" }
            }
        }

        _uiState.value = _uiState.value.copy(formError = null, isBusy = true)
        onSubmit(ProjectSubmission(
            name = name,
            serverUrl = server,
            connectionName = connName,
            pairingCode = pairing,
            workspace = path
        ))
    }

    fun setBusy(busy: Boolean) {
        _uiState.value = _uiState.value.copy(isBusy = busy)
    }

    fun showError(message: String) {
        _uiState.value = _uiState.value.copy(formError = message, isBusy = false)
    }
}
