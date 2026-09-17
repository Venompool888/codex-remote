package app.codexremote.android

import org.junit.Assert.assertEquals
import org.junit.Test

class ConversationReconciliationTest {
    private val question = TimelineItem("q", "You", "Hello", TimelineItem.Kind.USER)
    private val answer = TimelineItem("a", "Codex", "First", TimelineItem.Kind.ASSISTANT, active = true)

    @Test fun streamingKeepsEarlierRowsAndReplacesOnlyChangingAnswer() {
        assertEquals(listOf(0, null), reusableTimelineRows(listOf(question, answer), listOf(question, answer.copy(text = "First paragraph"))))
    }

    @Test fun replayOfSameSnapshotRetainsAllViews() {
        val snapshot = listOf(question, answer)
        assertEquals(listOf(0, 1), reusableTimelineRows(snapshot, snapshot.map { it.copy() }))
    }

    @Test fun completionReplacesActiveRowSoFinalControlsAppear() {
        assertEquals(listOf(0, null), reusableTimelineRows(listOf(question, answer), listOf(question, answer.copy(active = false))))
    }

    @Test fun insertedHistoryKeepsExistingRowsByIdentityNotPosition() {
        val earlier = question.copy(id = "earlier", text = "An earlier question")
        assertEquals(listOf(null, 0, 1), reusableTimelineRows(listOf(question, answer), listOf(earlier, question, answer)))
    }

    @Test fun sameIdWithDifferentRoleIsNotReused() {
        assertEquals(listOf(null), reusableTimelineRows(listOf(question), listOf(question.copy(kind = TimelineItem.Kind.COMMENTARY))))
    }
}
