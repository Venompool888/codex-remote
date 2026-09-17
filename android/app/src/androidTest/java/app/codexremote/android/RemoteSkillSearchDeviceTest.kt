@file:Suppress("DEPRECATION")
package app.codexremote.android

import android.content.Intent
import android.graphics.Bitmap
import android.os.Bundle
import android.os.SystemClock
import android.test.InstrumentationTestCase
import android.view.View
import android.view.ViewGroup
import android.view.accessibility.AccessibilityNodeInfo

/** Explicit live-host read-only acceptance; not part of the unpaired/default fixture suite. */
class RemoteSkillSearchDeviceTest : InstrumentationTestCase() {
    private fun nodes(): List<AccessibilityNodeInfo> {
        fun walk(n: AccessibilityNodeInfo): List<AccessibilityNodeInfo> { n.refresh(); return listOf(n)+(0 until n.childCount).flatMap { n.getChild(it)?.let(::walk) ?: emptyList() } }
        return instrumentation.uiAutomation.rootInActiveWindow?.let(::walk) ?: emptyList()
    }
    private fun awaitNode(test: (AccessibilityNodeInfo)->Boolean):AccessibilityNodeInfo {
        val end=SystemClock.uptimeMillis()+30000
        while(SystemClock.uptimeMillis()<end) { nodes().firstOrNull(test)?.let { return it };SystemClock.sleep(150) }
        throw AssertionError("Expected remote catalog UI state not reached")
    }
    fun testInstalledRemoteSkillCanBeSearched() {
        val activity=instrumentation.startActivitySync(Intent(instrumentation.targetContext,MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) as MainActivity
        fun views(v:View):List<View> = listOf(v)+(if(v is ViewGroup)(0 until v.childCount).flatMap { views(v.getChildAt(it)) } else emptyList())
        awaitNode { it.contentDescription?.toString()?.contains("Connected ·")==true }
        var plus = awaitNode { it.contentDescription == "Composer options" }
        while (!plus.isClickable) { plus = plus.parent ?: error("Missing plus action"); plus.refresh() }
        assertTrue(plus.performAction(AccessibilityNodeInfo.ACTION_CLICK))
        val refresh=awaitNode { it.contentDescription=="Refresh capabilities" && it.isEnabled }
        refresh.performAction(AccessibilityNodeInfo.ACTION_CLICK)
        awaitNode { it.contentDescription?.toString()?.startsWith("xray-reverse. Enabled.")==true }
        awaitNode { it.contentDescription=="Search plugins and skills" }.performAction(AccessibilityNodeInfo.ACTION_CLICK)
        val edit=awaitNode { it.className=="android.widget.EditText" }
        fun query(value:String) { edit.refresh();assertTrue(edit.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT,Bundle().apply { putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE,value) }));SystemClock.sleep(250) }
        for(value in listOf("xr","xray reverse")) {
            query(value)
            awaitNode { it.contentDescription?.toString()?.startsWith("xray-reverse. Enabled.")==true }
            assertFalse("Field boundary must not match index+Route as xr", nodes().any { it.contentDescription?.toString()?.startsWith("data-analytics:index.")==true })
        }
        query("xr")
        val bitmap=instrumentation.uiAutomation.takeScreenshot()
        instrumentation.targetContext.openFileOutput("qa-remote-xray-search.png",0).use { bitmap.compress(Bitmap.CompressFormat.PNG,100,it) };bitmap.recycle()
        // Leave the real search open for the user; do not select, send or execute the skill.
    }
}
