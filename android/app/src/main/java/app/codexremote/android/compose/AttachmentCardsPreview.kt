package app.codexremote.android.compose

import android.content.res.Configuration
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.colorResource
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import app.codexremote.android.R

@Composable
fun SampleAttachmentStripContent() {
    val items = listOf(
        ImageAttachmentUiState(
            localId = "img-ready",
            name = "screenshot_pixel.png",
            uploadState = AttachmentUploadState.Ready,
            contentDescription = "screenshot_pixel.png · Ready"
        ),
        ImageAttachmentUiState(
            localId = "img-uploading",
            name = "photo_large.jpg",
            uploadState = AttachmentUploadState.Uploading(45),
            contentDescription = "photo_large.jpg · 45%"
        ),
        ImageAttachmentUiState(
            localId = "img-failed",
            name = "corrupted_diagram.png",
            uploadState = AttachmentUploadState.Failed("Failed · retry"),
            contentDescription = "corrupted_diagram.png · Failed"
        ),
        FileAttachmentUiState(
            localId = "file-normal",
            name = "report_q3_summary.pdf",
            sizeBytes = 2450000L,
            metadataText = "PDF · 2.4 MB",
            uploadState = AttachmentUploadState.Ready
        ),
        FileAttachmentUiState(
            localId = "file-long-uploading",
            name = "very_long_project_architecture_audit_spec_final_2026.tar.gz",
            sizeBytes = 18900000L,
            metadataText = "TAR.GZ · 18.9 MB",
            uploadState = AttachmentUploadState.Uploading(78)
        ),
        FileAttachmentUiState(
            localId = "file-failed",
            name = "broken_dataset.csv",
            sizeBytes = 512000L,
            metadataText = "CSV · 512 KB",
            uploadState = AttachmentUploadState.Failed("Failed · retry")
        ),
        CapabilityTagUiState(
            localId = "cap-code",
            name = "github-review",
            description = "GitHub Pull Request Reviewer"
        ),
        CapabilityTagUiState(
            localId = "cap-long",
            name = "cloud-infrastructure-monitoring-agent",
            description = "Infrastructure Agent"
        )
    )

    Surface(
        color = colorResource(R.color.app_background),
        modifier = Modifier.padding(16.dp)
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
            ComposerAttachmentStrip(
                items = items,
                onCardClick = {},
                onRemoveClick = {}
            )
        }
    }
}

@Preview(name = "Light Mode - Normal Font", showBackground = true)
@Composable
fun PreviewLightNormal() {
    SampleAttachmentStripContent()
}

@Preview(name = "Dark Mode - Normal Font", showBackground = true, uiMode = Configuration.UI_MODE_NIGHT_YES)
@Composable
fun PreviewDarkNormal() {
    SampleAttachmentStripContent()
}

@Preview(name = "Light Mode - 2.0x Font Scale", showBackground = true, fontScale = 2.0f)
@Composable
fun PreviewLightLargeFont() {
    SampleAttachmentStripContent()
}

@Preview(name = "Dark Mode - 2.0x Font Scale", showBackground = true, uiMode = Configuration.UI_MODE_NIGHT_YES, fontScale = 2.0f)
@Composable
fun PreviewDarkLargeFont() {
    SampleAttachmentStripContent()
}
