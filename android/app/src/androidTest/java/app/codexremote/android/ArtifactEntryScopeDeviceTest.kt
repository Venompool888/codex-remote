@file:Suppress("DEPRECATION")
package app.codexremote.android

import android.content.Intent
import android.test.InstrumentationTestCase
import org.json.JSONObject
import java.io.File

class ArtifactEntryScopeDeviceTest : InstrumentationTestCase() {
    fun testStaleScopeReturnsBeforeCreatingDownloadOrRequest() {
        val activity = instrumentation.startActivitySync(Intent(instrumentation.targetContext, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        var guardCalls = 0
        var unchanged = false
        val events = mutableListOf<ArtifactDownloadEvent>()
        try {
            instrumentation.runOnMainSync {
                val directory = File(activity.filesDir, "artifacts")
                val before = directory.list()?.toSet().orEmpty()
                ArtifactDownloads(activity) { events.add(it) }.download("https://scope-fixture.invalid", "fixture-not-a-credential",
                    JSONObject().put("id", "a".repeat(64)).put("sha256", "b".repeat(64))
                        .put("size", 1).put("name", "scope-proof.txt"), stillCurrent = { guardCalls++; false })
                unchanged = before == directory.list()?.toSet().orEmpty()
            }
            assertTrue("Stale scope cannot present download UI", events.isEmpty())
            assertEquals("Entry checks the current scope before starting", 1, guardCalls)
            assertTrue("No destination or partial file created for stale scope", unchanged)
        } finally { instrumentation.runOnMainSync { activity.finish() } }
    }
}
