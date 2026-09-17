package app.codexremote.android

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ConnectionDeletionTest {
    private val projects = listOf(
        RemoteProject("a", "API", "A", "https://a.example", "/api"),
        RemoteProject("b", "Web", "A", "https://a.example/", "/web"),
        RemoteProject("c", "API", "B", "https://b.example", "/api"),
        RemoteProject("d", "Local", "C", "http://localhost:8787", "/local"),
    )

    @Test fun singleHostRemovesEveryRelatedProjectAndPreservesOtherHosts() {
        assertEquals(projects.drop(2), RemoteProjectStore.withoutConnections(projects, setOf(" https://a.example/ ")))
    }

    @Test fun batchDeletionIsIdempotentAndCanRemoveAllConnections() {
        val targets = setOf("https://a.example", "https://b.example", "http://localhost:8787")
        val remaining = RemoteProjectStore.withoutConnections(projects, targets)
        assertTrue(remaining.isEmpty())
        assertEquals(remaining, RemoteProjectStore.withoutConnections(remaining, targets))
    }

    @Test fun EmptyAndUnknownSelectionPreserveProjects() {
        assertEquals(projects, RemoteProjectStore.withoutConnections(projects, emptySet()))
        assertEquals(projects, RemoteProjectStore.withoutConnections(projects, setOf("", "https://unknown.example")))
    }
}
