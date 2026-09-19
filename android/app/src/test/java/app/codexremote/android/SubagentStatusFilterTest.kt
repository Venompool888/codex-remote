package app.codexremote.android

import app.codexremote.android.ui.conversation.SubagentStatusFilter
import org.junit.Assert.*
import org.junit.Test

class SubagentStatusFilterTest {
    @Test fun idleAndUnloadedDoNotPretendTheTaskCompleted() {
        listOf("idle", "notLoaded", "shutdown", "unknown", "", "futureState").forEach { state ->
            assertTrue(SubagentStatusFilter.ALL.matches(state))
            assertFalse(SubagentStatusFilter.COMPLETED.matches(state))
            assertFalse(SubagentStatusFilter.RUNNING.matches(state))
            assertFalse(SubagentStatusFilter.NEEDS_ATTENTION.matches(state))
        }
    }

    @Test fun executionAndProblemStatesAreCaseInsensitiveAndDisjoint() {
        val cases = mapOf(
            " Active " to SubagentStatusFilter.RUNNING,
            "pendingInit" to SubagentStatusFilter.RUNNING,
            "inProgress" to SubagentStatusFilter.RUNNING,
            "RUNNING" to SubagentStatusFilter.RUNNING,
            "systemError" to SubagentStatusFilter.NEEDS_ATTENTION,
            "errored" to SubagentStatusFilter.NEEDS_ATTENTION,
            "interrupted" to SubagentStatusFilter.NEEDS_ATTENTION,
            "cancelled" to SubagentStatusFilter.NEEDS_ATTENTION,
            "waitingForInput" to SubagentStatusFilter.NEEDS_ATTENTION,
            "completed" to SubagentStatusFilter.COMPLETED,
        )
        cases.forEach { (state, expected) ->
            assertEquals(listOf(expected), SubagentStatusFilter.entries.filter {
                it != SubagentStatusFilter.ALL && it.matches(state)
            })
        }
    }
}
