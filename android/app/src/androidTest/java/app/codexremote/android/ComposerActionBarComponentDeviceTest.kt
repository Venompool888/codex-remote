@file:Suppress("DEPRECATION")

package app.codexremote.android

import android.content.Intent
import android.os.SystemClock
import android.test.InstrumentationTestCase
import android.graphics.Rect
import android.view.accessibility.AccessibilityNodeInfo
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.platform.ComposeView
import app.codexremote.android.compose.ComposerActionBar
import app.codexremote.android.compose.calculateComposerActionBarUiState
import java.util.concurrent.atomic.AtomicInteger

class ComposerActionBarComponentDeviceTest : InstrumentationTestCase() {
    private fun nodes(): List<AccessibilityNodeInfo> {
        val root = instrumentation.uiAutomation.rootInActiveWindow ?: return emptyList()
        fun descend(node: AccessibilityNodeInfo): List<AccessibilityNodeInfo> {
            node.refresh()
            return listOf(node) + (0 until node.childCount).flatMap { index ->
                node.getChild(index)?.let(::descend) ?: emptyList()
            }
        }
        return descend(root)
    }

    private fun awaitCheck(message: String, test: () -> Boolean) {
        val deadline = SystemClock.uptimeMillis() + 5_000
        while (SystemClock.uptimeMillis() < deadline) {
            if (test()) return
            SystemClock.sleep(100)
        }
        val currentNodes = nodes().joinToString("\n") {
            "desc=${it.contentDescription} text=${it.text} enabled=${it.isEnabled} clickable=${it.isClickable} actions=${it.actionList}"
        }
        throw AssertionError("$message\nCurrent nodes:\n$currentNodes")
    }

    private fun findActionNode(label: String): AccessibilityNodeInfo {
        awaitCheck("Missing node with content description: $label") {
            nodes().any { it.contentDescription?.toString() == label }
        }
        var node = nodes().first { it.contentDescription?.toString() == label }
        while (!node.isClickable) {
            node = node.parent ?: error("No clickable parent for $label")
            node.refresh()
        }
        return node
    }

    fun testShortModelActionWrapsContentAndDoesNotFillMax() {
        val activity = instrumentation.startActivitySync(
            Intent(instrumentation.targetContext, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        ) as MainActivity
        val modelClicks = AtomicInteger()
        try {
            instrumentation.runOnMainSync {
                activity.setContentView(ComposeView(activity).apply {
                    setContent {
                        ComposerActionBar(
                            state = calculateComposerActionBarUiState(
                                turnRunning = false,
                                draftText = "x",
                                attachmentsCount = 0,
                                awaitingAttachments = false,
                                isExpanded = true,
                                modelLabel = "M",
                                hasFastTier = false
                            ),
                            onPlusClick = {},
                            onModelClick = { modelClicks.incrementAndGet() },
                            onSendClick = {}
                        )
                    }
                })
            }
            val modelAction = findActionNode("Model and reasoning effort, M")
            modelAction.performAction(AccessibilityNodeInfo.ACTION_CLICK)
            awaitCheck("Model action callback delivered") { modelClicks.get() == 1 }

            val bounds = Rect().also { modelAction.getBoundsInScreen(it) }
            assertFalse("Model action has visible bounds", bounds.isEmpty)
            val density = activity.resources.displayMetrics.density
            val widthDp = bounds.width() / density
            assertTrue(
                "A one-letter model action must wrap its button; measured width dp=$widthDp",
                widthDp < 150
            )
        } finally {
            instrumentation.runOnMainSync { activity.finish() }
        }
    }

    fun testLongModelLabelWithinBounds() {
        val activity = instrumentation.startActivitySync(
            Intent(instrumentation.targetContext, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        ) as MainActivity
        val modelClicks = AtomicInteger()
        try {
            instrumentation.runOnMainSync {
                activity.setContentView(ComposeView(activity).apply {
                    setContent {
                        ComposerActionBar(
                            state = calculateComposerActionBarUiState(
                                turnRunning = false,
                                draftText = "x",
                                attachmentsCount = 0,
                                awaitingAttachments = false,
                                isExpanded = true,
                                modelLabel = "Very Long Model Label That Truncates Ellipsis Claude 3.7 Sonnet Extra High Thinking",
                                hasFastTier = true
                            ),
                            onPlusClick = {},
                            onModelClick = { modelClicks.incrementAndGet() },
                            onSendClick = {}
                        )
                    }
                })
            }
            val fullDesc = "Model and reasoning effort, Very Long Model Label That Truncates Ellipsis Claude 3.7 Sonnet Extra High Thinking"
            val modelAction = findActionNode(fullDesc)
            modelAction.performAction(AccessibilityNodeInfo.ACTION_CLICK)
            awaitCheck("Long model action callback delivered") { modelClicks.get() == 1 }

            val bounds = Rect().also { modelAction.getBoundsInScreen(it) }
            assertFalse("Model action has visible bounds", bounds.isEmpty)
            val density = activity.resources.displayMetrics.density
            val widthDp = bounds.width() / density
            val screenWidthDp = activity.resources.configuration.screenWidthDp
            // Maximum allocated width for model button is screen width minus left 48dp (plus button) and right 56dp (send button)
            assertTrue("Long model label must not exceed available width: $widthDp vs ${screenWidthDp - 104}", widthDp <= (screenWidthDp - 104) + 1.0f)
        } finally {
            instrumentation.runOnMainSync { activity.finish() }
        }
    }

    fun testActionCallbacksDisabledAndExpandedTransitions() {
        val activity = instrumentation.startActivitySync(
            Intent(instrumentation.targetContext, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        ) as MainActivity
        val state = mutableStateOf(calculateComposerActionBarUiState(false, "", 0, false, false, "Model", false))
        val sendClicks = AtomicInteger()
        val plusClicks = AtomicInteger()
        val modelClicks = AtomicInteger()
        try {
            instrumentation.runOnMainSync {
                activity.setContentView(ComposeView(activity).apply {
                    setContent {
                        ComposerActionBar(
                            state = state.value,
                            onPlusClick = { plusClicks.incrementAndGet() },
                            onModelClick = { modelClicks.incrementAndGet() },
                            onSendClick = { sendClicks.incrementAndGet() }
                        )
                    }
                })
            }

            // 1. Send disabled initially (empty draft)
            val sendDisabled = findActionNode("Send")
            assertFalse(sendDisabled.isEnabled)
            sendDisabled.performAction(AccessibilityNodeInfo.ACTION_CLICK)
            SystemClock.sleep(150)
            assertEquals(0, sendClicks.get())

            // Model label should not be present in collapsed state
            assertFalse(nodes().any { it.contentDescription?.toString()?.startsWith("Model and reasoning") == true })

            // 2. Plus button works and has an accessible touch target
            findActionNode("Composer options").performAction(AccessibilityNodeInfo.ACTION_CLICK)
            awaitCheck("Plus action callback received") { plusClicks.get() == 1 }
            val plusBounds = Rect().also { findActionNode("Composer options").getBoundsInScreen(it) }
            val density = activity.resources.displayMetrics.density
            assertTrue("Plus touch target is at least 48dp wide", plusBounds.width() / density >= 47.5f)
            assertTrue("Plus touch target is at least 48dp tall", plusBounds.height() / density >= 47.5f)

            // 3. Expand and provide text -> Send becomes enabled
            instrumentation.runOnMainSync {
                state.value = calculateComposerActionBarUiState(false, "hello world", 0, false, true, "Model", false)
            }
            awaitCheck("Send button enabled") { findActionNode("Send").isEnabled }
            findActionNode("Send").performAction(AccessibilityNodeInfo.ACTION_CLICK)
            awaitCheck("Send callback invoked once") { sendClicks.get() == 1 }

            // 4. Model button is clickable and has a touch target distinct from plus
            findActionNode("Model and reasoning effort, Model").performAction(AccessibilityNodeInfo.ACTION_CLICK)
            awaitCheck("Model action callback received") { modelClicks.get() == 1 }
            assertEquals("Model action must not invoke plus", 1, plusClicks.get())
            val modelBounds = Rect().also { findActionNode("Model and reasoning effort, Model").getBoundsInScreen(it) }
            assertFalse("Model and plus have distinct touch targets", Rect.intersects(plusBounds, modelBounds))

            // 5. Awaiting attachments blocks send
            instrumentation.runOnMainSync {
                state.value = calculateComposerActionBarUiState(false, "hello", 1, true, true, "Model", false)
            }
            awaitCheck("Send disabled when awaiting attachments") {
                !findActionNode("Send unavailable until attachments are ready").isEnabled
            }
            findActionNode("Send unavailable until attachments are ready").performAction(AccessibilityNodeInfo.ACTION_CLICK)
            SystemClock.sleep(150)
            assertEquals("No new send clicks while awaiting attachments", 1, sendClicks.get())

            // 6. Running turn -> Stop response button enabled and triggers callback
            instrumentation.runOnMainSync {
                state.value = calculateComposerActionBarUiState(true, "", 1, true, true, "Model", false)
            }
            val stopAction = findActionNode("Stop response")
            assertTrue(stopAction.isEnabled)
            stopAction.performAction(AccessibilityNodeInfo.ACTION_CLICK)
            awaitCheck("Stop callback invoked") { sendClicks.get() == 2 }

            // 7. Collapsing hides model label semantics
            instrumentation.runOnMainSync {
                state.value = state.value.copy(isExpanded = false)
            }
            awaitCheck("Collapsed model leaves semantics") {
                nodes().none { it.contentDescription?.toString()?.startsWith("Model and reasoning") == true }
            }
        } finally {
            instrumentation.runOnMainSync { activity.finish() }
        }
    }

    fun testPermissionActionExposedWith48dpTargetAndTriggersCallback() {
        val activity = instrumentation.startActivitySync(
            Intent(instrumentation.targetContext, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        ) as MainActivity
        val permissionClicks = AtomicInteger()
        val plusClicks = AtomicInteger()
        try {
            instrumentation.runOnMainSync {
                activity.setContentView(ComposeView(activity).apply {
                    setContent {
                        ComposerActionBar(
                            state = calculateComposerActionBarUiState(
                                turnRunning = false,
                                draftText = "x",
                                attachmentsCount = 0,
                                awaitingAttachments = false,
                                isExpanded = true,
                                modelLabel = "Model",
                                hasFastTier = false,
                                hasPermissionOptions = true,
                                permissionLabel = "Ask for approval"
                            ),
                            onPlusClick = { plusClicks.incrementAndGet() },
                            onModelClick = {},
                            onSendClick = {},
                            onPermissionClick = { permissionClicks.incrementAndGet() }
                        )
                    }
                })
            }
            val permAction = findActionNode("Permissions, Ask for approval")
            permAction.performAction(AccessibilityNodeInfo.ACTION_CLICK)
            awaitCheck("Permission action callback delivered") { permissionClicks.get() == 1 }
            assertEquals("Plus action not triggered by permission click", 0, plusClicks.get())

            val permBounds = Rect().also { permAction.getBoundsInScreen(it) }
            val density = activity.resources.displayMetrics.density
            assertTrue("Permission touch target width >= 48dp", permBounds.width() / density >= 47.5f)
            assertTrue("Permission touch target height >= 48dp", permBounds.height() / density >= 47.5f)

            val plusBounds = Rect().also { findActionNode("Composer options").getBoundsInScreen(it) }
            assertFalse("Plus and permission do not intersect", Rect.intersects(plusBounds, permBounds))

            val modelBounds = Rect().also { findActionNode("Model and reasoning effort, Model").getBoundsInScreen(it) }
            assertFalse("Permission and model do not intersect", Rect.intersects(permBounds, modelBounds))
        } finally {
            instrumentation.runOnMainSync { activity.finish() }
        }
    }

    fun testPermissionHiddenInCollapsedState() {
        val activity = instrumentation.startActivitySync(
            Intent(instrumentation.targetContext, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        ) as MainActivity
        try {
            instrumentation.runOnMainSync {
                activity.setContentView(ComposeView(activity).apply {
                    setContent {
                        ComposerActionBar(
                            state = calculateComposerActionBarUiState(
                                turnRunning = false,
                                draftText = "",
                                attachmentsCount = 0,
                                awaitingAttachments = false,
                                isExpanded = false,
                                modelLabel = "Model",
                                hasFastTier = false,
                                hasPermissionOptions = true,
                                permissionLabel = "Ask for approval"
                            )
                        )
                    }
                })
            }
            awaitCheck("Collapsed state has plus button") {
                nodes().any { it.contentDescription?.toString() == "Composer options" }
            }
            assertFalse(
                "Permission button must be hidden in collapsed state",
                nodes().any { it.contentDescription?.toString()?.startsWith("Permissions") == true }
            )
        } finally {
            instrumentation.runOnMainSync { activity.finish() }
        }
    }

    fun testNonOverlapping48dpTouchTargets() {
        val activity = instrumentation.startActivitySync(
            Intent(instrumentation.targetContext, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        ) as MainActivity
        try {
            instrumentation.runOnMainSync {
                activity.setContentView(ComposeView(activity).apply {
                    setContent {
                        ComposerActionBar(
                            state = calculateComposerActionBarUiState(
                                turnRunning = false,
                                draftText = "Hello",
                                attachmentsCount = 0,
                                awaitingAttachments = false,
                                isExpanded = true,
                                modelLabel = "6 Astra Medium",
                                hasFastTier = true,
                                hasPermissionOptions = true,
                                permissionLabel = "Full access"
                            )
                        )
                    }
                })
            }
            val plus = findActionNode("Composer options")
            val perm = findActionNode("Permissions, Full access")
            val model = findActionNode("Model and reasoning effort, 6 Astra Medium")
            val send = findActionNode("Send")

            val density = activity.resources.displayMetrics.density
            val plusBounds = Rect().also { plus.getBoundsInScreen(it) }
            val permBounds = Rect().also { perm.getBoundsInScreen(it) }
            val modelBounds = Rect().also { model.getBoundsInScreen(it) }
            val sendBounds = Rect().also { send.getBoundsInScreen(it) }

            assertTrue("Plus width >= 48dp", plusBounds.width() / density >= 47.5f)
            assertTrue("Plus height >= 48dp", plusBounds.height() / density >= 47.5f)
            assertTrue("Perm width >= 48dp", permBounds.width() / density >= 47.5f)
            assertTrue("Perm height >= 48dp", permBounds.height() / density >= 47.5f)
            assertTrue("Model width >= 48dp", modelBounds.width() / density >= 47.5f)
            assertTrue("Model height >= 48dp", modelBounds.height() / density >= 47.5f)
            assertTrue("Send width >= 48dp", sendBounds.width() / density >= 47.5f)
            assertTrue("Send height >= 48dp", sendBounds.height() / density >= 47.5f)

            assertFalse("Plus and perm must not intersect", Rect.intersects(plusBounds, permBounds))
            assertFalse("Perm and model must not intersect", Rect.intersects(permBounds, modelBounds))
            assertFalse("Model and send must not intersect", Rect.intersects(modelBounds, sendBounds))
            assertFalse("Plus and model must not intersect", Rect.intersects(plusBounds, modelBounds))
        } finally {
            instrumentation.runOnMainSync { activity.finish() }
        }
    }
}
