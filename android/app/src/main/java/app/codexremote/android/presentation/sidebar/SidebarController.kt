package app.codexremote.android.presentation.sidebar

import androidx.compose.runtime.State
import androidx.compose.runtime.mutableStateOf
import app.codexremote.android.RemoteProject
import app.codexremote.android.RemoteConnection
import app.codexremote.android.RemoteThread

class SidebarController(
    private val onSelectProject: (RemoteProject) -> Unit = {},
    private val onSelectThread: (RemoteThread) -> Unit = {},
    private val onNewThread: () -> Unit = {},
    private val onOpenConnections: () -> Unit = {},
    private val onOpenNewProject: () -> Unit = {},
    private val onRefresh: () -> Unit = {},
    private val onOpenProject: (server: String, workspace: String) -> Unit = { _, _ -> },
    private val onLoadMore: () -> Unit = {},
    private val onTrashProject: (RemoteProject) -> Unit = {},
    private val onRestoreProject: (RemoteProject) -> Unit = {},
    private val onSelectConnection: (String) -> Unit = {}
) {
    private val _uiState = mutableStateOf(SidebarUiState())
    val uiState: State<SidebarUiState> = _uiState

    fun setConnections(
        connections: List<RemoteConnection>,
        selectedServer: String?,
        connectedServers: Set<String>,
        connectingServers: Set<String>
    ) {
        if (selectedServer != _uiState.value.connectedServerUrl) resetConnectionScope(selectedServer)
        _uiState.value = _uiState.value.copy(
            connections = connections,
            connectedServerUrls = connectedServers,
            connectingServers = connectingServers,
            connectionLabel = connections.firstOrNull { it.serverUrl == selectedServer }?.name.orEmpty()
        )
    }

    fun selectConnection(serverUrl: String) {
        val state = _uiState.value
        if (serverUrl == state.connectedServerUrl || state.connections.none { it.serverUrl == serverUrl }) return
        resetConnectionScope(serverUrl)
        _uiState.value = _uiState.value.copy(
            connectionLabel = state.connections.first { it.serverUrl == serverUrl }.name
        )
        onSelectConnection(serverUrl)
    }

    private fun resetConnectionScope(serverUrl: String?) {
        // Keep the drawer open while dropping every piece of host-owned presentation state.
        _uiState.value = _uiState.value.copy(
            connectedServerUrl = serverUrl,
            projects = emptyList(), trashedProjects = emptyList(), threads = emptyList(),
            activeProjectId = null, activeThreadId = null,
            scopedServer = null, scopedWorkspace = null,
            searchQuery = "", showRecycleBin = false, showProjectPage = false,
            showAllProjects = false, showAllUngroupedThreads = false,
            expandedWorkspaces = emptySet(), expandedProjectIds = emptySet(),
            collapsedProjectIds = emptySet(), expandedChildrenProjectIds = emptySet(),
            hasMore = false, isLoadingMore = false, isLoadingThreads = false
        )
    }

    fun openSidebar() {
        _uiState.value = _uiState.value.copy(isOpen = true)
        onRefresh()
    }

    fun closeSidebar() {
        _uiState.value = _uiState.value.copy(isOpen = false)
    }

    fun setProjects(projects: List<RemoteProject>, activeId: String?) {
        val server = _uiState.value.connectedServerUrl
        val scopedProjects = projects.filter { server == null || it.serverUrl == server }
        val validIds = scopedProjects.map { it.id }.toSet()
        val scopedActiveId = activeId?.takeIf { it in validIds }
        val currentExpanded = _uiState.value.expandedProjectIds.filter { it in validIds }.toSet()
        val currentCollapsed = _uiState.value.collapsedProjectIds.filter { it in validIds }.toSet()
        val currentExpandedChildren = _uiState.value.expandedChildrenProjectIds.filter { it in validIds }.toSet()
        val currentActive = _uiState.value.activeProjectId

        val isNewActiveProject = scopedActiveId != null && scopedActiveId != currentActive
        val isFirstActiveProject = scopedActiveId != null && currentActive == null

        val newExpanded = when {
            (isNewActiveProject || isFirstActiveProject) && scopedActiveId != null -> {
                if (scopedActiveId in currentCollapsed) {
                    currentExpanded
                } else {
                    currentExpanded + scopedActiveId
                }
            }
            else -> {
                currentExpanded
            }
        }

        _uiState.value = _uiState.value.copy(
            projects = scopedProjects,
            activeProjectId = scopedActiveId,
            expandedProjectIds = newExpanded,
            collapsedProjectIds = currentCollapsed,
            expandedChildrenProjectIds = currentExpandedChildren
        )
    }

    fun setTrashedProjects(projects: List<RemoteProject>) {
        val server = _uiState.value.connectedServerUrl
        val scopedProjects = projects.filter { server == null || it.serverUrl == server }
        _uiState.value = _uiState.value.copy(
            trashedProjects = scopedProjects,
            showRecycleBin = if (scopedProjects.isEmpty()) false else _uiState.value.showRecycleBin
        )
    }

    fun setConnectionInfo(serverUrl: String?, label: String) {
        _uiState.value = _uiState.value.copy(
            connectedServerUrl = serverUrl,
            connectionLabel = label
        )
    }

    fun setThreads(threads: List<RemoteThread>, isLoading: Boolean = false, activeThreadId: String? = null) {
        _uiState.value = _uiState.value.copy(
            threads = threads,
            isLoadingThreads = isLoading,
            activeThreadId = activeThreadId
        )
    }

    fun setProjectScope(server: String?, workspace: String?) {
        _uiState.value = _uiState.value.copy(
            scopedServer = server,
            scopedWorkspace = workspace
        )
    }

    fun setPaging(hasMore: Boolean, loading: Boolean) {
        _uiState.value = _uiState.value.copy(
            hasMore = hasMore,
            isLoadingMore = loading
        )
    }

    fun back(): Boolean {
        val s = _uiState.value
        if (s.showRecycleBin) {
            _uiState.value = s.copy(showRecycleBin = false)
            return true
        }
        if (s.scopedWorkspace != null) {
            _uiState.value = s.copy(scopedWorkspace = null, scopedServer = null, searchQuery = "")
            return true
        }
        if (s.showProjectPage) {
            _uiState.value = s.copy(showProjectPage = false)
            return true
        }
        if (s.searchVisible) {
            _uiState.value = s.copy(searchVisible = false, searchQuery = "")
            return true
        }
        if (s.isOpen) {
            _uiState.value = s.copy(isOpen = false)
            return true
        }
        return false
    }

    fun loadMore() {
        if (!_uiState.value.isLoadingMore && _uiState.value.hasMore) {
            onLoadMore()
        }
    }

    fun openProject(server: String, workspace: String) {
        setProjectScope(server, workspace)
        _uiState.value = _uiState.value.copy(showProjectPage = false, searchQuery = "")
        onOpenProject(server, workspace)
    }

    fun showProjectPage(show: Boolean = true) {
        _uiState.value = _uiState.value.copy(showProjectPage = show)
    }

    fun selectProject(project: RemoteProject) {
        val currentExpanded = _uiState.value.expandedProjectIds
        val currentCollapsed = _uiState.value.collapsedProjectIds
        _uiState.value = _uiState.value.copy(
            activeProjectId = project.id,
            showProjectPage = false,
            expandedProjectIds = currentExpanded + project.id,
            collapsedProjectIds = currentCollapsed - project.id
        )
        openProject(project.serverUrl, project.workspace)
    }

    fun toggleProjectExpanded(projectId: String) {
        val currentExpanded = _uiState.value.expandedProjectIds.toMutableSet()
        val currentCollapsed = _uiState.value.collapsedProjectIds.toMutableSet()
        val currentExpandedChildren = _uiState.value.expandedChildrenProjectIds.toMutableSet()
        if (projectId in currentExpanded) {
            currentExpanded.remove(projectId)
            currentCollapsed.add(projectId)
            currentExpandedChildren.remove(projectId)
        } else {
            currentExpanded.add(projectId)
            currentCollapsed.remove(projectId)
        }
        _uiState.value = _uiState.value.copy(
            expandedProjectIds = currentExpanded,
            collapsedProjectIds = currentCollapsed,
            expandedChildrenProjectIds = currentExpandedChildren
        )
    }

    fun showMoreProjectChildren(projectId: String) {
        _uiState.value = _uiState.value.copy(
            expandedChildrenProjectIds = _uiState.value.expandedChildrenProjectIds + projectId
        )
    }

    fun showMoreUngroupedThreads() {
        _uiState.value = _uiState.value.copy(showAllUngroupedThreads = true)
    }

    fun trashProject(project: RemoteProject) {
        val currentExpanded = _uiState.value.expandedProjectIds - project.id
        val currentCollapsed = _uiState.value.collapsedProjectIds - project.id
        val currentChildren = _uiState.value.expandedChildrenProjectIds - project.id
        val updatedTrashed = (_uiState.value.trashedProjects.filterNot { it.id == project.id } + project)
        _uiState.value = _uiState.value.copy(
            expandedProjectIds = currentExpanded,
            collapsedProjectIds = currentCollapsed,
            expandedChildrenProjectIds = currentChildren,
            trashedProjects = updatedTrashed
        )
        onTrashProject(project)
    }

    fun restoreProject(project: RemoteProject) {
        val remaining = _uiState.value.trashedProjects.filterNot { it.id == project.id }
        _uiState.value = _uiState.value.copy(
            trashedProjects = remaining,
            showRecycleBin = remaining.isNotEmpty() && _uiState.value.showRecycleBin
        )
        onRestoreProject(project)
    }

    fun openRecycleBin() {
        _uiState.value = _uiState.value.copy(showRecycleBin = true)
    }

    fun closeRecycleBin() {
        _uiState.value = _uiState.value.copy(showRecycleBin = false)
    }

    fun selectThread(thread: RemoteThread) {
        _uiState.value = _uiState.value.copy(activeThreadId = thread.id, isOpen = false)
        onSelectThread(thread)
    }

    fun createNewThread() {
        _uiState.value = _uiState.value.copy(isOpen = false)
        onNewThread()
    }

    fun newThreadInProject(project: RemoteProject) {
        _uiState.value = _uiState.value.copy(
            isOpen = false,
            activeProjectId = project.id,
            activeThreadId = null,
            scopedServer = project.serverUrl,
            scopedWorkspace = project.workspace
        )
        onSelectProject(project)
    }

    fun updateSearchQuery(query: String) {
        _uiState.value = _uiState.value.copy(searchQuery = query)
    }

    fun toggleSearch(visible: Boolean? = null) {
        val next = visible ?: !_uiState.value.searchVisible
        _uiState.value = _uiState.value.copy(
            searchVisible = next,
            searchQuery = if (!next) "" else _uiState.value.searchQuery
        )
    }

    fun toggleAllProjects() {
        _uiState.value = _uiState.value.copy(showAllProjects = !_uiState.value.showAllProjects)
    }

    fun toggleWorkspace(path: String) {
        val current = _uiState.value.expandedWorkspaces.toMutableSet()
        if (path in current) current.remove(path) else current.add(path)
        _uiState.value = _uiState.value.copy(expandedWorkspaces = current)
    }

    fun openConnections() {
        _uiState.value = _uiState.value.copy(isOpen = false)
        onOpenConnections()
    }

    fun openNewProject() {
        _uiState.value = _uiState.value.copy(isOpen = false)
        onOpenNewProject()
    }
}
