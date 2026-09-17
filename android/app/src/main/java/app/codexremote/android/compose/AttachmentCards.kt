package app.codexremote.android.compose

import android.graphics.Bitmap
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.requiredHeightIn
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.key
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.colorResource
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.Canvas
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import app.codexremote.android.R
import java.util.Locale

@Composable
fun AttachmentFileIconCanvas(
    filename: String,
    modifier: Modifier = Modifier
) {
    Canvas(modifier = modifier) {
        val width = size.width
        val height = size.height
        val scale = minOf(width / 32f, height / 40f)

        val extension = filename.substringAfterLast('.', "FILE").uppercase(Locale.ROOT)
        val label = extension.take(4)
        val fillColor = when (extension) {
            "PDF" -> Color(234, 67, 53)
            "CSV", "XLS", "XLSX" -> Color(24, 128, 56)
            "PPT", "PPTX" -> Color(249, 171, 0)
            "ZIP", "GZ", "TAR" -> Color(127, 86, 217)
            else -> Color(66, 133, 244)
        }

        drawIntoCanvas { nativeCanvas ->
            val canvas = nativeCanvas.nativeCanvas
            canvas.save()
            canvas.translate((width - 32 * scale) / 2f, (height - 40 * scale) / 2f)
            canvas.scale(scale, scale)

            val paint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
                color = fillColor.toArgb()
            }
            val sheet = android.graphics.Path().apply {
                moveTo(3f, 0f)
                lineTo(21f, 0f)
                lineTo(32f, 11f)
                lineTo(32f, 37f)
                quadTo(32f, 40f, 29f, 40f)
                lineTo(3f, 40f)
                quadTo(0f, 40f, 0f, 37f)
                lineTo(0f, 3f)
                quadTo(0f, 0f, 3f, 0f)
                close()
            }
            canvas.drawPath(sheet, paint)

            paint.color = 0x66FFFFFF
            val flap = android.graphics.Path().apply {
                moveTo(21f, 0f)
                lineTo(21f, 9f)
                quadTo(21f, 11f, 23f, 11f)
                lineTo(32f, 11f)
                close()
            }
            canvas.drawPath(flap, paint)

            paint.color = android.graphics.Color.WHITE
            paint.typeface = android.graphics.Typeface.create("sans-serif", android.graphics.Typeface.BOLD)
            paint.textSize = if (label.length > 3) 9f else 11f
            paint.textAlign = android.graphics.Paint.Align.CENTER
            canvas.drawText(label, 16f, 29f, paint)

            canvas.restore()
        }
    }
}

fun formatMiddleEllipsis(text: String, maxLength: Int = 24): String {
    if (text.length <= maxLength) return text
    val prefixLen = (maxLength - 3) * 3 / 5
    val suffixLen = (maxLength - 3) - prefixLen
    return text.take(prefixLen) + "…" + text.takeLast(suffixLen)
}

@Composable
fun ImageAttachmentCard(
    state: ImageAttachmentUiState,
    onClick: () -> Unit,
    onRemove: () -> Unit,
    modifier: Modifier = Modifier
) {
    val borderColor = colorResource(R.color.app_outline_variant)
    val surfaceColor = colorResource(R.color.app_surface_container_low)
    val shape = RoundedCornerShape(12.dp)

    Box(
        modifier = modifier
            .size(80.dp)
            .clip(shape)
            .background(surfaceColor, shape)
            .border(BorderStroke(1.dp, borderColor), shape)
            .clickable(enabled = !state.isLoadingPreview, onClick = onClick)
            .semantics { contentDescription = state.contentDescription }
    ) {
        if (state.previewBitmap != null) {
            Image(
                bitmap = state.previewBitmap.asImageBitmap(),
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize()
            )
        }

        if (state.isLoadingPreview) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color(0x66000000)),
                contentAlignment = Alignment.Center
            ) {
                CircularProgressIndicator(
                    modifier = Modifier.size(32.dp),
                    color = Color.White,
                    strokeWidth = 3.dp
                )
            }
        }

        val statusText = state.uploadState.displayText
        if (statusText.isNotEmpty()) {
            Box(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .fillMaxWidth()
                    .background(Color(0xB3000000))
                    .padding(horizontal = 6.dp, vertical = 3.dp)
            ) {
                Text(
                    text = statusText,
                    color = Color.White,
                    fontSize = 10.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }

        // Direct remove target 48 dp with visible 20 dp circle
        Box(
            modifier = Modifier
                .align(Alignment.TopEnd)
                .size(48.dp)
                .clickable(role = Role.Button, onClickLabel = "Remove ${state.name}", onClick = onRemove),
            contentAlignment = Alignment.TopEnd
        ) {
            Box(
                modifier = Modifier
                    .padding(top = 8.dp, end = 8.dp)
                    .size(20.dp)
                    .background(Color.White, CircleShape)
                    .padding(4.dp),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    painter = painterResource(R.drawable.ic_attachment_remove),
                    contentDescription = null,
                    tint = Color.Black,
                    modifier = Modifier.fillMaxSize()
                )
            }
        }
    }
}

@Composable
fun FileAttachmentCard(
    state: FileAttachmentUiState,
    onClick: () -> Unit,
    onRemove: () -> Unit,
    modifier: Modifier = Modifier
) {
    val borderColor = colorResource(R.color.app_outline_variant)
    val composerColor = colorResource(R.color.app_surface_container_high)
    val composerControlColor = colorResource(R.color.app_surface_container_highest)
    val primaryColor = colorResource(R.color.app_on_surface)
    val secondaryColor = colorResource(R.color.app_on_surface_variant)
    val shape = RoundedCornerShape(19.dp)
    val fontScale = LocalDensity.current.fontScale.coerceAtLeast(1f)
    val minHeight = (60 * fontScale).dp

    Box(
        modifier = modifier
            .width(240.dp)
            .wrapContentHeight()
            .requiredHeightIn(min = minHeight)
            .clip(shape)
            .background(composerColor, shape)
            .border(BorderStroke(1.dp, borderColor), shape)
            .clickable(onClick = onClick)
            .semantics { contentDescription = state.contentDescription }
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 10.dp, top = 10.dp, end = 32.dp, bottom = 10.dp)
        ) {
            AttachmentFileIconCanvas(
                filename = state.name,
                modifier = Modifier
                    .size(40.dp)
                    .padding(end = 8.dp)
            )

            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.Center
            ) {
                Text(
                    text = formatMiddleEllipsis(state.name, 22),
                    color = primaryColor,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )

                if (state.metadataText.isNotEmpty()) {
                    Text(
                        text = state.metadataText,
                        color = secondaryColor,
                        fontSize = 14.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }

                val statusText = state.uploadState.displayText
                if (statusText.isNotEmpty()) {
                    Text(
                        text = statusText,
                        color = secondaryColor,
                        fontSize = 10.sp,
                        modifier = Modifier.padding(top = 3.dp),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
        }

        // Direct remove target 48 dp
        Box(
            modifier = Modifier
                .align(Alignment.TopEnd)
                .size(48.dp)
                .clickable(role = Role.Button, onClickLabel = "Remove ${state.name}", onClick = onRemove),
            contentAlignment = Alignment.TopEnd
        ) {
            Box(
                modifier = Modifier
                    .padding(top = 8.dp, end = 8.dp)
                    .size(20.dp)
                    .background(composerControlColor, CircleShape)
                    .padding(4.dp),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    painter = painterResource(R.drawable.ic_attachment_remove),
                    contentDescription = null,
                    tint = primaryColor,
                    modifier = Modifier.fillMaxSize()
                )
            }
        }
    }
}

@Composable
fun CapabilityTagChip(
    state: CapabilityTagUiState,
    onClick: () -> Unit,
    onRemove: () -> Unit,
    modifier: Modifier = Modifier
) {
    val borderColor = colorResource(R.color.app_outline_variant)
    val composerControlColor = colorResource(R.color.app_surface_container_highest)
    val primaryColor = colorResource(R.color.app_on_surface)
    val shape = RoundedCornerShape(20.dp)
    val fontScale = LocalDensity.current.fontScale.coerceAtLeast(1f)
    val height = maxOf(48.dp, (40 * fontScale).dp)

    Box(
        modifier = modifier
            .width(160.dp)
            .height(height)
            .clip(shape)
            .background(composerControlColor, shape)
            .border(BorderStroke(1.dp, borderColor), shape)
            .clickable(onClick = onClick)
            .semantics { contentDescription = state.contentDescription }
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .fillMaxSize()
                .padding(start = 10.dp, end = 32.dp)
        ) {
            Text(
                text = formatMiddleEllipsis(state.name, 16),
                color = primaryColor,
                fontSize = 13.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }

        // Direct remove target 48 dp
        Box(
            modifier = Modifier
                .align(Alignment.TopEnd)
                .size(48.dp)
                .clickable(role = Role.Button, onClickLabel = "Remove ${state.name}", onClick = onRemove),
            contentAlignment = Alignment.TopEnd
        ) {
            Box(
                modifier = Modifier
                    .padding(top = 8.dp, end = 8.dp)
                    .size(20.dp)
                    .background(composerControlColor, CircleShape)
                    .padding(4.dp),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    painter = painterResource(R.drawable.ic_attachment_remove),
                    contentDescription = null,
                    tint = primaryColor,
                    modifier = Modifier.fillMaxSize()
                )
            }
        }
    }
}

@Composable
fun ComposerAttachmentStrip(
    items: List<AttachmentItemUiState>,
    onCardClick: (AttachmentItemUiState) -> Unit,
    onRemoveClick: (AttachmentItemUiState) -> Unit,
    modifier: Modifier = Modifier
) {
    if (items.isEmpty()) return

    Row(
        modifier = modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState())
            .padding(start = 10.dp, end = 10.dp, top = 10.dp, bottom = 2.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        items.forEach { item ->
            key(item.localId) {
                when (item) {
                    is ImageAttachmentUiState -> {
                        ImageAttachmentCard(
                            state = item,
                            onClick = { onCardClick(item) },
                            onRemove = { onRemoveClick(item) }
                        )
                    }
                    is FileAttachmentUiState -> {
                        FileAttachmentCard(
                            state = item,
                            onClick = { onCardClick(item) },
                            onRemove = { onRemoveClick(item) }
                        )
                    }
                    is CapabilityTagUiState -> {
                        CapabilityTagChip(
                            state = item,
                            onClick = { onCardClick(item) },
                            onRemove = { onRemoveClick(item) }
                        )
                    }
                }
            }
        }
    }
}
