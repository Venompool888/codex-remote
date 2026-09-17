package app.codexremote.android

import app.codexremote.android.presentation.conversation.ConversationController
import org.junit.Assert.*
import org.junit.Test

class ConversationLoadingTest {
    @Test fun placeholderAndLiveRendersCannotDismissPendingHistoryLoad() {
        val controller = ConversationController()
        controller.setTimelineItems(listOf(TimelineItem("old", "", "Old conversation", TimelineItem.Kind.ASSISTANT)), false)
        controller.beginThreadLoad()
        assertTrue(controller.uiState.value.items.isEmpty())
        controller.setTimelineItems(emptyList(), false)
        assertTrue(controller.uiState.value.isLoading)
        controller.setTimelineItems(listOf(TimelineItem("new", "", "Partial response", TimelineItem.Kind.ASSISTANT)), false)
        assertTrue(controller.uiState.value.isLoading)
        controller.finishThreadLoad()
        assertFalse(controller.uiState.value.isLoading)
        assertNull(controller.uiState.value.loadError)
    }

    @Test fun failureIsPersistentAndRetryStartsFreshLoading() {
        var retries = 0
        val controller = ConversationController(onRetryLoading = { retries++ })
        controller.beginThreadLoad()
        controller.retryLoading()
        assertEquals(0, retries)
        controller.failThreadLoad("Please retry")
        controller.setTimelineItems(emptyList(), false)
        assertEquals("Please retry", controller.uiState.value.loadError)
        assertFalse(controller.uiState.value.isLoading)
        controller.retryLoading()
        assertEquals(1, retries)
        controller.beginThreadLoad()
        assertNull(controller.uiState.value.loadError)
        assertTrue(controller.uiState.value.isLoading)
        controller.setLoading(false)
        assertFalse(controller.isThreadLoadPending)
    }
}
