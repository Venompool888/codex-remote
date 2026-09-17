package app.codexremote.android.ui.artifacts

import androidx.compose.ui.semantics.*
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.sizeIn
import android.graphics.Bitmap
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import app.codexremote.android.R
import app.codexremote.android.presentation.artifacts.ArtifactsController

@Composable
fun ImageViewerDialog(
    controller: ArtifactsController,
    modifier: Modifier = Modifier
) {
    val state = controller.uiState.value
    val bitmap = state.viewerImage ?: return
    if (!state.isViewerOpen) return

    Dialog(
        onDismissRequest = { controller.closeImageViewer() },
        properties = DialogProperties(
            usePlatformDefaultWidth = false,
            decorFitsSystemWindows = false
        )
    ) {
        var scale by remember { mutableFloatStateOf(1f) }
        var offset by remember { mutableStateOf(Offset.Zero) }

        // Reset zoom and pan whenever the image changes
        LaunchedEffect(bitmap) {
            scale = 1f
            offset = Offset.Zero
        }

        Box(
            modifier = modifier
                .fillMaxSize()
                .background(Color.Black)
        ) {
            // Zoomable and pannable image content with clamped bounds
            BoxWithConstraints(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color(0xFF212121)),
                contentAlignment = Alignment.Center
            ) {
                val containerWidth = constraints.maxWidth.toFloat()
                val containerHeight = constraints.maxHeight.toFloat()
                val imgW = bitmap.width.toFloat().coerceAtLeast(1f)
                val imgH = bitmap.height.toFloat().coerceAtLeast(1f)
                val fit = minOf(containerWidth / imgW, containerHeight / imgH)

                val zoomPercent = (scale * 100).toInt()
                val zoomInLabel = "Zoom in"
                val zoomOutLabel = "Zoom out"

                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .semantics {
                            stateDescription = "$zoomPercent% zoom"
                            customActions = listOf(
                                androidx.compose.ui.semantics.CustomAccessibilityAction(zoomInLabel) {
                                    val nextScale = (scale * 1.5f).coerceIn(1f, 8f)
                                    scale = nextScale
                                    true
                                },
                                androidx.compose.ui.semantics.CustomAccessibilityAction(zoomOutLabel) {
                                    val nextScale = (scale / 1.5f).coerceIn(1f, 8f)
                                    if (nextScale <= 1.01f) {
                                        scale = 1f
                                        offset = Offset.Zero
                                    } else {
                                        scale = nextScale
                                    }
                                    true
                                }
                            )
                        }
                        .pointerInput(bitmap) {
                            detectTapGestures(
                                onDoubleTap = { tapOffset ->
                                    val nextScale = if (scale > 1.01f) 1f else 3f
                                    val ratio = nextScale / scale
                                    val maxX = ((imgW * fit * nextScale - containerWidth) / 2f).coerceAtLeast(0f)
                                    val maxY = ((imgH * fit * nextScale - containerHeight) / 2f).coerceAtLeast(0f)
                                    if (nextScale <= 1.01f) {
                                        scale = 1f
                                        offset = Offset.Zero
                                    } else {
                                        offset = Offset(
                                            (offset.x * ratio + (tapOffset.x - containerWidth / 2f) * (1f - ratio)).coerceIn(-maxX, maxX),
                                            (offset.y * ratio + (tapOffset.y - containerHeight / 2f) * (1f - ratio)).coerceIn(-maxY, maxY)
                                        )
                                        scale = nextScale
                                    }
                                }
                            )
                        }
                        .pointerInput(bitmap) {
                            detectTransformGestures { centroid, pan, zoom, _ ->
                                val nextScale = (scale * zoom).coerceIn(1f, 8f)
                                val ratio = nextScale / scale
                                val maxX = ((imgW * fit * nextScale - containerWidth) / 2f).coerceAtLeast(0f)
                                val maxY = ((imgH * fit * nextScale - containerHeight) / 2f).coerceAtLeast(0f)
                                offset = Offset(
                                    (offset.x * ratio + (centroid.x - containerWidth / 2f) * (1f - ratio) + pan.x).coerceIn(-maxX, maxX),
                                    (offset.y * ratio + (centroid.y - containerHeight / 2f) * (1f - ratio) + pan.y).coerceIn(-maxY, maxY)
                                )
                                scale = nextScale
                            }
                        },
                    contentAlignment = Alignment.Center
                ) {
                    Image(
                        bitmap = bitmap.asImageBitmap(),
                        contentDescription = state.viewerDescription,
                        contentScale = ContentScale.Fit,
                        modifier = Modifier
                            .fillMaxSize()
                            .semantics {
                                stateDescription = "$zoomPercent% zoom"
                                customActions = listOf(
                                    androidx.compose.ui.semantics.CustomAccessibilityAction(zoomInLabel) {
                                        val nextScale = (scale * 1.5f).coerceIn(1f, 8f)
                                        scale = nextScale
                                        true
                                    },
                                    androidx.compose.ui.semantics.CustomAccessibilityAction(zoomOutLabel) {
                                        val nextScale = (scale / 1.5f).coerceIn(1f, 8f)
                                        if (nextScale <= 1.01f) {
                                            scale = 1f
                                            offset = Offset.Zero
                                        } else {
                                            scale = nextScale
                                        }
                                        true
                                    }
                                )
                            }
                            .graphicsLayer(
                                scaleX = scale,
                                scaleY = scale,
                                translationX = offset.x,
                                translationY = offset.y
                            )
                    )
                }
            }

            // Top Toolbar with FlowRow for 2x font reflow
            @OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
            androidx.compose.foundation.layout.FlowRow(
                modifier = Modifier
                    .fillMaxWidth()
                    .statusBarsPadding()
                    .heightIn(min = 56.dp)
                    .background(Color(0x99000000))
                    .padding(horizontal = 8.dp, vertical = 4.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalArrangement = Arrangement.Center
            ) {
                Row(
                    modifier = Modifier.weight(1f, fill = false),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    IconButton(
                        onClick = { controller.closeImageViewer() },
                        modifier = Modifier.size(48.dp)
                    ) {
                        Icon(
                            painter = painterResource(R.drawable.ic_attachment_remove),
                            contentDescription = "Close image",
                            tint = Color.White,
                            modifier = Modifier.size(24.dp)
                        )
                    }

                    Text(
                        text = state.viewerDescription,
                        fontSize = 14.sp,
                        color = Color.White,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.padding(horizontal = 4.dp)
                    )
                }

                Row(
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    // Capability-gated Share button
                    if (state.canShare) {
                        Button(
                            onClick = { controller.shareImage() },
                            shape = RoundedCornerShape(18.dp),
                            colors = ButtonDefaults.buttonColors(
                                containerColor = Color.White,
                                contentColor = Color.Black
                            ),
                            modifier = Modifier
                                .heightIn(min = 36.dp)
                                .sizeIn(minWidth = 48.dp, minHeight = 48.dp)
                        ) {
                            Text("Share", fontSize = 13.sp)
                        }
                        Spacer(modifier = Modifier.width(6.dp))
                    }

                    // Capability-gated Save button
                    if (state.canSave) {
                        IconButton(
                            onClick = { controller.saveImage() },
                            modifier = Modifier.size(48.dp)
                        ) {
                            Icon(
                                painter = painterResource(R.drawable.ic_download_image),
                                contentDescription = "Download image",
                                tint = Color.White,
                                modifier = Modifier.size(24.dp)
                            )
                        }
                    }
                }
            }
        }
    }
}
