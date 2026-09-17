package app.codexremote.android

import app.codexremote.android.compose.AttachmentItemUiState
import app.codexremote.android.compose.AttachmentUploadState
import app.codexremote.android.compose.CapabilityTagUiState
import app.codexremote.android.compose.DraftImageOpenGate
import app.codexremote.android.compose.FileAttachmentUiState
import app.codexremote.android.compose.ImageAttachmentUiState
import app.codexremote.android.compose.formatMiddleEllipsis
import app.codexremote.android.compose.mergeAttachmentUiState
import app.codexremote.android.compose.updateImagePreviewLoading
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AttachmentCardUiStateTest {

    @Test
    fun testUploadStateDisplayText() {
        assertEquals("Preparing…", AttachmentUploadState.Preparing.displayText)
        assertEquals("Waiting…", AttachmentUploadState.Waiting.displayText)
        assertEquals("45%", AttachmentUploadState.Uploading(45).displayText)
        assertEquals("", AttachmentUploadState.Ready.displayText)
        assertEquals("Failed · retry", AttachmentUploadState.Failed().displayText)
        assertEquals("Network timeout", AttachmentUploadState.Failed("Network timeout").displayText)
        assertEquals("Cancelled", AttachmentUploadState.Cancelled.displayText)
        assertEquals("Expired", AttachmentUploadState.Expired.displayText)
    }

    @Test
    fun testFormatMiddleEllipsis() {
        val shortName = "document.pdf"
        assertEquals(shortName, formatMiddleEllipsis(shortName, 20))

        val longName = "very_long_file_name_for_acceptance_testing_and_verification_2026.tar.gz"
        val truncated = formatMiddleEllipsis(longName, 24)
        assertTrue(truncated.contains("…"))
        assertTrue(truncated.startsWith("very_long"))
        assertTrue(truncated.endsWith(".gz"))
        assertTrue(truncated.length <= 24)
    }

    @Test
    fun testCardUiStateDefaults() {
        val imageState = ImageAttachmentUiState(
            localId = "img-1",
            name = "photo.png"
        )
        assertEquals("img-1", imageState.localId)
        assertEquals("photo.png", imageState.name)
        assertEquals(AttachmentUploadState.Ready, imageState.uploadState)

        val fileState = FileAttachmentUiState(
            localId = "file-1",
            name = "report.csv",
            sizeBytes = 2048L,
            metadataText = "CSV · 2 KB"
        )
        assertEquals("file-1", fileState.localId)
        assertEquals(2048L, fileState.sizeBytes)
        assertEquals("CSV · 2 KB", fileState.metadataText)

        val capState = CapabilityTagUiState(
            localId = "cap-1",
            name = "code-review",
            description = "Code review skill"
        )
        assertEquals("cap-1", capState.localId)
        assertEquals("code-review", capState.name)
    }

    @Test
    fun testImageAttachmentUiStateAsyncThumbnailAndLoadingPreview() {
        val initial = ImageAttachmentUiState(
            localId = "img-async",
            name = "camera.jpg",
            previewBitmap = null,
            localFilePath = null,
            uploadState = AttachmentUploadState.Preparing,
            isLoadingPreview = false
        )
        assertEquals(null, initial.previewBitmap)
        assertEquals(null, initial.localFilePath)
        assertEquals(false, initial.isLoadingPreview)

        // Simulate async import arriving later with local path
        val withPath = initial.copy(
            localFilePath = "/data/user/0/app.codexremote.android/cache/camera.jpg",
            uploadState = AttachmentUploadState.Ready
        )
        assertEquals("/data/user/0/app.codexremote.android/cache/camera.jpg", withPath.localFilePath)
        assertEquals(AttachmentUploadState.Ready, withPath.uploadState)

        // Simulate clicking to view draft image (loading preview indicator toggled)
        val loading = withPath.copy(isLoadingPreview = true)
        assertTrue(loading.isLoadingPreview)

        // Simulate finish loading preview
        val loaded = loading.copy(isLoadingPreview = false)
        assertEquals(false, loaded.isLoadingPreview)
    }

    @Test
    fun testFileAttachmentReorderingAndRemovalKeyIntegrity() {
        val file1 = FileAttachmentUiState(
            localId = "id-pdf",
            name = "whitepaper.pdf",
            sizeBytes = 1024L,
            metadataText = "PDF · 1 KB"
        )
        val file2 = FileAttachmentUiState(
            localId = "id-csv",
            name = "metrics.csv",
            sizeBytes = 2048L,
            metadataText = "CSV · 2 KB"
        )

        val items = mutableListOf(file1, file2)
        assertEquals(2, items.size)
        assertEquals("whitepaper.pdf", items[0].name)
        assertEquals("metrics.csv", items[1].name)

        // Removing the first item leaves the CSV intact with its own stable localId
        items.removeAt(0)
        assertEquals(1, items.size)
        assertEquals("id-csv", items[0].localId)
        assertEquals("metrics.csv", items[0].name)

        // Verify file extension calculation for icon assignment
        val ext = items[0].name.substringAfterLast('.', "FILE").uppercase()
        assertEquals("CSV", ext)
    }

    @Test
    fun testPeriodicRefreshStateMergePreservesLoadingPreviewAndExistingState() {
        val existing = ImageAttachmentUiState(
            localId = "img-1",
            name = "photo.png",
            uploadState = AttachmentUploadState.Uploading(25),
            isLoadingPreview = true
        )
        // 750ms refresh delivers new state with updated progress, but buildUiState defaults isLoadingPreview = false
        val incoming = ImageAttachmentUiState(
            localId = "img-1",
            name = "photo.png",
            uploadState = AttachmentUploadState.Uploading(60),
            isLoadingPreview = false
        )

        val merged = mergeAttachmentUiState(existing, incoming) as ImageAttachmentUiState
        // Ensure isLoadingPreview was NOT reset to false by the periodic 750ms poll
        assertTrue(merged.isLoadingPreview)
        // Ensure latest progress from incoming state is merged
        assertEquals(AttachmentUploadState.Uploading(60), merged.uploadState)

        // Once preview finishes loading, subsequent refresh keeps isLoadingPreview = false
        val loaded = existing.copy(isLoadingPreview = false, uploadState = AttachmentUploadState.Ready)
        val refreshAfterLoaded = mergeAttachmentUiState(loaded, incoming.copy(uploadState = AttachmentUploadState.Ready)) as ImageAttachmentUiState
        assertFalse(refreshAfterLoaded.isLoadingPreview)
        assertEquals(AttachmentUploadState.Ready, refreshAfterLoaded.uploadState)

        // FileAttachmentUiState passes through
        val fileExisting = FileAttachmentUiState(localId = "f1", name = "report.pdf", uploadState = AttachmentUploadState.Waiting)
        val fileIncoming = FileAttachmentUiState(localId = "f1", name = "report.pdf", uploadState = AttachmentUploadState.Ready)
        assertEquals(fileIncoming, mergeAttachmentUiState(fileExisting, fileIncoming))
    }

    @Test
    fun testDraftImageOpenGatePreventsDuplicateDecodeAndHandlesLifecycle() {
        val gate = DraftImageOpenGate()
        val scope1 = "server1\u0000dev1\u0000thread1"
        val scope2 = "server1\u0000dev1\u0000thread2"

        // First click acquires lease
        val lease1 = gate.tryAcquire(scope1, "img-1")
        assertNotNull(lease1)
        assertTrue(gate.isOpening(scope1, "img-1"))

        // Duplicate click while in-flight is rejected (prevents duplicate decode)
        assertNull(gate.tryAcquire(scope1, "img-1"))

        // Other image in same draft is independent
        val lease2 = gate.tryAcquire(scope1, "img-2")
        assertNotNull(lease2)
        assertNull(gate.tryAcquire(scope1, "img-2"))

        // Same image id in different draft scope is independent
        val leaseScope2 = gate.tryAcquire(scope2, "img-1")
        assertNotNull(leaseScope2)

        // Release on completion/failure restores ability to open again
        assertTrue(gate.release(lease1))
        assertFalse(gate.isOpening(scope1, "img-1"))
        assertNotNull(gate.tryAcquire(scope1, "img-1"))

        // Clearing gate on draft switch frees all pending latch keys
        gate.clear()
        assertFalse(gate.isOpening(scope1, "img-1"))
        assertFalse(gate.isOpening(scope1, "img-2"))
        assertFalse(gate.isOpening(scope2, "img-1"))
        assertNotNull(gate.tryAcquire(scope1, "img-1"))
    }

    @Test
    fun testDraftImageOpenGateLeasePreventsOldRequestReleasingNewLatch() {
        val gate = DraftImageOpenGate()
        val scope = "server1\u0000dev1\u0000thread1"
        val localId = "img-1"

        // Request A acquires lease
        val leaseA = gate.tryAcquire(scope, localId)
        assertNotNull(leaseA)
        assertTrue(gate.isOpening(scope, localId))

        // While Request A is running, draft switch / clear occurs
        gate.clear()
        assertFalse(gate.isOpening(scope, localId))

        // New Request B for same scope & localId acquires its own lease
        val leaseB = gate.tryAcquire(scope, localId)
        assertNotNull(leaseB)
        assertTrue(gate.isOpening(scope, localId))
        assertTrue(leaseA!!.token != leaseB!!.token)

        // Request A finishes and attempts to release with its stale leaseA
        val releasedA = gate.release(leaseA)
        // Stale lease must NOT be accepted
        assertFalse(releasedA)
        // Request B's latch must remain active and protected
        assertTrue(gate.isOpening(scope, localId))
        assertNull(gate.tryAcquire(scope, localId))

        // When Request B finishes, releasing leaseB succeeds
        val releasedB = gate.release(leaseB)
        assertTrue(releasedB)
        assertFalse(gate.isOpening(scope, localId))
    }

    @Test
    fun testUpdateImagePreviewLoadingShiftingAndResurrectionProtection() {
        val item0 = FileAttachmentUiState(localId = "doc-0", name = "spec.pdf")
        val targetImg = ImageAttachmentUiState(
            localId = "img-1",
            name = "chart.png",
            uploadState = AttachmentUploadState.Ready,
            isLoadingPreview = false
        )
        val item2 = FileAttachmentUiState(localId = "doc-2", name = "data.csv")
        val items = mutableListOf<AttachmentItemUiState>(item0, targetImg, item2)

        // Start loading preview using the real production function
        val started = items.updateImagePreviewLoading("img-1", isLoading = true)
        assertNotNull(started)
        assertTrue(started!!.isLoadingPreview)
        assertTrue((items[1] as ImageAttachmentUiState).isLoadingPreview)

        // Case A: Preceding item removed during delay
        items.removeAll { it.localId == "doc-0" }
        assertEquals(2, items.size)
        // Delayed completion updates via real production function
        val completedA = items.updateImagePreviewLoading("img-1", isLoading = false)
        assertNotNull(completedA)
        assertFalse(completedA!!.isLoadingPreview)
        // Found at new index 0
        assertEquals(0, items.indexOfFirst { it.localId == "img-1" })
        assertFalse((items[0] as ImageAttachmentUiState).isLoadingPreview)
        // Adjacent item preserved and uncorrupted
        assertEquals("doc-2", items[1].localId)
        assertEquals("data.csv", items[1].name)

        // Case B: Target item itself removed during delay
        items.removeAll { it.localId == "img-1" }
        assertEquals(1, items.size)
        // Delayed completion updates via real production function
        val completedB = items.updateImagePreviewLoading("img-1", isLoading = false)
        assertNull(completedB)
        // Deleted item is NOT resurrected
        assertEquals(1, items.size)
        assertEquals("doc-2", items[0].localId)

        // Case C: Concurrent state update while loading preview is preserved
        val freshTarget = ImageAttachmentUiState(
            localId = "img-live",
            name = "diagram.png",
            uploadState = AttachmentUploadState.Uploading(40),
            isLoadingPreview = true
        )
        items.add(freshTarget)
        // Background update modifies uploadState to Ready
        val liveIdx = items.indexOfFirst { it.localId == "img-live" }
        items[liveIdx] = (items[liveIdx] as ImageAttachmentUiState).copy(uploadState = AttachmentUploadState.Ready)

        // Real production function completes and merges with latest item
        val completedC = items.updateImagePreviewLoading("img-live", isLoading = false)
        assertNotNull(completedC)
        assertFalse(completedC!!.isLoadingPreview)
        // Concurrently updated Ready state must NOT be wiped
        assertEquals(AttachmentUploadState.Ready, completedC.uploadState)
    }
}
