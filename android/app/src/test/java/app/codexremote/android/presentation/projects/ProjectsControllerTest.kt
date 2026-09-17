package app.codexremote.android.presentation.projects

import app.codexremote.android.ui.projects.ProjectFolder
import app.codexremote.android.ui.projects.ProjectHost
import app.codexremote.android.ui.projects.ProjectSubmission
import org.junit.Assert.*
import org.junit.Test

class ProjectsControllerTest {
    private val hostA = ProjectHost("https://a.example", "A")
    private val hostB = ProjectHost("https://b.example", "B")

    @Test fun pairingLinkOpensNewHostConfirmationWithoutBrowsingOrSubmitting() {
        var browseCalls = 0
        val submissions = mutableListOf<ProjectSubmission>()
        val controller = ProjectsController(
            onBrowse = { _, _, _ -> browseCalls++ },
            onSubmit = submissions::add
        )

        controller.openPairingLink(listOf(hostA), "https://new.example", "abcDEF0123456789")

        val state = controller.uiState.value
        assertTrue(state.isOpen)
        assertTrue(state.isNewHost)
        assertNull(state.selectedHost)
        assertEquals("https://new.example", state.serverUrl)
        assertEquals("abcDEF0123456789", state.pairingCode)
        assertEquals("/", state.folderPath)
        assertEquals(0, browseCalls)
        assertTrue(submissions.isEmpty())
    }

    @Test fun editingPathInvalidatesPendingBrowse() {
        val callbacks = mutableListOf<(List<ProjectFolder>, String?) -> Unit>()
        val controller = ProjectsController(onBrowse = { _, _, done -> callbacks.add(done) })
        controller.openDialog(listOf(hostA))
        controller.updateFolderPath("/changed")
        callbacks.single()(listOf(ProjectFolder("Stale", "/old")), null)
        assertEquals("/changed", controller.uiState.value.folderPath)
        assertTrue(controller.uiState.value.folders.isEmpty())
        assertTrue("Absolute path edit waits for debounced completion", controller.uiState.value.isLoadingFolders)
    }

    @Test fun editingServerInvalidatesPendingBrowse() {
        val callbacks = mutableListOf<(List<ProjectFolder>, String?) -> Unit>()
        val controller = ProjectsController(onBrowse = { _, _, done -> callbacks.add(done) })
        controller.openDialog(emptyList(), "https://a.example")
        controller.updateServerUrl("https://b.example")
        callbacks.single()(listOf(ProjectFolder("Private A", "/a")), null)
        assertTrue(controller.uiState.value.folders.isEmpty())
        assertFalse(controller.uiState.value.isLoadingFolders)
    }

    @Test fun hostChangeResetsHostSpecificDraftAndIgnoresOldBrowse() {
        val callbacks = mutableListOf<(List<ProjectFolder>, String?) -> Unit>()
        val controller = ProjectsController(onBrowse = { _, _, done -> callbacks.add(done) })
        controller.openDialog(listOf(hostA, hostB))
        controller.updateFolderPath("/private-a")
        controller.updatePairingCode("private-code")
        controller.selectHost(hostB)
        callbacks.first()(listOf(ProjectFolder("A", "/private-a")), null)
        assertEquals(hostB, controller.uiState.value.selectedHost)
        assertEquals("/", controller.uiState.value.folderPath)
        assertEquals("", controller.uiState.value.pairingCode)
        assertTrue(controller.uiState.value.folders.isEmpty())
    }

    @Test fun pendingSubmissionCannotDuplicateAndFailureCanRetry() {
        val sent = mutableListOf<ProjectSubmission>()
        val controller = ProjectsController(onSubmit = sent::add)
        controller.openDialog(listOf(hostA))
        controller.updateFolderPath("/repo")
        controller.submit()
        controller.submit()
        assertEquals(1, sent.size)
        assertEquals("/repo", sent.single().workspace)
        assertTrue(controller.uiState.value.isBusy)
        controller.showError("Folder unavailable")
        assertFalse(controller.uiState.value.isBusy)
        assertEquals("Folder unavailable", controller.uiState.value.formError)
        controller.submit()
        assertEquals(2, sent.size)
    }

    @Test fun browseHintDoesNotHideKnownFolders() {
        val controller = ProjectsController(onBrowse = { _, _, done ->
            done(listOf(ProjectFolder("Known", "/known")), "Choose a known project")
        })
        controller.openDialog(listOf(hostA))
        assertEquals("Known", controller.uiState.value.folders.single().name)
        assertEquals("Choose a known project", controller.uiState.value.browseError)
        assertFalse(controller.uiState.value.isLoadingFolders)
    }

    @Test fun folderCompletionBrowsesParentAndFiltersPrefix() {
        val requests = mutableListOf<String>()
        val controller = ProjectsController(onBrowse = { _, path, done ->
            requests.add(path)
            done(listOf(
                ProjectFolder("codex-remote-public", "/var/lib/codex-remote-public"),
                ProjectFolder("containers", "/var/lib/containers"),
                ProjectFolder("CODEX-cache", "/var/lib/CODEX-cache")
            ), null)
        })
        controller.openDialog(listOf(hostA))
        requests.clear()

        controller.updateFolderPath("/var/lib/codex")
        controller.completeFolderPath("/var/lib/codex")

        assertEquals(listOf("/var/lib"), requests)
        assertEquals(listOf("codex-remote-public", "CODEX-cache"), controller.uiState.value.folders.map { it.name })
        assertTrue(controller.uiState.value.isShowingFolderSuggestions)
        assertFalse(controller.uiState.value.isLoadingFolders)
    }

    @Test fun trailingSlashCompletesWithDirectoryChildren() {
        val requests = mutableListOf<String>()
        val controller = ProjectsController(onBrowse = { _, path, done ->
            requests.add(path)
            done(listOf(ProjectFolder("workspace", "$path/workspace")), null)
        })
        controller.openDialog(listOf(hostA))
        requests.clear()

        controller.updateFolderPath("/var/lib/codex-remote-public/")
        controller.completeFolderPath("/var/lib/codex-remote-public/")

        assertEquals(listOf("/var/lib/codex-remote-public/"), requests)
        assertEquals("workspace", controller.uiState.value.folders.single().name)
        assertFalse(controller.uiState.value.isShowingFolderSuggestions)
    }
}
