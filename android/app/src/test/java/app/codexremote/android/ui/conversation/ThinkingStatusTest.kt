package app.codexremote.android.ui.conversation

import app.codexremote.android.TimelineItem
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ThinkingStatusTest {
    @Test fun proseOnlyWorkStillNeedsTheThinkingIndicator() {
        val prose = TimelineItem("progress", "Codex", "Checking", TimelineItem.Kind.COMMENTARY, active = true)
        val group = TimelineItem("group", "Working", "", TimelineItem.Kind.ACTIVITY_GROUP,
            active = true, children = listOf(prose))
        assertFalse(hasActiveStatus(listOf(group)))
    }

    @Test fun visibleRunningToolSuppliesItsOwnStatus() {
        val tool = TimelineItem("tool", "Search source", "", TimelineItem.Kind.COMMAND, active = true)
        val group = TimelineItem("group", "Working", "", TimelineItem.Kind.ACTIVITY_GROUP,
            active = true, children = listOf(tool))
        assertTrue(hasActiveStatus(listOf(group)))
        assertTrue(hasActiveStatus(listOf(tool)))
    }

    @Test fun completedHistoryCannotHideTheNextTurnsThinkingIndicator() {
        val stale = TimelineItem("old-tool", "Running command", "", TimelineItem.Kind.COMMAND, phase = "running")
        val completed = TimelineItem("old-group", "Completed", "", TimelineItem.Kind.ACTIVITY_GROUP,
            active = false, children = listOf(stale))
        val next = TimelineItem("new-user", "You", "Next question", TimelineItem.Kind.USER)
        assertFalse(hasActiveStatus(listOf(completed, next)))
    }
}
