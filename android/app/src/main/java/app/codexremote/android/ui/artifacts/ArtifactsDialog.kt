package app.codexremote.android.ui.artifacts

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import app.codexremote.android.R
import app.codexremote.android.presentation.artifacts.ArtifactItem
import app.codexremote.android.presentation.artifacts.ArtifactsController
import app.codexremote.android.ui.theme.AppColors

@Composable
fun ArtifactsDialog(
    controller: ArtifactsController,
    modifier: Modifier = Modifier
) {
    val state = controller.uiState.value

    if (state.downloadProgress != null) {
        val download = state.downloadProgress
        Dialog(
            onDismissRequest = {
                if (download.isCompleted || download.isFailed) {
                    controller.dismissDownload(download.key)
                } else {
                    controller.cancelDownload(download.key)
                }
            }
        ) {
            Surface(
                shape = RoundedCornerShape(16.dp),
                color = AppColors.surfaceContainer,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp)
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(20.dp)
                ) {
                    Text(
                        text = when {
                            download.isCompleted -> "Download complete"
                            download.isFailed -> "Download failed"
                            else -> "Downloading ${download.name}"
                        },
                        fontSize = 16.sp,
                        fontWeight = FontWeight.Bold,
                        color = if (download.isFailed) AppColors.error else AppColors.onSurface
                    )

                    Spacer(modifier = Modifier.height(12.dp))

                    if (!download.isCompleted && !download.isFailed) {
                        androidx.compose.material3.LinearProgressIndicator(
                            progress = { download.percent / 100f },
                            modifier = Modifier.fillMaxWidth()
                        )
                        Spacer(modifier = Modifier.height(6.dp))
                        Text(
                            text = "${download.percent}%",
                            fontSize = 12.sp,
                            color = AppColors.onSurfaceMuted,
                            modifier = Modifier.align(Alignment.End)
                        )
                    } else if (download.isCompleted) {
                        Text(
                            text = download.name,
                            fontSize = 13.sp,
                            color = AppColors.onSurfaceVariant
                        )
                    } else {
                        Text(
                            text = "Could not download ${download.name}. Check your connection or retry.",
                            fontSize = 13.sp,
                            color = AppColors.onSurfaceVariant
                        )
                    }

                    Spacer(modifier = Modifier.height(16.dp))

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.End
                    ) {
                        if (download.isCompleted) {
                            if (download.share != null) {
                                Button(
                                    onClick = { download.share.invoke() },
                                    shape = RoundedCornerShape(8.dp),
                                    colors = ButtonDefaults.buttonColors(
                                        containerColor = AppColors.surfaceContainerHighest,
                                        contentColor = AppColors.primary
                                    )
                                ) {
                                    Text("Share")
                                }
                                Spacer(modifier = Modifier.width(8.dp))
                            }
                            if (download.open != null) {
                                Button(
                                    onClick = {
                                        download.open.invoke()
                                        controller.dismissDownload(download.key)
                                    },
                                    shape = RoundedCornerShape(8.dp),
                                    colors = ButtonDefaults.buttonColors(
                                        containerColor = AppColors.primary,
                                        contentColor = AppColors.onPrimary
                                    )
                                ) {
                                    Text("Open")
                                }
                                Spacer(modifier = Modifier.width(8.dp))
                            }
                            Button(
                                onClick = { controller.dismissDownload(download.key) },
                                shape = RoundedCornerShape(8.dp),
                                colors = ButtonDefaults.buttonColors(
                                    containerColor = AppColors.surfaceContainerHighest,
                                    contentColor = AppColors.onSurface
                                )
                            ) {
                                Text("Done")
                            }
                        } else if (download.isFailed) {
                            if (download.retry != null) {
                                Button(
                                    onClick = { download.retry.invoke() },
                                    shape = RoundedCornerShape(8.dp),
                                    colors = ButtonDefaults.buttonColors(
                                        containerColor = AppColors.primary,
                                        contentColor = AppColors.onPrimary
                                    )
                                ) {
                                    Text("Retry")
                                }
                                Spacer(modifier = Modifier.width(8.dp))
                            }
                            Button(
                                onClick = { controller.dismissDownload(download.key) },
                                shape = RoundedCornerShape(8.dp),
                                colors = ButtonDefaults.buttonColors(
                                    containerColor = AppColors.surfaceContainerHighest,
                                    contentColor = AppColors.onSurface
                                )
                            ) {
                                Text("Close")
                            }
                        } else {
                            Button(
                                onClick = { controller.cancelDownload(download.key) },
                                shape = RoundedCornerShape(8.dp),
                                colors = ButtonDefaults.buttonColors(
                                    containerColor = AppColors.surfaceContainerHighest,
                                    contentColor = AppColors.error
                                )
                            ) {
                                Text("Cancel")
                            }
                        }
                    }
                }
            }
        }
    }

    if (!state.isOpen) return

    Dialog(
        onDismissRequest = { controller.closeArtifacts() },
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        val shape = RoundedCornerShape(16.dp)

        Box(
            modifier = modifier
                .fillMaxSize()
                .background(AppColors.drawerScrim)
                .padding(16.dp),
            contentAlignment = Alignment.Center
        ) {
            Surface(
                modifier = Modifier
                    .fillMaxWidth()
                    .fillMaxHeight(0.85f),
                shape = shape,
                color = AppColors.surfaceContainer
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(16.dp)
                ) {
                    // Header
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = if (state.selectedArtifact != null) state.selectedArtifact.name else "Task Artifacts",
                            fontSize = 18.sp,
                            fontWeight = FontWeight.Bold,
                            color = AppColors.onSurface,
                            modifier = Modifier.weight(1f),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        IconButton(onClick = {
                            if (state.selectedArtifact != null) {
                                controller.selectArtifact(null)
                            } else {
                                controller.closeArtifacts()
                            }
                        }) {
                            Icon(
                                painter = painterResource(if (state.selectedArtifact != null) R.drawable.ic_arrow_back else R.drawable.ic_close),
                                contentDescription = "Close",
                                tint = AppColors.onSurfaceMuted
                            )
                        }
                    }

                    Spacer(modifier = Modifier.height(12.dp))

                    // Content
                    if (state.isLoading) {
                        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                            CircularProgressIndicator(modifier = Modifier.size(24.dp))
                        }
                    } else if (state.selectedArtifact != null) {
                        // Viewing single artifact
                        val artifact = state.selectedArtifact
                        Column(modifier = Modifier.fillMaxSize()) {
                            Box(
                                modifier = Modifier
                                    .weight(1f)
                                    .fillMaxWidth()
                                    .clip(RoundedCornerShape(8.dp))
                                    .background(AppColors.background)
                                    .padding(12.dp)
                                    .verticalScroll(rememberScrollState())
                            ) {
                                Text(
                                    text = artifact.previewText.ifBlank { "(Empty artifact)" },
                                    fontFamily = FontFamily.Monospace,
                                    fontSize = 12.sp,
                                    lineHeight = 16.sp,
                                    color = AppColors.onSurface
                                )
                            }

                            Spacer(modifier = Modifier.height(12.dp))
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.End
                            ) {
                                Button(
                                    onClick = { controller.copyContent(artifact) },
                                    shape = RoundedCornerShape(8.dp),
                                    colors = ButtonDefaults.buttonColors(
                                        containerColor = AppColors.surfaceContainerHighest,
                                        contentColor = AppColors.primary
                                    )
                                ) {
                                    Text("Copy", fontWeight = FontWeight.Bold)
                                }
                                Spacer(modifier = Modifier.width(8.dp))
                                Button(
                                    onClick = { controller.downloadArtifact(artifact) },
                                    shape = RoundedCornerShape(8.dp),
                                    colors = ButtonDefaults.buttonColors(
                                        containerColor = AppColors.primary,
                                        contentColor = AppColors.onPrimary
                                    )
                                ) {
                                    Text("Download", fontWeight = FontWeight.Bold)
                                }
                            }
                        }
                    } else if (state.artifacts.isEmpty()) {
                        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                            Text(
                                text = "No artifacts generated yet",
                                fontSize = 14.sp,
                                color = AppColors.onSurfaceMuted
                            )
                        }
                    } else {
                        LazyColumn(
                            modifier = Modifier.fillMaxSize(),
                            verticalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            items(state.artifacts, key = { it.id }) { artifact ->
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .clip(RoundedCornerShape(8.dp))
                                        .background(AppColors.surfaceContainerLow)
                                        .border(BorderStroke(1.dp, AppColors.outlineVariant), RoundedCornerShape(8.dp))
                                        .clickable { controller.selectArtifact(artifact) }
                                        .padding(12.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Icon(
                                        painter = painterResource(R.drawable.ic_attach_file),
                                        contentDescription = null,
                                        tint = AppColors.primary,
                                        modifier = Modifier.size(20.dp)
                                    )
                                    Spacer(modifier = Modifier.width(10.dp))
                                    Column(modifier = Modifier.weight(1f)) {
                                        Text(
                                            text = artifact.name,
                                            fontSize = 14.sp,
                                            fontWeight = FontWeight.SemiBold,
                                            color = AppColors.onSurface,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis
                                        )
                                        Text(
                                            text = "${artifact.path} · ${artifact.sizeBytes} B",
                                            fontSize = 11.sp,
                                            color = AppColors.onSurfaceMuted,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}
