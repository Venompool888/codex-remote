@file:Suppress("DEPRECATION")
package app.codexremote.android
import android.graphics.Rect
import android.test.InstrumentationTestCase
import androidx.compose.runtime.mutableStateOf
import app.codexremote.android.compose.*
class AttachmentStateLayoutDeviceTest : InstrumentationTestCase() {
    fun testFileStatusNeverOverlapsMetadata() {
        val state=mutableStateOf(FileAttachmentUiState("layout-proof","quarterly-report.pdf",123456,"PDF · 123 KB",AttachmentUploadState.Uploading(37)))
        val activity=instrumentation.composeFixture { FileAttachmentCard(state.value,{},{}) }
        try {
            for(upload in listOf(AttachmentUploadState.Uploading(37),AttachmentUploadState.Failed(),AttachmentUploadState.Waiting,AttachmentUploadState.Expired,AttachmentUploadState.Ready)) {
                instrumentation.runOnMainSync {state.value=state.value.copy(uploadState=upload)}
                val root=if(upload.displayText.isNotBlank())instrumentation.awaitUiText(upload.displayText) else instrumentation.awaitUi("ready") {it.findUiText("Expired").isEmpty()}
                val meta=Rect().also(root.findUiText("PDF · 123 KB").first()::getBoundsInScreen)
                if(upload.displayText.isNotBlank()) {
                    val status=Rect().also(root.findUiText(upload.displayText).first()::getBoundsInScreen)
                    assertTrue("Status below metadata",status.top>=meta.bottom)
                }
            }
        } finally {instrumentation.runOnMainSync {activity.finish()}}
    }
}
