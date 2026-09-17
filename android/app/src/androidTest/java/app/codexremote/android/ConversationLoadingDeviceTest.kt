@file:Suppress("DEPRECATION")
package app.codexremote.android

import android.test.InstrumentationTestCase
import android.content.Context
import android.graphics.Bitmap
import app.codexremote.android.presentation.conversation.ConversationController
import app.codexremote.android.ui.conversation.ConversationScreen

class ConversationLoadingDeviceTest : InstrumentationTestCase() {
    private fun capture(name: String) {
        instrumentation.waitForIdleSync()
        android.os.SystemClock.sleep(200)
        instrumentation.uiAutomation.takeScreenshot()?.let { image ->
            instrumentation.targetContext.openFileOutput(name, Context.MODE_PRIVATE).use { image.compress(Bitmap.CompressFormat.PNG, 100, it) }
            image.recycle()
        }
    }
    fun testLoadingErrorRetryAndLoadedHistory() {
        var retries = 0
        lateinit var controller: ConversationController
        controller = ConversationController(onRetryLoading = { retries++; controller.beginThreadLoad() })
        instrumentation.runOnMainSync { controller.beginThreadLoad(); controller.setTimelineItems(emptyList(), false) }
        val activity = instrumentation.composeFixture { ConversationScreen(controller, {}) }
        try {
            instrumentation.awaitUiText("Loading conversation")
            capture("qa-conversation-loading.png")
            instrumentation.runOnMainSync { controller.failThreadLoad("Couldn't load this conversation. Please try again.") }
            instrumentation.awaitUiText("Couldn't load this conversation")
            instrumentation.awaitUiText("Chat")
            capture("qa-conversation-load-error.png")
            instrumentation.clickUi("Retry")
            instrumentation.awaitUiText("Loading conversation")
            assertEquals(1, retries)
            instrumentation.runOnMainSync {
                controller.finishThreadLoad()
                controller.setTimelineItems(listOf(TimelineItem("loaded", "", "HISTORY_LOADED", TimelineItem.Kind.ASSISTANT)), false)
            }
            instrumentation.awaitUiText("HISTORY_LOADED")
            instrumentation.awaitUi("Loading feedback removed") { it.findUiText("Loading conversation").isEmpty() }
        } finally { instrumentation.runOnMainSync { activity.finish() } }
    }
}
