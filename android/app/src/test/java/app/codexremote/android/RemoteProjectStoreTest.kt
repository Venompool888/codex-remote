package app.codexremote.android

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RemoteProjectStoreTest {
    @Test fun savedOpaqueProjectsPinOnlyTheirOwnConnectionToCompatibleRouting() {
        val opaque = RemoteProject("old", "root", "A", "https://a/", "remote-workspace://" + "a".repeat(64))
        val plain = opaque.copy(id = "new", serverUrl = "https://b", workspace = "/root")
        assertTrue(RemoteProjectStore.requiresOpaqueRouting(listOf(opaque, plain), "https://a"))
        assertTrue(!RemoteProjectStore.requiresOpaqueRouting(listOf(opaque, plain), "https://b"))
        assertTrue(!RemoteProjectStore.requiresOpaqueRouting(listOf(plain), "https://a"))
    }

    @Test fun migratedAliasesCollapseWithoutMergingSameNamesOnDifferentHostsOrPaths() {
        val original = RemoteProject("old", "root", "A", "https://a", "remote-workspace://" + "a".repeat(64))
        val migrated = original.copy(id = "discovered", name = "Custom root")
        val otherPath = original.copy(id = "other-path", workspace = "remote-workspace://" + "b".repeat(64))
        val otherHost = original.copy(id = "other-host", serverUrl = "https://b")
        val projects = listOf(original, migrated, otherPath, otherHost)
        assertEquals(listOf(original, otherPath, otherHost), RemoteProjectStore.distinctWorkspaces(projects, null))
        assertEquals(listOf(migrated, otherPath, otherHost), RemoteProjectStore.distinctWorkspaces(projects, migrated.id))
        assertEquals(setOf("old", "discovered"), RemoteProjectStore.workspaceAliasIds(projects, "old"))
        assertEquals(4, projects.size)
    }

    @Test fun normalizedPathsDeduplicateButOpaqueIdsAreNeverGuessedFromDisplayNames() {
        val a = RemoteProject("one", "root", "A", "https://a/", "/root/")
        val b = a.copy(id = "two", serverUrl = "https://a", workspace = "/root")
        val opaque = b.copy(id = "three", workspace = "remote-workspace://" + "a".repeat(64))
        assertEquals(listOf(a, opaque), RemoteProjectStore.distinctWorkspaces(listOf(a, b, opaque), null))
    }

    @Test fun opaqueDiscoveryUsesNamesWithoutChangingExistingProjectLabelsOrIdentity() {
        val a = "remote-workspace://" + "a".repeat(64)
        val b = "remote-workspace://" + "b".repeat(64)
        val existing = listOf(RemoteProject("one", "My chosen name", "secondary", "https://host.example", a))
        val merged = RemoteProjectStore.mergeDiscoveredProjects(existing, "https://host.example", "secondary",
            listOf(a,b), mapOf(a to "project", b to "project"))
        assertEquals(listOf("My chosen name", "project"), merged.map { it.name })
        assertEquals(listOf(a,b), merged.map { it.workspace })
        assertEquals("Workspace", RemoteProjectStore.mergeDiscoveredProjects(emptyList(), "https://other.example", "other", listOf(a)).single().name)
        assertEquals(merged, RemoteProjectStore.decode(RemoteProjectStore.encode(merged)))
    }
    @Test
    fun projectsRoundTripThroughJson() {
        val projects = listOf(
            RemoteProject("one", "API", "production", "https://host.example", "/srv/api"),
            RemoteProject("two", "Web", "staging", "https://stage.example", "/srv/web"),
        )

        assertEquals(projects, RemoteProjectStore.decode(RemoteProjectStore.encode(projects)))
    }

    @Test
    fun invalidEntriesAreIgnored() {
        assertTrue(RemoteProjectStore.decode("[{\"name\":\"missing fields\"}]").isEmpty())
        assertTrue(RemoteProjectStore.decode("not json").isEmpty())
    }

    @Test
    fun discoveredWorkspacesAreAttachedToTheirHostWithoutDuplicatingExistingProjects() {
        val existing = listOf(RemoteProject("one", "root", "secondary", "https://secondary.example", "/root"))
        var nextId = 1

        val merged = RemoteProjectStore.mergeDiscoveredProjects(
            existing = existing,
            serverUrl = "https://secondary.example/",
            connectionName = "secondary",
            workspaces = listOf("/root/", "/root/unseen-wynges", "/root/voice-chat", "/root/voice-chat"),
            idFactory = { "new-${nextId++}" },
        )

        assertEquals(listOf("root", "unseen-wynges", "voice-chat"), merged.map(RemoteProject::name))
        assertEquals(listOf("one", "new-1", "new-2"), merged.map(RemoteProject::id))
        assertTrue(merged.all { it.serverUrl == "https://secondary.example" })
    }

    @Test
    fun projectsOnTheSameHostAreGroupedIntoOneConnection() {
        val projects = listOf(
            RemoteProject("one", "root", "secondary", "https://secondary.example", "/root"),
            RemoteProject("two", "unseen-wynges", "secondary", "https://secondary.example/", "/opt/unseen-wynges"),
            RemoteProject("three", "local", "local", "http://127.0.0.1:8787", "/srv/local"),
        )

        val connections = RemoteProjectStore.groupConnections(projects)

        assertEquals(listOf("secondary", "local"), connections.map(RemoteConnection::name))
        assertEquals(listOf(2, 1), connections.map { it.projects.size })
        assertEquals("https://secondary.example", connections.first().serverUrl)
    }

    @Test
    fun updatingAConnectionUpdatesEveryRelatedProject() {
        val projects = listOf(
            RemoteProject("one", "root", "secondary", "https://secondary.example", "/root"),
            RemoteProject("two", "web", "secondary", "https://secondary.example", "/srv/web"),
            RemoteProject("three", "local", "local", "http://127.0.0.1:8787", "/srv/local"),
        )

        val updated = RemoteProjectStore.updateConnectionProjects(
            projects,
            oldServerUrl = "https://secondary.example/",
            name = "Singapore",
            serverUrl = "https://sg-new.example/",
        )

        assertEquals(listOf("Singapore", "Singapore", "local"), updated.map(RemoteProject::connectionName))
        assertEquals(
            listOf("https://sg-new.example", "https://sg-new.example", "http://127.0.0.1:8787"),
            updated.map(RemoteProject::serverUrl),
        )
    }
}
