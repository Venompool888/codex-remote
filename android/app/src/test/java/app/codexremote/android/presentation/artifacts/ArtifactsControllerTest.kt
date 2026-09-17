package app.codexremote.android.presentation.artifacts

import app.codexremote.android.ArtifactDownloadEvent
import org.junit.Assert.*
import org.junit.Test

class ArtifactsControllerTest {
    @Test fun oldDownloadCompletionCannotDisableCancelForNewDownload() {
        for (failure in listOf(false, true)) {
            var cancelled = 0
            val controller = ArtifactsController()
            controller.onDownloadEvent(ArtifactDownloadEvent.Started("old", "old.txt") {})
            controller.onDownloadEvent(ArtifactDownloadEvent.Started("new", "new.txt") { cancelled++ })
            if (failure) controller.onDownloadEvent(ArtifactDownloadEvent.Failed("old") {})
            else controller.onDownloadEvent(ArtifactDownloadEvent.Completed("old", "old.txt", {}, {}))
            controller.cancelDownload("new")
            assertEquals("Old terminal event cannot clear the new cancellation action", 1, cancelled)
            assertNull(controller.uiState.value.downloadProgress)
        }
    }

    @Test fun staleProgressAndDismissalLeaveCurrentDownloadUntouched() {
        val controller = ArtifactsController()
        controller.onDownloadEvent(ArtifactDownloadEvent.Started("current", "a.txt") {})
        controller.onDownloadEvent(ArtifactDownloadEvent.Progress("current", 42))
        controller.onDownloadEvent(ArtifactDownloadEvent.Progress("old", 99))
        controller.onDownloadEvent(ArtifactDownloadEvent.Dismissed("old"))
        assertEquals(42, controller.uiState.value.downloadProgress!!.percent)
        controller.dismissDownload("current")
        controller.onDownloadEvent(ArtifactDownloadEvent.Completed("current", "a.txt", {}, {}))
        assertNull("Dismissed download cannot reopen on late completion", controller.uiState.value.downloadProgress)
    }

    @Test fun newerFetchWinsEvenWhenSameTaskIsReopened() {
        val callbacks = mutableListOf<(List<ArtifactItem>, String?) -> Unit>()
        val controller = ArtifactsController(onFetchArtifacts = { _, done -> callbacks.add(done) })
        controller.openArtifacts("task", "host/device/task")
        controller.closeArtifacts()
        controller.openArtifacts("task", "host/device/task")
        callbacks.last()(listOf(ArtifactItem("new", "New", "/new")), null)
        callbacks.first()(listOf(ArtifactItem("old", "Old", "/old")), null)
        assertEquals("new", controller.uiState.value.artifacts.single().id)
    }

    @Test fun switchingScopeDropsOldListAndIgnoresItsCallback() {
        val callbacks = mutableListOf<(List<ArtifactItem>, String?) -> Unit>()
        val controller = ArtifactsController(onFetchArtifacts = { _, done -> callbacks.add(done) })
        controller.openArtifacts("task-a", "host-a/device/task-a")
        callbacks.single()(listOf(ArtifactItem("a", "Private A", "/a")), null)
        controller.openArtifacts("task-b", "host-b/device/task-b")
        assertTrue(controller.uiState.value.artifacts.isEmpty())
        callbacks.first()(listOf(ArtifactItem("a", "Private A", "/a")), null)
        assertTrue(controller.uiState.value.artifacts.isEmpty())
        assertTrue(controller.uiState.value.isLoading)
    }
}
