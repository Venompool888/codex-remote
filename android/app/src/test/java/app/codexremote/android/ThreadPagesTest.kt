package app.codexremote.android

import org.junit.Assert.*
import org.junit.Test

class ThreadPagesTest {
    private fun thread(id: String, title: String = id) = RemoteThread(id, title, "project", "idle", 1, false)
    @Test fun paginationMergesWithoutDuplicatesAndRetainsNewerMetadata() {
        val pages = ThreadPages()
        val first = pages.begin("a", false)!!
        pages.finish(first, "page2")
        val second = pages.begin("a", true)!!
        assertEquals("page2", second.cursor)
        assertNull(pages.begin("a", true))
        val merged = pages.merge(second, listOf(thread("1", "new")), listOf(thread("1", "old"), thread("2")))
        assertEquals(listOf("1", "2"), merged.map { it.id })
        assertEquals("new", merged.first().title)
        pages.finish(second, null)
        assertFalse(pages.hasMore("a"))
        assertNull(pages.begin("a", true))
    }
    @Test fun refreshSupersedesOlderPageAndHostsRemainIndependent() {
        val pages = ThreadPages()
        pages.finish(pages.begin("a", false)!!, "a2")
        pages.finish(pages.begin("b", false)!!, "b2")
        val stale = pages.begin("a", true)!!
        val fresh = pages.begin("a", false)!!
        assertFalse(pages.accepts(stale))
        pages.finish(stale, "bad")
        pages.fail(stale)
        assertTrue(pages.loading("a"))
        assertFalse(pages.failed("a"))
        assertEquals(listOf("3"), pages.merge(fresh, listOf(thread("1")), listOf(thread("3"))).map { it.id })
        pages.finish(fresh, null)
        assertFalse(pages.hasMore("a"))
        assertEquals("b2", pages.begin("b", true)!!.cursor)
    }
    @Test fun failureRetriesSameCursorAndRemovalRejectsOldResponses() {
        val pages = ThreadPages()
        pages.finish(pages.begin("a", false)!!, "cursor")
        val failed = pages.begin("a", true)!!
        pages.fail(failed)
        assertTrue(pages.failed("a"))
        assertFalse(pages.loading("a"))
        val retry = pages.begin("a", true)!!
        assertEquals("cursor", retry.cursor)
        pages.remove("a")
        val replacement = pages.begin("a", false)!!
        assertFalse(pages.accepts(retry))
        assertTrue(pages.accepts(replacement))
    }
    @Test fun repeatedCursorEndsPaginationInsteadOfLooping() {
        val pages = ThreadPages()
        pages.finish(pages.begin("a", false)!!, "same")
        pages.finish(pages.begin("a", true)!!, "same")
        assertFalse(pages.hasMore("a"))
    }
    @Test fun retryOfFailedRefreshDoesNotSkipToCachedOlderCursor() {
        val pages = ThreadPages()
        pages.finish(pages.begin("a", false)!!, "old-cursor")
        val refresh = pages.begin("a", false)!!
        assertFalse(pages.requestOlder("a"))
        pages.fail(refresh)
        assertTrue(pages.hasMore("a"))
        assertFalse(pages.requestOlder("a"))
        val retry = pages.begin("a", pages.requestOlder("a"))!!
        assertNull(retry.cursor)
        pages.finish(retry, "new-cursor")
        assertTrue(pages.requestOlder("a"))
        val older = pages.begin("a", true)!!
        pages.fail(older)
        assertTrue(pages.requestOlder("a"))
        assertEquals("new-cursor", pages.begin("a", pages.requestOlder("a"))!!.cursor)
    }

}
