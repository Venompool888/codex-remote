@file:Suppress("DEPRECATION")
package app.codexremote.android
import android.graphics.Rect
import android.os.Bundle
import android.test.InstrumentationTestCase
import android.view.accessibility.AccessibilityNodeInfo
import androidx.compose.runtime.mutableStateOf
import app.codexremote.android.compose.*
import app.codexremote.android.presentation.composer.ComposerController
import app.codexremote.android.presentation.composer.ComposerOption
import app.codexremote.android.ui.composer.ComposerSection

class ComposerActionBarDeviceTest : InstrumentationTestCase() {
    private fun action(label: String): AccessibilityNodeInfo {
        var node = instrumentation.awaitUi(label) { it.uiDescendants().any { n -> n.contentDescription?.toString() == label } }
            .uiDescendants().first { it.contentDescription?.toString() == label }
        repeat(12) { if (!node.isClickable) node = node.parent ?: node }
        return node
    }
    fun testCallbacksDisabledAndExpansion() {
        var sends=0; var plus=0; var model=0
        val state=mutableStateOf(calculateComposerActionBarUiState(false,"",0,false,false,"Model",false))
        val activity=instrumentation.composeFixture { ComposerActionBar(state.value,{plus++},{model++},{sends++}) }
        try {
            assertFalse(action("Send").isEnabled)
            action("Composer options").performAction(AccessibilityNodeInfo.ACTION_CLICK)
            instrumentation.awaitUi("plus callback") { plus==1 }
            instrumentation.runOnMainSync { state.value=calculateComposerActionBarUiState(false,"hello",0,false,true,"Model",false) }
            instrumentation.awaitUi("Send enabled") { action("Send").isEnabled }
            action("Send").performAction(AccessibilityNodeInfo.ACTION_CLICK)
            instrumentation.awaitUi("send callback") { sends==1 }
            action("Model and reasoning effort, Model").performAction(AccessibilityNodeInfo.ACTION_CLICK)
            instrumentation.awaitUi("model callback") { model==1 }
            instrumentation.runOnMainSync { state.value=calculateComposerActionBarUiState(false,"hello",1,true,true,"Model",false) }
            assertFalse(action("Send unavailable until attachments are ready").isEnabled)
            instrumentation.runOnMainSync { state.value=calculateComposerActionBarUiState(true,"",1,true,true,"Model",false) }
            action("Stop response").performAction(AccessibilityNodeInfo.ACTION_CLICK)
            instrumentation.awaitUi("stop callback") { sends==2 }
        } finally { instrumentation.runOnMainSync { activity.finish() } }
    }
    fun testShortModelTargetWrapsContent() {
        val activity=instrumentation.composeFixture { ComposerActionBar(calculateComposerActionBarUiState(false,"x",0,false,true,"M",false),{},{},{}) }
        try {
            val bounds=Rect().also(action("Model and reasoning effort, M")::getBoundsInScreen)
            val width=bounds.width()/activity.resources.displayMetrics.density
            assertTrue("Short model wraps content",width<150); assertTrue("48dp target",width>=47.8f)
        } finally { instrumentation.runOnMainSync { activity.finish() } }
    }
    fun testRealComposerTextAndModelSelection() {
        var selected="";var text=""
        val controller=ComposerController(onModelChanged={selected=it},onTextChanged={text=it.text})
        instrumentation.runOnMainSync { controller.setModelOptions(listOf(ComposerOption("a","Fixture A"),ComposerOption("b","Fixture B")),"a") }
        val activity=instrumentation.composeFixture { ComposerSection(controller) }
        try {
            val edit=instrumentation.awaitUi("editor") { it.uiDescendants().any { n -> n.isEditable } }.uiDescendants().first { it.isEditable }
            edit.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT,Bundle().apply {putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE,"中文输入 · Composer draft")})
            instrumentation.awaitUi("text update") { text=="中文输入 · Composer draft" }
            instrumentation.runOnMainSync { controller.toggleModelMenu(true) }
            instrumentation.clickUi("Fixture B")
            instrumentation.awaitUi("selected model") { selected=="b" }
            assertEquals("中文输入 · Composer draft",controller.uiState.value.text)
        } finally { instrumentation.runOnMainSync { activity.finish() } }
    }
    fun testPermissionCallbackAndConciseModelLabel() {
        var permissionClicked = false
        val state = mutableStateOf(
            calculateComposerActionBarUiState(
                turnRunning = false,
                draftText = "test",
                attachmentsCount = 0,
                awaitingAttachments = false,
                isExpanded = true,
                modelLabel = "GPT-6-Astra (Medium)",
                hasFastTier = false,
                hasPermissionOptions = true,
                permissionLabel = "Ask for approval"
            )
        )
        val activity = instrumentation.composeFixture {
            ComposerActionBar(
                state = state.value,
                onPlusClick = {},
                onModelClick = {},
                onSendClick = {},
                onPermissionClick = { permissionClicked = true }
            )
        }
        try {
            assertEquals("6 Astra Medium", state.value.modelLabel)
            assertEquals("Model and reasoning effort, GPT-6-Astra (Medium)", state.value.modelContentDescription)
            val perm = action("Permissions, Ask for approval")
            perm.performAction(AccessibilityNodeInfo.ACTION_CLICK)
            instrumentation.awaitUi("permission callback") { permissionClicked }
        } finally { instrumentation.runOnMainSync { activity.finish() } }
    }
}
