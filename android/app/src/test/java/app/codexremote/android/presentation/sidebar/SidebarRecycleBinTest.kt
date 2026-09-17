package app.codexremote.android.presentation.sidebar

import app.codexremote.android.RemoteProject
import app.codexremote.android.RemoteThread
import app.codexremote.android.ui.sidebar.groupThreads
import org.junit.Assert.*
import org.junit.Test

class SidebarRecycleBinTest {
    private val project = RemoteProject("one", "workspace", "Host", "https://a", "/same")
    private val thread = RemoteThread("task", "Saved task", "/same", "idle", 0L, false)

    @Test fun recycledProjectTasksCannotReappearAsRecentTasks() {
        val groups = groupThreads(listOf(thread), emptyList(), project.serverUrl, null, "", listOf(project))
        assertTrue(groups.ungroupedThreads.isEmpty())
    }

    @Test fun visibleDuplicateTakesPrecedenceOverRecycledIdentity() {
        val visible = project.copy(id = "two")
        val groups = groupThreads(listOf(thread), listOf(visible), project.serverUrl, null, "", listOf(project))
        assertEquals(listOf(thread), groups.projectThreads[visible.id])
    }

    @Test fun recycledSamePathOnOtherHostDoesNotHideCurrentHostTasks() {
        val groups = groupThreads(listOf(thread), emptyList(), "https://b", null, "", listOf(project))
        assertEquals(listOf(thread), groups.ungroupedThreads)
    }

    @Test fun recycleAndRestoreCallbacksTargetExactProjectIdentity() {
        var trashed: RemoteProject? = null
        var restored: RemoteProject? = null
        val controller = SidebarController(onTrashProject = { trashed = it }, onRestoreProject = { restored = it })
        controller.setProjects(listOf(project), project.id)
        controller.trashProject(project)
        assertEquals(project, trashed)
        assertNull(restored)
        controller.setProjects(emptyList(), null)
        controller.setTrashedProjects(listOf(project))
        controller.restoreProject(project)
        assertEquals(project, restored)
    }
}
