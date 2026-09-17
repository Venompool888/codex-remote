package app.codexremote.android

import org.junit.Assert.*
import org.junit.Test

class NavigationRequestsTest {
    @Test fun switchingAwayAndBackDoesNotAcceptOldResponse() {
        val requests = NavigationRequests()
        val old = requests.capture("host-a", "task-a")
        requests.invalidate()
        requests.invalidate()
        val latest = requests.capture("host-a", "task-a")
        assertFalse(requests.accepts(old, "host-a", "task-a"))
        assertTrue(requests.accepts(latest, "host-a", "task-a"))
    }
    @Test fun identicalTaskIdsOnDifferentHostsDoNotShareResponses() {
        val requests = NavigationRequests()
        val ticket = requests.capture("host-a", "task-a")
        assertFalse(requests.accepts(ticket, "host-b", "task-a"))
        assertFalse(requests.accepts(ticket, "host-a", "task-b"))
        assertTrue(requests.accepts(ticket, "host-a", "task-a"))
    }
}
