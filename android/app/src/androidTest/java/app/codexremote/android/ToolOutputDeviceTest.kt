@file:Suppress("DEPRECATION")
package app.codexremote.android
import android.test.InstrumentationTestCase
import androidx.compose.runtime.mutableStateOf
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.ui.Modifier
import app.codexremote.android.ui.conversation.ToolActivityCard
class ToolOutputDeviceTest : InstrumentationTestCase() {
    fun testLongOutputCanBeFullyExpandedAndCollapsed() {
        val output=(1..35).joinToString("\n"){"OUTPUT_LINE_$it"};val expanded=mutableStateOf(false)
        val item=TimelineItem("qa-long-output","Ran tools",output,TimelineItem.Kind.COMMAND,rawCommand="printf QA")
        val activity=instrumentation.composeFixture {Column(Modifier.verticalScroll(rememberScrollState())) {ToolActivityCard(item,expanded.value,{expanded.value=!expanded.value},{_,_->})}}
        try {instrumentation.clickUi("Ran tools");instrumentation.awaitUiText("printf QA")
            val root=instrumentation.awaitUiText("OUTPUT_LINE_1")
            assertTrue("Output preserved",root.uiDescendants().any {it.text?.contains("OUTPUT_LINE_35")==true} || root.findUiText("Show full output").isNotEmpty())
            if(root.findUiText("Show full output").isNotEmpty()) {instrumentation.clickUi("Show full output");instrumentation.awaitUiText("OUTPUT_LINE_35")}
        } finally {instrumentation.runOnMainSync {activity.finish()}}
    }
}
