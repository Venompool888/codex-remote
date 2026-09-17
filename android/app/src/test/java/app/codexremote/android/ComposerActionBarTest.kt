package app.codexremote.android

import app.codexremote.android.compose.ComposerActionBarUiState
import app.codexremote.android.compose.calculateComposerActionBarUiState
import app.codexremote.android.compose.formatConciseModelLabel
import app.codexremote.android.presentation.composer.ComposerOption
import app.codexremote.android.presentation.composer.ComposerUiState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ComposerActionBarTest {

    @Test
    fun lightningTracksSelectedFastModeInsteadOfAvailableOptions() {
        val controller = app.codexremote.android.presentation.composer.ComposerController()
        val options = listOf(
            app.codexremote.android.presentation.composer.ComposerOption("", "Standard"),
            app.codexremote.android.presentation.composer.ComposerOption("fast", "Fast")
        )
        controller.setServiceTierOptions(options, null)
        assertFalse(controller.uiState.value.actionBarState.hasFastTier)
        controller.setServiceTierOptions(options, "fast")
        assertTrue(controller.uiState.value.actionBarState.hasFastTier)
        controller.setServiceTierOptions(options, "")
        assertFalse(controller.uiState.value.actionBarState.hasFastTier)
        controller.setServiceTierOptions(emptyList(), "fast")
        assertFalse(controller.uiState.value.actionBarState.hasFastTier)
        controller.setServiceTierOptions(listOf(
            app.codexremote.android.presentation.composer.ComposerOption("priority", "Fast")
        ), "priority")
        assertTrue(controller.uiState.value.actionBarState.hasFastTier)
    }

    @Test
    fun testEmptyDraftState() {
        val state = calculateComposerActionBarUiState(
            turnRunning = false,
            draftText = "",
            attachmentsCount = 0,
            awaitingAttachments = false,
            isExpanded = false,
            modelLabel = "5.3 Thinking",
            hasFastTier = false
        )
        assertFalse("Cannot send on empty draft", state.canSend)
        assertFalse("Turn is not running", state.isTurnRunning)
        assertFalse("Not awaiting attachments", state.isAwaitingAttachments)
        assertFalse("Collapsed on empty draft", state.isExpanded)
        assertEquals("Send", state.sendButtonContentDescription)
        assertEquals("5.3 Thinking", state.modelLabel)
        assertEquals("Model and reasoning effort, 5.3 Thinking", state.modelContentDescription)
        assertFalse(state.hasFastTier)
    }

    @Test
    fun testDraftWithTextCanSend() {
        val state = calculateComposerActionBarUiState(
            turnRunning = false,
            draftText = "Hello Codex",
            attachmentsCount = 0,
            awaitingAttachments = false,
            isExpanded = true,
            modelLabel = "5.3 Thinking Extra High",
            hasFastTier = true
        )
        assertTrue("Can send when draft has text", state.canSend)
        assertFalse(state.isTurnRunning)
        assertTrue(state.isExpanded)
        assertEquals("Send", state.sendButtonContentDescription)
        assertEquals("5.3 Thinking Extra High", state.modelLabel)
        assertTrue(state.hasFastTier)
    }

    @Test
    fun testAwaitingAttachmentsBlocksSend() {
        val state = calculateComposerActionBarUiState(
            turnRunning = false,
            draftText = "Look at this image",
            attachmentsCount = 1,
            awaitingAttachments = true,
            isExpanded = true,
            modelLabel = "5.3 Thinking",
            hasFastTier = false
        )
        assertFalse("Send blocked while attachments are uploading", state.canSend)
        assertTrue("Awaiting attachments flag is set", state.isAwaitingAttachments)
        assertEquals("Send unavailable until attachments are ready", state.sendButtonContentDescription)
    }

    @Test
    fun testTurnRunningAllowsStop() {
        val state = calculateComposerActionBarUiState(
            turnRunning = true,
            draftText = "",
            attachmentsCount = 0,
            awaitingAttachments = false,
            isExpanded = true,
            modelLabel = "5.3 Thinking",
            hasFastTier = false
        )
        assertTrue("Can interact with button when turn is running to stop it", state.canSend)
        assertTrue("Turn running flag is set", state.isTurnRunning)
        assertEquals("Stop response", state.sendButtonContentDescription)
    }

    @Test
    fun testTurnRunningOverridesAwaitingAttachments() {
        val state = calculateComposerActionBarUiState(
            turnRunning = true,
            draftText = "waiting upload",
            attachmentsCount = 1,
            awaitingAttachments = true,
            isExpanded = true,
            modelLabel = "5.3 Thinking",
            hasFastTier = false
        )
        assertTrue("Can click stop even if attachments were pending", state.canSend)
        assertTrue(state.isTurnRunning)
        assertEquals("Stop response", state.sendButtonContentDescription)
    }

    @Test
    fun testReadyAttachmentsWithoutTextCanSend() {
        val state = calculateComposerActionBarUiState(
            turnRunning = false,
            draftText = "",
            attachmentsCount = 1,
            awaitingAttachments = false,
            isExpanded = true,
            modelLabel = "5.3 Thinking",
            hasFastTier = false
        )
        assertTrue("Can send image only without text draft", state.canSend)
        assertEquals("Send", state.sendButtonContentDescription)
    }

    @Test
    fun testWhitespaceOnlyDraftCannotSend() {
        val state = calculateComposerActionBarUiState(
            turnRunning = false,
            draftText = "   \n\t  ",
            attachmentsCount = 0,
            awaitingAttachments = false,
            isExpanded = false,
            modelLabel = "5.3 Thinking",
            hasFastTier = false
        )
        assertFalse("Whitespace draft alone cannot send", state.canSend)
    }

    @Test
    fun testBlankModelLabelFallback() {
        val state = calculateComposerActionBarUiState(
            turnRunning = false,
            draftText = "",
            attachmentsCount = 0,
            awaitingAttachments = false,
            isExpanded = false,
            modelLabel = "",
            hasFastTier = false
        )
        assertEquals("Model", state.modelLabel)
        assertEquals("Model and reasoning effort", state.modelContentDescription)
    }

    @Test
    fun testConciseModelLabelFormatting() {
        assertEquals("6 Astra Medium", formatConciseModelLabel("GPT-6-Astra (Medium)"))
        assertEquals("6 astra Medium", formatConciseModelLabel("gpt-6-astra (Medium)"))
        assertEquals("6 Astra Medium", formatConciseModelLabel("GPT-6-Astra", "Medium"))
        assertEquals("6 Astra", formatConciseModelLabel("GPT-6-Astra", "Standard"))
        assertEquals("6 Astra", formatConciseModelLabel("GPT-6-Astra"))
        assertEquals("4o", formatConciseModelLabel("GPT-4o"))
        assertEquals("4o mini", formatConciseModelLabel("gpt-4o-mini"))
        assertEquals("5.3 Codex High", formatConciseModelLabel("GPT-5.3-Codex", "High"))
        assertEquals("Claude 3.7 Sonnet Extra High", formatConciseModelLabel("Claude-3.7-Sonnet", "Extra High"))
        assertEquals("5.3 Thinking", formatConciseModelLabel("5.3 Thinking"))
        assertEquals("6 Astra Low", formatConciseModelLabel("  GPT-6-Astra   (Low)  "))
        assertEquals("", formatConciseModelLabel(""))
    }

    @Test
    fun testCalculateComposerActionBarUiStateWithConciseModelAndPreservedAccessibilityDescription() {
        val state = calculateComposerActionBarUiState(
            turnRunning = false,
            draftText = "test",
            attachmentsCount = 0,
            awaitingAttachments = false,
            isExpanded = true,
            modelLabel = "GPT-6-Astra (Medium)",
            hasFastTier = false
        )
        assertEquals("6 Astra Medium", state.modelLabel)
        assertEquals("Model and reasoning effort, GPT-6-Astra (Medium)", state.modelContentDescription)
    }

    @Test
    fun testPermissionOptionsExposureAndSemantics() {
        val stateWithPermission = calculateComposerActionBarUiState(
            turnRunning = false,
            draftText = "",
            attachmentsCount = 0,
            awaitingAttachments = false,
            isExpanded = true,
            modelLabel = "Model",
            hasFastTier = false,
            hasPermissionOptions = true,
            permissionLabel = "Ask for approval"
        )
        assertTrue(stateWithPermission.hasPermissionOptions)
        assertEquals("Ask for approval", stateWithPermission.permissionLabel)
        assertEquals("Permissions, Ask for approval", stateWithPermission.permissionContentDescription)

        val stateWithoutPermission = calculateComposerActionBarUiState(
            turnRunning = false,
            draftText = "",
            attachmentsCount = 0,
            awaitingAttachments = false,
            isExpanded = true,
            modelLabel = "Model",
            hasFastTier = false,
            hasPermissionOptions = false
        )
        assertFalse(stateWithoutPermission.hasPermissionOptions)
        assertEquals("Permissions", stateWithoutPermission.permissionContentDescription)
    }

    @Test
    fun testComposerUiStateConciseModelAndPermissionExposure() {
        val uiState = ComposerUiState(
            modelOptions = listOf(ComposerOption("gpt-6-astra", "GPT-6-Astra")),
            selectedModelId = "gpt-6-astra",
            effortOptions = listOf(ComposerOption("medium", "Medium")),
            selectedEffortId = "medium",
            permissionOptions = listOf(ComposerOption("workspace", "Ask for approval")),
            selectedPermissionId = "workspace"
        )
        assertEquals("GPT-6-Astra (Medium)", uiState.fullModelLabel)
        assertEquals("6 Astra Medium", uiState.conciseModelLabel)
        assertEquals("6 Astra Medium", uiState.actionBarState.modelLabel)
        assertEquals("Model and reasoning effort, GPT-6-Astra (Medium)", uiState.actionBarState.modelContentDescription)
        assertTrue(uiState.actionBarState.hasPermissionOptions)
        assertEquals("Ask for approval", uiState.actionBarState.permissionLabel)
        assertEquals("Permissions, Ask for approval", uiState.actionBarState.permissionContentDescription)
    }

    @Test
    fun testCollapsedVersusExpandedStateSemantics() {
        val collapsed = ComposerUiState()
        assertFalse(collapsed.actionBarState.isExpanded)

        val expandedWithText = ComposerUiState().copy(textFieldValue = androidx.compose.ui.text.input.TextFieldValue("draft"))
        assertTrue(expandedWithText.actionBarState.isExpanded)

        val expandedWithAttachment = ComposerUiState().copy(
            attachments = listOf(app.codexremote.android.compose.ImageAttachmentUiState("1", "pic.png"))
        )
        assertTrue(expandedWithAttachment.actionBarState.isExpanded)

        val expandedRunning = ComposerUiState().copy(isTurnRunning = true)
        assertTrue(expandedRunning.actionBarState.isExpanded)
    }
}
