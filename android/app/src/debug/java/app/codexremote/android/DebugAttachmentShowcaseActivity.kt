package app.codexremote.android

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.appcompat.app.AppCompatDelegate
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Divider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.colorResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.codexremote.android.compose.AttachmentItemUiState
import app.codexremote.android.compose.AttachmentUploadState
import app.codexremote.android.compose.CapabilityTagChip
import app.codexremote.android.compose.CapabilityTagUiState
import app.codexremote.android.compose.ComposerAttachmentStrip
import app.codexremote.android.compose.ComposerActionBar
import app.codexremote.android.compose.ComposerActionBarUiState
import app.codexremote.android.compose.FileAttachmentCard
import app.codexremote.android.compose.FileAttachmentUiState
import app.codexremote.android.compose.ImageAttachmentCard
import app.codexremote.android.compose.ImageAttachmentUiState
import app.codexremote.android.compose.updateImagePreviewLoading
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

private fun createSampleBitmap(text: String, bgColor: Int): Bitmap {
    val bitmap = Bitmap.createBitmap(160, 160, Bitmap.Config.ARGB_8888)
    val canvas = Canvas(bitmap)
    canvas.drawColor(bgColor)
    val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        textSize = 24f
        textAlign = Paint.Align.CENTER
    }
    canvas.drawText(text, 80f, 90f, paint)
    return bitmap
}

class DebugAttachmentShowcaseActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            ShowcaseScreen(
                onToast = { msg -> Toast.makeText(this, msg, Toast.LENGTH_SHORT).show() },
                onToggleNightMode = { isDark ->
                    AppCompatDelegate.setDefaultNightMode(
                        if (isDark) AppCompatDelegate.MODE_NIGHT_YES else AppCompatDelegate.MODE_NIGHT_NO
                    )
                }
            )
        }
    }
}

@Composable
fun ShowcaseScreen(
    onToast: (String) -> Unit,
    onToggleNightMode: (Boolean) -> Unit
) {
    var isDarkMode by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    val sampleBitmap1 = remember { createSampleBitmap("IMG 1", Color.rgb(52, 168, 83)) }
    val sampleBitmap2 = remember { createSampleBitmap("IMG 2", Color.rgb(66, 133, 244)) }

    val defaultItems = remember {
        mutableStateListOf<AttachmentItemUiState>(
            ImageAttachmentUiState(
                localId = "img-ready",
                name = "pixel_capture.png",
                previewBitmap = sampleBitmap1,
                uploadState = AttachmentUploadState.Ready,
                contentDescription = "pixel_capture.png · Ready"
            ),
            ImageAttachmentUiState(
                localId = "img-uploading",
                name = "photo_highres.jpg",
                previewBitmap = sampleBitmap2,
                uploadState = AttachmentUploadState.Uploading(45),
                contentDescription = "photo_highres.jpg · 45%"
            ),
            ImageAttachmentUiState(
                localId = "img-failed",
                name = "corrupted.png",
                uploadState = AttachmentUploadState.Failed("Failed · retry"),
                contentDescription = "corrupted.png · Failed"
            ),
            FileAttachmentUiState(
                localId = "file-pdf",
                name = "architecture_doc.pdf",
                sizeBytes = 2450000L,
                metadataText = "PDF · 2.4 MB",
                uploadState = AttachmentUploadState.Ready
            ),
            FileAttachmentUiState(
                localId = "file-long",
                name = "super_long_release_notes_and_migration_contract_2026.tar.gz",
                sizeBytes = 18900000L,
                metadataText = "TAR.GZ · 18.9 MB",
                uploadState = AttachmentUploadState.Uploading(68)
            ),
            FileAttachmentUiState(
                localId = "file-failed",
                name = "dataset.csv",
                sizeBytes = 512000L,
                metadataText = "CSV · 512 KB",
                uploadState = AttachmentUploadState.Failed("Failed · retry")
            ),
            CapabilityTagUiState(
                localId = "cap-1",
                name = "code-reviewer",
                description = "Automated Pull Request Code Reviewer"
            ),
            CapabilityTagUiState(
                localId = "cap-2",
                name = "cloud-infra-monitor-agent",
                description = "Infrastructure Monitoring Skill"
            )
        )
    }

    var progressStep by remember { mutableStateOf(45) }

    Surface(
        modifier = Modifier.fillMaxSize(),
        color = colorResource(R.color.app_background)
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .statusBarsPadding()
                .verticalScroll(rememberScrollState())
                .padding(16.dp)
        ) {
            // Header
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "Compose Showcase",
                    fontSize = 20.sp,
                    fontWeight = FontWeight.Bold,
                    color = colorResource(R.color.app_on_surface)
                )

                OutlinedButton(
                    onClick = {
                        isDarkMode = !isDarkMode
                        onToggleNightMode(isDarkMode)
                    }
                ) {
                    Text(if (isDarkMode) "Light Mode" else "Dark Mode")
                }
            }

            Spacer(modifier = Modifier.height(12.dp))

            // Action Controls
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Button(
                    onClick = {
                        progressStep = (progressStep + 20) % 100
                        val idx = defaultItems.indexOfFirst { it.localId == "img-uploading" }
                        if (idx >= 0) {
                            defaultItems[idx] = (defaultItems[idx] as ImageAttachmentUiState).copy(
                                uploadState = AttachmentUploadState.Uploading(progressStep)
                            )
                        }
                        onToast("Progress updated to $progressStep%")
                    },
                    modifier = Modifier.weight(1f)
                ) {
                    Text("Advance Upload")
                }

                Button(
                    onClick = {
                        defaultItems.clear()
                        defaultItems.addAll(
                            listOf(
                                ImageAttachmentUiState("img-ready", "pixel_capture.png", null, null, AttachmentUploadState.Ready),
                                FileAttachmentUiState("file-pdf", "architecture_doc.pdf", 2450000L, "PDF · 2.4 MB", AttachmentUploadState.Ready),
                                CapabilityTagUiState("cap-1", "code-reviewer", "Reviewer")
                            )
                        )
                        onToast("Reset to default items")
                    },
                    modifier = Modifier.weight(1f)
                ) {
                    Text("Reset Items")
                }
            }

            Spacer(modifier = Modifier.height(20.dp))

            Text(
                text = "1. Production ComposerAttachmentStrip (Horizontal)",
                fontWeight = FontWeight.SemiBold,
                fontSize = 15.sp,
                color = colorResource(R.color.app_on_surface)
            )

            // Strip
            ComposerAttachmentStrip(
                items = defaultItems,
                onCardClick = { item ->
                    if (item is FileAttachmentUiState && item.uploadState is AttachmentUploadState.Failed) {
                        val idx = defaultItems.indexOfFirst { it.localId == item.localId }
                        if (idx >= 0) {
                            val current = defaultItems[idx]
                            if (current is FileAttachmentUiState) {
                                defaultItems[idx] = current.copy(uploadState = AttachmentUploadState.Uploading(10))
                                onToast("Retrying ${current.name}...")
                            }
                        }
                    } else if (item is ImageAttachmentUiState && item.uploadState is AttachmentUploadState.Failed) {
                        val idx = defaultItems.indexOfFirst { it.localId == item.localId }
                        if (idx >= 0) {
                            val current = defaultItems[idx]
                            if (current is ImageAttachmentUiState) {
                                defaultItems[idx] = current.copy(uploadState = AttachmentUploadState.Uploading(10))
                                onToast("Retrying ${current.name}...")
                            }
                        }
                    } else if (item is ImageAttachmentUiState && item.uploadState is AttachmentUploadState.Ready) {
                        val targetLocalId = item.localId
                        val startIdx = defaultItems.indexOfFirst { it.localId == targetLocalId }
                        if (startIdx >= 0) {
                            val current = defaultItems[startIdx]
                            if (current is ImageAttachmentUiState && !current.isLoadingPreview) {
                                defaultItems.updateImagePreviewLoading(targetLocalId, isLoading = true)
                                scope.launch {
                                    delay(600)
                                    val completed = defaultItems.updateImagePreviewLoading(targetLocalId, isLoading = false)
                                    if (completed != null) {
                                        onToast("Preview loaded for ${completed.name}")
                                    }
                                }
                            }
                        }
                    } else {
                        onToast("Clicked: ${item.name}")
                    }
                },
                onRemoveClick = { item ->
                    defaultItems.removeAll { it.localId == item.localId }
                    onToast("Removed: ${item.name}")
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .background(colorResource(R.color.app_composer))
            )

            Spacer(modifier = Modifier.height(24.dp))

            Text(
                text = "2. Individual Component States",
                fontWeight = FontWeight.SemiBold,
                fontSize = 15.sp,
                color = colorResource(R.color.app_on_surface)
            )

            Spacer(modifier = Modifier.height(12.dp))

            // Section: Images
            Text("Image Card States (Ready, Uploading, Failed, Loading Preview):", fontSize = 13.sp, color = colorResource(R.color.app_on_surface_variant))
            Spacer(modifier = Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                ImageAttachmentCard(
                    state = ImageAttachmentUiState("test-img-1", "ready.png", sampleBitmap1, null, AttachmentUploadState.Ready),
                    onClick = { onToast("Ready image clicked") },
                    onRemove = { onToast("Ready image removed") }
                )
                ImageAttachmentCard(
                    state = ImageAttachmentUiState("test-img-2", "uploading.png", sampleBitmap2, null, AttachmentUploadState.Uploading(60)),
                    onClick = { onToast("Uploading image clicked") },
                    onRemove = { onToast("Uploading image removed") }
                )
                ImageAttachmentCard(
                    state = ImageAttachmentUiState("test-img-3", "failed.png", null, null, AttachmentUploadState.Failed("Failed · retry")),
                    onClick = { onToast("Failed image retry") },
                    onRemove = { onToast("Failed image removed") }
                )
                ImageAttachmentCard(
                    state = ImageAttachmentUiState("test-img-4", "loading.png", sampleBitmap1, null, AttachmentUploadState.Ready, isLoadingPreview = true),
                    onClick = { onToast("Loading preview image clicked") },
                    onRemove = { onToast("Loading preview image removed") }
                )
            }

            Spacer(modifier = Modifier.height(16.dp))

            // Section: Files
            Text("File Card States (Ready, Uploading, Long Name):", fontSize = 13.sp, color = colorResource(R.color.app_on_surface_variant))
            Spacer(modifier = Modifier.height(8.dp))
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                FileAttachmentCard(
                    state = FileAttachmentUiState("f-1", "short.txt", 1024L, "TXT · 1 KB", AttachmentUploadState.Ready),
                    onClick = { onToast("Clicked short.txt") },
                    onRemove = { onToast("Removed short.txt") }
                )
                FileAttachmentCard(
                    state = FileAttachmentUiState("f-2", "very_long_file_name_middle_truncate.tar.gz", 15400000L, "TAR.GZ · 15.4 MB", AttachmentUploadState.Uploading(33)),
                    onClick = { onToast("Clicked long file") },
                    onRemove = { onToast("Removed long file") }
                )
                FileAttachmentCard(
                    state = FileAttachmentUiState("f-3", "failure_case.zip", 3100000L, "ZIP · 3.1 MB", AttachmentUploadState.Failed("Failed · retry")),
                    onClick = { onToast("Retry zip") },
                    onRemove = { onToast("Removed zip") }
                )
            }

            Spacer(modifier = Modifier.height(16.dp))

            // Section: Capabilities
            Text("Capability Tag Chip:", fontSize = 13.sp, color = colorResource(R.color.app_on_surface_variant))
            Spacer(modifier = Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                CapabilityTagChip(
                    state = CapabilityTagUiState("c-1", "git-commit-helper", "Git Commit"),
                    onClick = { onToast("Capability clicked") },
                    onRemove = { onToast("Capability removed") }
                )
            }

            Spacer(modifier = Modifier.height(16.dp))

            // Section: Composer Action Bar States
            Text("Composer Action Bar States:", fontSize = 13.sp, color = colorResource(R.color.app_on_surface_variant))
            Spacer(modifier = Modifier.height(8.dp))
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("1. Empty Draft (Collapsed):", fontSize = 11.sp, color = colorResource(R.color.app_on_surface_variant))
                ComposerActionBar(
                    state = ComposerActionBarUiState(canSend = false, isTurnRunning = false, isExpanded = false),
                    onPlusClick = { onToast("Plus clicked") },
                    onModelClick = { onToast("Model clicked") },
                    onSendClick = { onToast("Send clicked") }
                )
                Text("2. Ready To Send (Expanded, Fast Tier):", fontSize = 11.sp, color = colorResource(R.color.app_on_surface_variant))
                ComposerActionBar(
                    state = ComposerActionBarUiState(
                        canSend = true,
                        isTurnRunning = false,
                        isExpanded = true,
                        modelLabel = "Claude 3.7 Sonnet High",
                        hasFastTier = true
                    ),
                    onPlusClick = { onToast("Plus clicked") },
                    onModelClick = { onToast("Model clicked") },
                    onSendClick = { onToast("Send clicked") }
                )
                Text("3. Awaiting Attachments Upload:", fontSize = 11.sp, color = colorResource(R.color.app_on_surface_variant))
                ComposerActionBar(
                    state = ComposerActionBarUiState(
                        canSend = false,
                        isTurnRunning = false,
                        isAwaitingAttachments = true,
                        isExpanded = true,
                        modelLabel = "GPT-4o",
                        sendButtonContentDescription = "Send unavailable until attachments are ready"
                    ),
                    onPlusClick = { onToast("Plus clicked") },
                    onModelClick = { onToast("Model clicked") },
                    onSendClick = { onToast("Send clicked") }
                )
                Text("4. Turn Running / Stop Mode:", fontSize = 11.sp, color = colorResource(R.color.app_on_surface_variant))
                ComposerActionBar(
                    state = ComposerActionBarUiState(
                        canSend = true,
                        isTurnRunning = true,
                        isExpanded = true,
                        modelLabel = "o3-mini Medium",
                        sendButtonContentDescription = "Stop response"
                    ),
                    onPlusClick = { onToast("Plus clicked") },
                    onModelClick = { onToast("Model clicked") },
                    onSendClick = { onToast("Stop clicked") }
                )
            }
        }
    }
}
