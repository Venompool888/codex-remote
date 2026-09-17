package app.codexremote.android

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.nio.file.Files
import java.util.concurrent.Executor

class RemoteImageRepositoryTest {
    private class Fixture {
        var current = true
        var source: RemoteImageSource? = RemoteImageSource("https://host.example", "device-a", "task-a", "test-token", false, false) { current }
        val requests = mutableListOf<Pair<String, JSONObject>>()
        val completions = mutableListOf<(JSONObject?) -> Unit>()
        val results = mutableListOf<RemoteImageContent>()
        val cacheKeys = mutableListOf<String>()
        val root = Files.createTempDirectory("remote-image-test").toFile()
        val repository = RemoteImageRepository(root, { source }, { _, method, params, done ->
            requests.add(method to params); completions.add(done)
        }, Executor { it.run() }, Executor { it.run() }, { null }, { cacheKeys.add(it); null }, { _, _ -> }, { _, _, _ -> })
        fun load(reference: String) = repository.loadImage(reference, "Preview", results::add)
    }
    private fun fixture(test: (Fixture) -> Unit) {
        val fixture = Fixture()
        try { test(fixture) } finally { fixture.root.deleteRecursively() }
    }

    @Test fun missingHostExplainsWithoutReadingOrRequesting() = fixture { f ->
        f.source = null
        f.load("/private/image.png")
        assertTrue(f.requests.isEmpty())
        assertTrue(f.cacheKeys.isEmpty())
        assertNotNull(f.results.single().error)
    }

    @Test fun malformedPrivateReferenceCannotFallThroughToImageRpc() = fixture { f ->
        for (reference in listOf("remote-attachment://bad", "remote-artifact-image://bad")) f.load(reference)
        assertTrue(f.requests.isEmpty())
        assertEquals(2, f.results.size)
        assertTrue(f.results.all { it.bitmap == null && it.share == null && it.save == null })
    }

    @Test fun unsupportedAttachmentPreviewDoesNotAttemptTransfer() = fixture { f ->
        f.load("remote-attachment://12345678-1234-1234-1234-123456789abc-image.png")
        assertEquals("Image preview requires a newer host", f.results.single().error)
        assertTrue(f.requests.isEmpty())
    }

    @Test fun cancelledOrOldTaskCallbacksCannotUpdatePresentation() = fixture { f ->
        val cancel = f.load("/private/a.png")
        cancel()
        f.completions.single()(null)
        assertTrue(f.results.isEmpty())
        f.load("/private/b.png")
        f.current = false
        f.completions.last()(null)
        assertTrue(f.results.isEmpty())
    }

    @Test fun cacheIdentityIncludesDeviceAndTaskBeforeReadingPrivatePath() = fixture { f ->
        f.load("/private/a.png")
        f.source = RemoteImageSource("https://host.example", "device-b", "task-a", "test-token", false, false) { true }
        f.load("/private/a.png")
        f.source = RemoteImageSource("https://host.example", "device-b", "task-b", "test-token", false, false) { true }
        f.load("/private/a.png")
        assertEquals(3, f.cacheKeys.distinct().size)
        assertTrue(f.requests.all { it.first == "host/image/read" && it.second.getString("path") == "/private/a.png" })
    }

    @Test fun artifactImageRequiresOneMatchingArtifactFromOriginalTask() = fixture { f ->
        f.source = RemoteImageSource("https://host.example", "device-a", "task-a", "test-token", false, true) { f.current }
        f.load("remote-artifact-image://" + "a".repeat(64))
        assertEquals("host/artifacts/list", f.requests.single().first)
        assertEquals("task-a", f.requests.single().second.getString("threadId"))
        f.completions.single()(JSONObject().put("artifacts", org.json.JSONArray()))
        assertNull(f.results.single().bitmap)
        assertNull(f.results.single().share)
        assertNull(f.results.single().save)
    }
}
