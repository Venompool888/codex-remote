package app.codexremote.android.presentation.sidebar

import app.codexremote.android.RemoteProject
import app.codexremote.android.RemoteConnection
import app.codexremote.android.RemoteThread

data class SidebarUiState(
    val isOpen: Boolean = false,
    val connections: List<RemoteConnection> = emptyList(),
    val connectedServerUrls: Set<String> = emptySet(),
    val connectingServers: Set<String> = emptySet(),
    val projects: List<RemoteProject> = emptyList(),
    val trashedProjects: List<RemoteProject> = emptyList(),
    val showRecycleBin: Boolean = false,
    val activeProjectId: String? = null,
    val threads: List<RemoteThread> = emptyList(),
    val searchQuery: String = "",
    val searchVisible: Boolean = false,
    val showAllProjects: Boolean = false,
    val showProjectPage: Boolean = false,
    val expandedWorkspaces: Set<String> = emptySet(),
    val expandedProjectIds: Set<String> = emptySet(),
    val collapsedProjectIds: Set<String> = emptySet(),
    val expandedChildrenProjectIds: Set<String> = emptySet(),
    val showAllUngroupedThreads: Boolean = false,
    val isLoadingThreads: Boolean = false,
    val hasMore: Boolean = false,
    val isLoadingMore: Boolean = false,
    val connectedServerUrl: String? = null,
    val connectionLabel: String = "",
    val activeThreadId: String? = null,
    val scopedServer: String? = null,
    val scopedWorkspace: String? = null
) {
    val filteredThreads: List<RemoteThread>
        get() {
            val query = searchQuery.trim().lowercase()
            val scoped = threads.filter { scopedWorkspace == null || it.cwd == scopedWorkspace }
            return if (query.isBlank()) {
                scoped
            } else {
                scoped.filter {
                    it.title.lowercase().contains(query) ||
                    it.cwd.lowercase().contains(query) ||
                    it.cwdName.lowercase().contains(query)
                }
            }
        }

    val filteredProjects: List<RemoteProject>
        get() {
            val query = searchQuery.trim().lowercase()
            return if (query.isBlank()) {
                projects
            } else {
                projects.filter {
                    it.name.lowercase().contains(query) ||
                    it.workspace.lowercase().contains(query) ||
                    it.serverUrl.lowercase().contains(query)
                }
            }
        }
}
