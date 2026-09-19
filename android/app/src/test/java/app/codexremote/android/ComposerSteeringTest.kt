package app.codexremote.android

import app.codexremote.android.presentation.composer.ComposerController
import org.junit.Assert.*
import org.junit.Test

class ComposerSteeringTest {
    @Test fun steeringNeverInvokesStopAndPreservesRejectedDraft() {
        var stops = 0
        var sends = 0
        var reply: ((Boolean) -> Unit)? = null
        val controller = ComposerController(onStopTurn = { stops++ }, onSteer = { _, _, done -> sends++; reply = done })
        controller.setDraftIdentity("task")
        controller.setTurnRunning(true)
        controller.setSteeringAvailable(true)
        controller.updateText("Use the small fixture")
        controller.steer(); controller.steer()
        assertEquals(1, sends)
        assertEquals(0, stops)
        assertTrue(controller.uiState.value.isSteering)
        reply!!(false)
        assertEquals("Use the small fixture", controller.uiState.value.text)
        assertFalse(controller.uiState.value.isSteering)
    }

    @Test fun acknowledgementCannotClearNewDraftOrAnotherVisitToSameTask() {
        val replies = mutableListOf<(Boolean) -> Unit>()
        val controller = ComposerController(onSteer = { _, _, done -> replies += done })
        controller.setDraftIdentity("a"); controller.setTurnRunning(true); controller.setSteeringAvailable(true)
        controller.updateText("first"); controller.steer(); controller.updateText("second")
        replies[0](true)
        assertEquals("second", controller.uiState.value.text)
        controller.steer()
        controller.setDraftIdentity("b"); controller.setDraftIdentity("a")
        controller.steer()
        replies[1](true)
        assertTrue(controller.uiState.value.isSteering)
        assertEquals("second", controller.uiState.value.text)
        replies[2](true)
        assertEquals("", controller.uiState.value.text)
    }

    @Test fun unsupportedOrIdleTaskDoesNotDispatch() {
        var calls = 0
        val controller = ComposerController(onSteer = { _, _, _ -> calls++ })
        controller.updateText("draft"); controller.steer()
        controller.setTurnRunning(true); controller.steer()
        controller.setSteeringAvailable(true); controller.setAttachments(emptyList(), awaiting = true); controller.steer()
        assertEquals(0, calls)
    }
}
