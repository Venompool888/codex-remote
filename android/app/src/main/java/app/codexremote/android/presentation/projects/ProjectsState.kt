package app.codexremote.android.presentation.projects

import app.codexremote.android.ui.projects.ProjectFolder
import app.codexremote.android.ui.projects.ProjectHost

data class ProjectsUiState(
    val isOpen: Boolean = false,
    val hosts: List<ProjectHost> = emptyList(),
    val selectedHost: ProjectHost? = null,
    val isNewHost: Boolean = false,
    val projectName: String = "",
    val serverUrl: String = "",
    val connectionName: String = "",
    val pairingCode: String = "",
    val folderPath: String = "/",
    val folders: List<ProjectFolder> = emptyList(),
    val isEditingFolderPath: Boolean = false,
    val isShowingFolderSuggestions: Boolean = false,
    val isLoadingFolders: Boolean = false,
    val browseError: String? = null,
    val formError: String? = null,
    val isBusy: Boolean = false
)
