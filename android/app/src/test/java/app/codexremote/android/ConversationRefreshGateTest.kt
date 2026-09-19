package app.codexremote.android

import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ConversationRefreshGateTest {
    @Test fun rapidSameThreadReconnectLetsReplacementRefreshCompleteFirst() {
        val gate = ConversationRefreshGate()
        val disconnectedRead = gate.begin("server-a", "thread-a")!!
        assertNull(gate.begin("server-a", "thread-a"))

        gate.connectionChanged("server-a")
        val reconnectedRead = gate.begin("server-a", "thread-a")
        assertNotNull(reconnectedRead)

        assertFalse(gate.complete(disconnectedRead))
        assertTrue(gate.isCurrent(reconnectedRead!!))
        assertTrue(gate.complete(reconnectedRead))
        assertFalse(gate.consumeFollowUp())
    }

    @Test fun staleCallbackCannotReleaseNewerRequestOnSameConnection() {
        val gate = ConversationRefreshGate()
        val first = gate.begin("server-a", "thread-a")!!
        gate.reset()
        val second = gate.begin("server-a", "thread-a")!!

        assertFalse(gate.complete(first))
        assertTrue(gate.isCurrent(second))
        assertNull(gate.begin("server-a", "thread-a"))
        assertTrue(gate.complete(second))
        assertTrue(gate.consumeFollowUp())
    }

    @Test fun disconnectingAnotherHostDoesNotInvalidateActiveRefresh() {
        val gate = ConversationRefreshGate()
        val active = gate.begin("server-a", "thread-a")!!

        gate.connectionChanged("server-b")

        assertTrue(gate.isCurrent(active))
        assertTrue(gate.complete(active))
    }
}
