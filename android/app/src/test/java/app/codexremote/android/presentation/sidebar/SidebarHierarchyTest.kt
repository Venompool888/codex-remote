package app.codexremote.android.presentation.sidebar

import app.codexremote.android.RemoteProject
import app.codexremote.android.RemoteThread
import app.codexremote.android.ui.sidebar.groupThreads
import org.junit.Assert.*
import org.junit.Test

class SidebarHierarchyTest {
    private val a = RemoteProject("a", "A", "Host A", "https://a", "/same")
    private val b = RemoteProject("b", "B", "Host B", "https://b", "/same")
    private val sibling = RemoteProject("s", "Sibling", "Host A", "https://a", "/sibling")
    private val first = RemoteThread("first", "First", "/same", "idle", 0L, false)
    private val second = RemoteThread("second", "Second", "/sibling", "idle", 0L, false)

    @Test fun projectNewChatUsesExactHostAndFolderWithoutExpandingRow() {
        var chosen: RemoteProject? = null
        var genericNewChats = 0
        val controller = SidebarController(onSelectProject = { chosen = it }, onNewThread = { genericNewChats++ })
        controller.setProjects(listOf(a, b), a.id)
        controller.setThreads(listOf(first), activeThreadId = first.id)
        controller.openSidebar()
        val expanded = controller.uiState.value.expandedProjectIds
        controller.newThreadInProject(b)
        assertEquals(b, chosen)
        assertEquals(0, genericNewChats)
        assertEquals(b.serverUrl, controller.uiState.value.scopedServer)
        assertEquals(b.workspace, controller.uiState.value.scopedWorkspace)
        assertNull(controller.uiState.value.activeThreadId)
        assertFalse(controller.uiState.value.isOpen)
        assertEquals(expanded, controller.uiState.value.expandedProjectIds)
    }

    @Test fun expandingUsesSidebarNavigationWithoutOpeningDraft() {
        var draftActivations = 0
        var opened: Pair<String, String>? = null
        val controller = SidebarController(onSelectProject = { draftActivations++ }, onOpenProject = { server, workspace -> opened = server to workspace })
        controller.setProjects(listOf(a, b), a.id)
        controller.openSidebar()
        controller.selectProject(b)
        assertEquals(0, draftActivations)
        assertEquals(b.serverUrl to b.workspace, opened)
        assertTrue(controller.uiState.value.isOpen)
    }

    @Test fun refreshPreservesExplicitCollapse() {
        val controller = SidebarController()
        controller.setProjects(listOf(a), a.id)
        controller.openSidebar()
        controller.toggleProjectExpanded(a.id)
        controller.setProjects(listOf(a), a.id)
        assertFalse(a.id in controller.uiState.value.expandedProjectIds)
    }

    @Test fun persistedActiveProjectRefreshDoesNotUndoCollapseWhileBrowsingSibling() {
        val controller = SidebarController()
        controller.setProjects(listOf(a, sibling), a.id)
        controller.toggleProjectExpanded(a.id)
        controller.selectProject(sibling)
        controller.setProjects(listOf(a, sibling), a.id)
        assertFalse(a.id in controller.uiState.value.expandedProjectIds)
        assertTrue(sibling.id in controller.uiState.value.expandedProjectIds)
    }

    @Test fun creationScopeDoesNotHideExpandedSiblingTasks() {
        val groups = groupThreads(listOf(first, second), listOf(a, b, sibling), a.serverUrl, a.workspace, "")
        assertEquals(listOf(first), groups.projectThreads[a.id])
        assertEquals(listOf(second), groups.projectThreads[sibling.id])
        assertTrue(groups.projectThreads[b.id].isNullOrEmpty())
    }

    @Test fun samePathOnOtherHostNeverReceivesCurrentHostThreads() {
        val groups = groupThreads(listOf(first), listOf(b, a), a.serverUrl, null, "")
        assertEquals(listOf(first), groups.projectThreads[a.id])
        assertTrue(groups.projectThreads[b.id].isNullOrEmpty())
    }
}
