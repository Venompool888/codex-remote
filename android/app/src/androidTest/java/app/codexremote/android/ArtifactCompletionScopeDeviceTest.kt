@file:Suppress("DEPRECATION")
package app.codexremote.android

import android.content.Intent
import android.test.InstrumentationTestCase
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.json.JSONObject
import java.io.File
import java.security.MessageDigest
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** Gates the production asynchronous download without contacting a fixture server. */
class ArtifactCompletionScopeDeviceTest : InstrumentationTestCase() {
    fun testLateSuccessDoesNotPresentInAnotherTask() = exercise(200, true)
    fun testLateFailureDoesNotPresentInAnotherTask() = exercise(500, true)
    fun testCurrentSuccessPresentsVerifiedResult() = exercise(200, false)
    fun testOldOpenButtonRechecksTaskBeforeGrantingUri() = exercise(200, false, "Open")
    fun testOldShareButtonRechecksTaskBeforeGrantingUri() = exercise(200, false, "Share")
    fun testHistoricalAttachmentUsesVerifiedDownloadAndOriginalScope() = exercise(200, false, attachment = true)
    fun testHistoricalAttachmentLateSuccessCannotCrossTasks() = exercise(200, true, attachment = true)
    fun testHistoricalTextOpensInAppAfterVerification() = exercise(200, false, attachment = true, textPreview = true)

    private fun exercise(status: Int, navigate: Boolean, staleAction: String? = null, attachment: Boolean = false, textPreview: Boolean = false) {
        val activity = instrumentation.startActivitySync(Intent(instrumentation.targetContext, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val completed = CountDownLatch(1)
        val navigation = NavigationRequests()
        val server = "https://completion-scope-fixture.invalid"
        val id = if (attachment) "12345678-1234-1234-1234-123456789abc" else "c".repeat(64)
        val ticket = navigation.capture(server, "task-a")
        val payload = "artifact scope proof".toByteArray()
        fun sha(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
        val scope = sha("$server\u0000$id".toByteArray())
        var calls = 0
        val events = mutableListOf<ArtifactDownloadEvent>()
        var preview: Pair<String, String>? = null
        try {
            instrumentation.runOnMainSync {
                val downloads = ArtifactDownloads(activity, { events.add(it) }, { name, text -> preview = name to text })
                val client = OkHttpClient.Builder().addInterceptor { chain ->
                    assertEquals(if (attachment) "/v2/attachments/$id/download" else "/v2/artifacts/$id", chain.request().url.encodedPath)
                    entered.countDown()
                    check(release.await(10, TimeUnit.SECONDS)) { "Fixture gate timed out" }
                    Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1)
                        .code(status).message("Fixture").body(payload.toResponseBody()).build()
                }.build()
                ArtifactDownloads::class.java.getDeclaredField("http").apply { isAccessible = true }.set(downloads, client)
                downloads.download(server, "fixture-not-a-credential", JSONObject().put("id", id)
                    .put("size", payload.size).put("sha256", sha(payload)).put("name", "completion-scope-proof.txt")
                    .put("mimeType", "text/plain"), attachmentId = if (attachment) id else null, stillCurrent = {
                        calls++
                        if (calls > 1) completed.countDown()
                        navigation.accepts(ticket, server, "task-a")
                    })
            }
            assertTrue("Production worker reached the gated response", entered.await(10, TimeUnit.SECONDS))
            if (navigate) instrumentation.runOnMainSync { navigation.invalidate() }
            release.countDown()
            assertTrue("Completion rechecks the navigation ticket", completed.await(10, TimeUnit.SECONDS))
            instrumentation.waitForIdleSync()
            var success: ArtifactDownloadEvent.Completed? = null
            instrumentation.runOnMainSync {
                success = events.filterIsInstance<ArtifactDownloadEvent.Completed>().singleOrNull()
                assertEquals("Only the current task can present a verified download", !navigate, success != null)
                assertTrue("A stale failure cannot present retry in another task", events.none { it is ArtifactDownloadEvent.Failed })
                assertEquals("Entry and completion both check scope", 2, calls)
                val started = events.filterIsInstance<ArtifactDownloadEvent.Started>().single()
                assertTrue("All download events retain one attempt identity", events.all { it.key == started.key })
                if (navigate) assertTrue(events.last() is ArtifactDownloadEvent.Dismissed)
            }
            if (staleAction != null) {
                instrumentation.runOnMainSync {
                    navigation.invalidate()
                    if (staleAction == "Open") success!!.open() else success!!.share()
                    assertEquals("Button action checks current task before a URI grant", 3, calls)
                }
                instrumentation.waitForIdleSync()
                assertEquals("No external chooser is launched", activity.packageName,
                    instrumentation.awaitUi("Activity remains foreground") { it.packageName?.toString() == activity.packageName }.packageName.toString())
            }
            if (textPreview) instrumentation.runOnMainSync {
                success!!.open()
                assertEquals("completion-scope-proof.txt" to payload.toString(Charsets.UTF_8), preview)
                assertEquals(3, calls)
            }
            if (staleAction != null) assertNull("Stale actions cannot show text", preview)
        } finally {
            release.countDown()
            instrumentation.runOnMainSync { activity.finish() }
            File(activity.filesDir, "artifacts/$scope").deleteRecursively()
            File(activity.filesDir, "artifacts").listFiles().orEmpty()
                .filter { it.name.startsWith("$scope-") && it.extension == "part" }.forEach { it.delete() }
        }
    }
}
