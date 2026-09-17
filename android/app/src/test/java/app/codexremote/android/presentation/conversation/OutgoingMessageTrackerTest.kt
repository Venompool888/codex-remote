package app.codexremote.android.presentation.conversation

import app.codexremote.android.TimelineItem
import org.junit.Assert.*
import org.junit.Test

class OutgoingMessageTrackerTest {
    private val scope = "server\u0000device\u0000thread"
    private val local = TimelineItem("local", "You", "hello", TimelineItem.Kind.USER)
    private val host = local.copy(id = "host-user")
    private fun turns(finished: Boolean = false) = mapOf("turn" to DeliveryTurn(setOf(host.id), finished))
    private fun tracker() = OutgoingMessageTracker().apply { begin(scope, local, emptyList()) }

    @Test fun clickImmediatelyCreatesSendingBubble() {
        val result = tracker().project(scope, emptyList(), emptyMap())
        assertEquals(listOf(local), result.items)
        assertEquals(MessageDeliveryStatus.SENDING, result.statuses[local.id])
    }
    @Test fun acknowledgementKeepsBubbleAndShowsSentWithoutWaitingForSnapshot() {
        val tracker = tracker()
        tracker.confirm(scope, local.id, "turn")
        val result = tracker.project(scope, emptyList(), emptyMap())
        assertEquals(listOf(local), result.items)
        assertEquals(MessageDeliveryStatus.SENT, result.statuses[local.id])
    }
    @Test fun echoBeforeAckReconcilesOnceAndPreservesLocalIdentity() {
        val tracker = tracker()
        val result = tracker.project(scope, listOf(host), turns())
        assertEquals(listOf(local.id), result.items.map { it.id })
        assertEquals(MessageDeliveryStatus.SENT, result.statuses[local.id])
        tracker.reject(scope, local.id)
        assertEquals(1, tracker.project(scope, listOf(host), turns()).items.size)
    }
    @Test fun completionBeforeAckNeverResurrectsReceipt() {
        val tracker = tracker()
        tracker.finish(scope, "turn")
        tracker.confirm(scope, local.id, "turn")
        assertTrue(tracker.project(scope, listOf(host), turns()).statuses.isEmpty())
    }
    @Test fun completedSnapshotHidesReceiptAndStaleSnapshotCannotRestoreIt() {
        val tracker = tracker()
        assertTrue(tracker.project(scope, listOf(host), turns(true)).statuses.isEmpty())
        assertTrue(tracker.project(scope, listOf(host), turns(false)).statuses.isEmpty())
    }
    @Test fun oldIdenticalTextIsNeverMistakenForNewMessage() {
        val tracker = OutgoingMessageTracker()
        tracker.begin(scope, local, listOf(host))
        val result = tracker.project(scope, listOf(host), turns(true))
        assertEquals(listOf(host.id, local.id), result.items.map { it.id })
        assertEquals(MessageDeliveryStatus.SENDING, result.statuses[local.id])
    }
    @Test fun knownTurnAllowsHostNormalizedTextWithoutDuplicate() {
        val tracker = tracker()
        tracker.confirm(scope, local.id, "turn")
        assertEquals(1, tracker.project(scope, listOf(host.copy(text = "normalized")), turns()).items.size)
    }
    @Test fun newChatMigrationAndOtherCredentialStayIsolated() {
        val tracker = tracker()
        val next = "server\u0000device\u0000created"
        tracker.move(scope, next)
        assertTrue(tracker.project(scope, emptyList(), emptyMap()).items.isEmpty())
        assertTrue(tracker.project("server\u0000other-device\u0000created", emptyList(), emptyMap()).items.isEmpty())
        assertEquals(local, tracker.project(next, emptyList(), emptyMap()).items.single())
    }
    @Test fun rejectedUnconfirmedMessageIsRemoved() {
        val tracker = tracker()
        tracker.reject(scope, local.id)
        assertTrue(tracker.project(scope, emptyList(), emptyMap()).items.isEmpty())
    }
    @Test fun executionArrivingFirstStaysAfterUserBubble() {
        val activity = TimelineItem("work", "Working", "", TimelineItem.Kind.ACTIVITY_GROUP)
        assertEquals(listOf(local.id, activity.id), tracker().project(scope, listOf(activity), emptyMap()).items.map { it.id })
    }
    @Test fun laterRepeatedMessageCannotStealExistingAlias() {
        val tracker = tracker()
        tracker.project(scope, listOf(host), turns(true))
        val second = local.copy(id = "second")
        tracker.begin(scope, second, listOf(host))
        val host2 = host.copy(id = "host2")
        val result = tracker.project(scope, listOf(host, host2), turns(true) + ("turn2" to DeliveryTurn(setOf(host2.id), false)))
        assertEquals(listOf(local.id, second.id), result.items.map { it.id })
        assertEquals(mapOf(second.id to MessageDeliveryStatus.SENT), result.statuses)
    }
    @Test fun consecutiveMessagesKeepOrderWhenHistoryHasNotCaughtUp() {
        val tracker = tracker()
        tracker.confirm(scope, local.id, "turn")
        tracker.finish(scope, "turn")
        tracker.begin(scope, local.copy(id = "second", text = "next"), emptyList())
        assertEquals(listOf(local.id, "second"), tracker.project(scope, emptyList(), emptyMap()).items.map { it.id })
    }

}
