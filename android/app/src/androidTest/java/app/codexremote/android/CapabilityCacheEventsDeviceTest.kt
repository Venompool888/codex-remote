@file:Suppress("DEPRECATION")
package app.codexremote.android

import android.content.Intent
import android.test.InstrumentationTestCase
import org.json.JSONObject

/** Exercises the actual Activity event handlers; isolated scopes never connect to a host. */
class CapabilityCacheEventsDeviceTest : InstrumentationTestCase() {
    fun testRemoteEventsInvalidateTheirHostEvenWhenItIsNotSelected() {
        val activity = instrumentation.startActivitySync(Intent(instrumentation.targetContext, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        val a = CapabilityCacheScope("https://catalog-event-a.invalid", "fixture-device", "/work")
        val b = a.copy(server = "https://catalog-event-b.invalid")
        val cases = mutableListOf<Triple<String, Boolean, Boolean>>()
        var unrelatedRetained = false
        var staleWriteRejected = true
        try {
            instrumentation.runOnMainSync {
                val cache = MainActivity::class.java.getDeclaredField("capabilityPresentationCache")
                    .apply { isAccessible = true }.get(activity) as CapabilityPresentationCache
                val message = MainActivity::class.java.getDeclaredMethod("onMessage", String::class.java, JSONObject::class.java)
                    .apply { isAccessible = true }
                val disconnect = MainActivity::class.java.getDeclaredMethod("onDisconnected", String::class.java, String::class.java)
                    .apply { isAccessible = true }
                cache.put(a, "A", cache.generation)
                cache.put(b, "B", cache.generation)
                message.invoke(activity, a.server, JSONObject().put("type", "codex_event").put("method", "thread/name/updated"))
                unrelatedRetained = cache.get(a) == "A" && cache.get(b) == "B"
                for (event in listOf("skills/changed", "plugin/list/changed", "app/list/updated", "host_session_changed", "disconnect")) {
                    cache.put(a, "A", cache.generation)
                    cache.put(b, "B", cache.generation)
                    val pendingGeneration = cache.generation
                    when (event) {
                        "disconnect" -> disconnect.invoke(activity, a.server, "Fixture disconnect")
                        "host_session_changed" -> message.invoke(activity, a.server, JSONObject().put("type", event))
                        else -> message.invoke(activity, a.server, JSONObject().put("type", "codex_event").put("method", event))
                    }
                    cases += Triple(event, cache.get(a) == null, cache.get(b) == "B")
                    staleWriteRejected = staleWriteRejected && !cache.put(a, "late", pendingGeneration)
                }
            }
            assertTrue("Unrelated task metadata must not clear capability snapshots", unrelatedRetained)
            for ((event, invalidated, isolated) in cases) {
                assertTrue("$event removes its host snapshot", invalidated)
                assertTrue("$event retains another host snapshot", isolated)
            }
            assertTrue("Late response cannot refill an invalidated snapshot", staleWriteRejected)
        } finally { instrumentation.runOnMainSync { activity.finish() } }
    }
}
