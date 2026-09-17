package app.codexremote.android.presentation.sidebar

import app.codexremote.android.RemoteConnection
import app.codexremote.android.RemoteProject
import app.codexremote.android.RemoteThread
import org.junit.Assert.*
import org.junit.Test

class SidebarConnectionsTest {
    private val mac = RemoteProject("mac", "Shared folder", "Mac", "https://mac", "/same")
    private val secondary = RemoteProject("secondary", "Shared folder", "Remote host", "https://secondary", "/same")
    private val connections = listOf(mac, secondary).map { RemoteConnection(it.connectionName, it.serverUrl, listOf(it)) }

    @Test fun projectsAndRecycleBinBelongToSelectedConnectionEvenWithIdenticalPaths() {
        val controller = SidebarController()
        controller.setConnections(connections, mac.serverUrl, setOf(mac.serverUrl), emptySet())
        controller.setProjects(listOf(mac, secondary), secondary.id)
        controller.setTrashedProjects(listOf(mac, secondary))
        assertEquals(listOf(mac), controller.uiState.value.projects)
        assertEquals(listOf(mac), controller.uiState.value.trashedProjects)
        assertNull(controller.uiState.value.activeProjectId)
        assertEquals("Mac", controller.uiState.value.connectionLabel)
    }

    @Test fun switchingDropsOldHostStateWithoutClosingDrawerOrOpeningConversation() {
        var requested: String? = null
        var openedTasks = 0
        val controller = SidebarController(onSelectConnection = { requested = it }, onSelectThread = { openedTasks++ })
        controller.setConnections(connections, mac.serverUrl, setOf(mac.serverUrl), setOf(secondary.serverUrl))
        controller.setProjects(listOf(mac), mac.id)
        controller.setTrashedProjects(listOf(mac))
        controller.setThreads(listOf(RemoteThread("task", "Mac task", "/same", "idle", 0L, false)), activeThreadId = "task")
        controller.setProjectScope(mac.serverUrl, "/same")
        controller.updateSearchQuery("Mac-only query")
        controller.setPaging(true, true)
        controller.openSidebar()
        controller.openRecycleBin()
        controller.selectConnection(secondary.serverUrl)
        val state = controller.uiState.value
        assertEquals(secondary.serverUrl, requested)
        assertEquals(secondary.serverUrl, state.connectedServerUrl)
        assertEquals("Remote host", state.connectionLabel)
        assertTrue(state.isOpen)
        assertTrue(state.projects.isEmpty() && state.threads.isEmpty() && state.trashedProjects.isEmpty())
        assertNull(state.scopedWorkspace)
        assertNull(state.activeThreadId)
        assertEquals("", state.searchQuery)
        assertFalse(state.showRecycleBin || state.hasMore || state.isLoadingMore)
        assertEquals(0, openedTasks)
        controller.setProjects(listOf(mac, secondary), mac.id)
        assertEquals(listOf(secondary), controller.uiState.value.projects)
    }

    @Test fun refreshOfSameConnectionPreservesSearchExpansionAndWorkspace() {
        val controller = SidebarController()
        controller.setConnections(connections, mac.serverUrl, emptySet(), emptySet())
        controller.setProjects(listOf(mac), mac.id)
        controller.setProjectScope(mac.serverUrl, mac.workspace)
        controller.updateSearchQuery("keep")
        controller.setConnections(connections, mac.serverUrl, setOf(mac.serverUrl), emptySet())
        assertEquals("keep", controller.uiState.value.searchQuery)
        assertEquals(mac.workspace, controller.uiState.value.scopedWorkspace)
        assertTrue(mac.id in controller.uiState.value.expandedProjectIds)
        assertEquals(setOf(mac.serverUrl), controller.uiState.value.connectedServerUrls)
    }

    @Test fun removedOrRepeatedConnectionCannotTriggerSelection() {
        var requests = 0
        val controller = SidebarController(onSelectConnection = { requests++ })
        controller.setConnections(connections, mac.serverUrl, emptySet(), emptySet())
        controller.selectConnection("https://removed")
        controller.selectConnection(mac.serverUrl)
        assertEquals(0, requests)
        controller.setConnections(listOf(connections.last()), secondary.serverUrl, emptySet(), emptySet())
        assertEquals(secondary.serverUrl, controller.uiState.value.connectedServerUrl)
        assertEquals("Remote host", controller.uiState.value.connectionLabel)
    }
}
