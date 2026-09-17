package app.codexremote.android

import org.junit.Assert.*
import org.junit.Test

class MonitorConnectionsTest {
    @Test fun removedHostIsInvalidBeforeDisconnectCallbackAndCannotAffectReplacement() {
        val pool = MonitorConnections<Any>()
        val first = pool.reconcile(mapOf("host" to "old"), { Any() }, { error("unexpected close") }).getValue("host")
        var closed = 0
        val second = pool.reconcile(mapOf("host" to "rotated"), { Any() }, {
            assertSame(first, it)
            assertFalse(pool.isCurrent("host", it))
            closed++
        }).getValue("host")
        assertEquals(1, closed)
        assertFalse(pool.isCurrent("host", first))
        assertTrue(pool.isCurrent("host", second))
        pool.reconcile(emptyMap(), { error("unexpected create") }, {
            assertSame(second, it)
            assertFalse(pool.isCurrent("host", it))
            closed++
        })
        assertEquals(2, closed)
    }

    @Test fun unchangedHostIsReusedAndOtherHostOwnershipIsIndependent() {
        val pool = MonitorConnections<Any>()
        val added = pool.reconcile(mapOf("a" to "one", "b" to "two"), { Any() }, { error("unexpected close") })
        assertTrue(pool.reconcile(mapOf("a" to "one", "b" to "two"), { error("unexpected create") }, { error("unexpected close") }).isEmpty())
        pool.reconcile(mapOf("b" to "two"), { error("unexpected create") }, { assertSame(added["a"], it) })
        assertFalse(pool.isCurrent("a", added.getValue("a")))
        assertTrue(pool.isCurrent("b", added.getValue("b")))
        assertFalse(pool.isCurrent("a", added.getValue("b")))
    }

    @Test fun shutdownInvalidatesAllClientsBeforeAnyCloseCallback() {
        val pool = MonitorConnections<Any>()
        val added = pool.reconcile(mapOf("a" to "one", "b" to "two"), { Any() }, {})
        var closed = 0
        pool.closeAll {
            added.forEach { (server, client) -> assertFalse(pool.isCurrent(server, client)) }
            closed++
        }
        assertEquals(2, closed)
        pool.closeAll { fail("already closed") }
    }
}
