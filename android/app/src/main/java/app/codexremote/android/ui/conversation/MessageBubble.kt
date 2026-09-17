package app.codexremote.android.ui.conversation

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.sizeIn
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import kotlinx.coroutines.delay
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.foundation.layout.heightIn
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.codexremote.android.ConversationStyle
import app.codexremote.android.MessageAttachment
import app.codexremote.android.R
import app.codexremote.android.RemoteImageContent
import app.codexremote.android.TimelineItem
import app.codexremote.android.presentation.conversation.UserMessageText
import app.codexremote.android.presentation.conversation.MessageDeliveryStatus
import app.codexremote.android.ui.LocalRemoteImagePresenter
import app.codexremote.android.ui.LocalRemoteImageRepository
import app.codexremote.android.ui.LocalRemoteImageScope
import app.codexremote.android.ui.LocalRemoteAttachmentOpener
import app.codexremote.android.ui.theme.AppColors
import java.util.Locale

@Composable
fun UserMessageBubble(
    item: TimelineItem,
    isExpanded: Boolean,
    onToggleExpand: () -> Unit,
    modifier: Modifier = Modifier,
    deliveryStatus: MessageDeliveryStatus? = null
) {
    val messageText = remember(item.text) { UserMessageText.display(item.text) }
    val isLong = messageText.length > 300 || messageText.count { it == '\n' } > 6
    val displayText = if (isLong && !isExpanded) {
        messageText.take(240).trimEnd() + "…"
    } else {
        messageText
    }

    val bubbleShape = RoundedCornerShape(ConversationStyle.USER_RADIUS.dp)

    Box(
        modifier = modifier.fillMaxWidth(),
        contentAlignment = Alignment.CenterEnd
    ) {
        Column(
            horizontalAlignment = Alignment.End,
            modifier = Modifier.fillMaxWidth(0.9f)
        ) {
            // Attached files/images if any, reflowing via FlowRow
            if (item.attachments.isNotEmpty()) {
                @OptIn(ExperimentalLayoutApi::class)
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.padding(bottom = 8.dp)
                ) {
                    item.attachments.forEach { att ->
                        if (att.kind == "image") {
                            UserAttachmentImage(attachment = att)
                        } else {
                            UserAttachmentFile(attachment = att)
                        }
                    }
                }
            }

            // Bubble body
            if (displayText.isNotBlank()) {
                Box(
                    modifier = Modifier
                        .clip(bubbleShape)
                        .background(AppColors.userBubble, bubbleShape)
                        .padding(horizontal = 16.dp, vertical = 12.dp)
                ) {
                    Column {
                        Text(
                            text = displayText,
                            fontSize = 15.sp,
                            lineHeight = 22.sp,
                            color = AppColors.onSurface
                        )

                        if (isLong) {
                            Spacer(modifier = Modifier.height(4.dp))
                            Text(
                                text = if (isExpanded) "Show less" else "Show more",
                                fontSize = 13.sp,
                                fontWeight = FontWeight.Bold,
                                color = AppColors.primary,
                                modifier = Modifier
                                    .clickable { onToggleExpand() }
                                    .padding(vertical = 2.dp)
                            )
                        }
                    }
                }
            }

            if (deliveryStatus != null) {
                UserDeliveryStatusText(
                    status = deliveryStatus,
                    modifier = Modifier
                        .align(Alignment.End)
                        .padding(top = 2.dp)
                )
            }
        }
    }
}

@Composable
private fun UserDeliveryStatusText(
    status: MessageDeliveryStatus,
    modifier: Modifier = Modifier
) {
    when (status) {
        MessageDeliveryStatus.SENDING -> {
            var dotCount by remember(status) { mutableStateOf(1) }
            LaunchedEffect(status) {
                while (true) {
                    delay(400L)
                    dotCount = (dotCount % 3) + 1
                }
            }
            val dots = when (dotCount) {
                1 -> "."
                2 -> ".."
                else -> "..."
            }
            Box(
                modifier = modifier,
                contentAlignment = Alignment.CenterStart
            ) {
                Text(
                    text = "sending...",
                    fontSize = 11.sp,
                    color = Color.Transparent,
                    maxLines = 1,
                    modifier = Modifier.clearAndSetSemantics { }
                )
                Text(
                    text = "sending$dots",
                    fontSize = 11.sp,
                    color = AppColors.onSurfaceMuted,
                    maxLines = 1
                )
            }
        }
        MessageDeliveryStatus.SENT -> {
            Text(
                text = "sent",
                fontSize = 11.sp,
                color = AppColors.onSurfaceMuted,
                maxLines = 1,
                modifier = modifier
            )
        }
    }
}

@Composable
private fun UserAttachmentImage(
    attachment: MessageAttachment,
    modifier: Modifier = Modifier
) {
    val imageRepo = LocalRemoteImageRepository.current
    val imageScope = LocalRemoteImageScope.current
    val presenter = LocalRemoteImagePresenter.current
    val source = remember(attachment.id, attachment.name) {
        "remote-attachment://${attachment.id}-${attachment.name}"
    }
    var content by remember(source, imageScope) { mutableStateOf<RemoteImageContent?>(null) }

    var retryTrigger by remember(source, imageScope) { mutableStateOf(0) }
    DisposableEffect(source, imageScope, imageRepo, retryTrigger) {
        if (imageRepo == null) return@DisposableEffect onDispose {}
        val cancel = imageRepo.loadImage(source, attachment.name) { result ->
            content = result
        }
        onDispose { cancel() }
    }

    val shape = RoundedCornerShape(12.dp)
    val bitmap = content?.bitmap

    if (bitmap != null) {
        val imgWidth = bitmap.width.toFloat().coerceAtLeast(1f)
        val imgHeight = bitmap.height.toFloat().coerceAtLeast(1f)
        val aspectRatio = (imgWidth / imgHeight).coerceIn(0.4f, 2.5f)
        Box(
            modifier = modifier
                .sizeIn(minWidth = 64.dp, minHeight = 64.dp, maxWidth = 200.dp, maxHeight = 200.dp)
                .aspectRatio(aspectRatio)
                .clip(shape)
                .background(AppColors.surfaceContainerHigh, shape)
                .border(1.dp, AppColors.outlineVariant, shape)
                .clickable {
                    content?.let { presenter(it, attachment.name) }
                }
                .semantics { contentDescription = attachment.name }
        ) {
            Image(
                bitmap = bitmap.asImageBitmap(),
                contentDescription = attachment.name,
                contentScale = ContentScale.Fit,
                modifier = Modifier.fillMaxSize()
            )
        }
    } else {
        Box(
            modifier = modifier
                .sizeIn(minWidth = 72.dp, minHeight = 72.dp, maxWidth = 180.dp)
                .clip(shape)
                .background(AppColors.surfaceContainerHigh, shape)
                .border(1.dp, AppColors.outlineVariant, shape)
                .clickable {
                    if (content?.error != null && imageRepo != null) {
                        content = null
                        retryTrigger++
                    }
                },
            contentAlignment = Alignment.Center
        ) {
            if (content?.error != null) {
                Text(
                    text = content?.error.orEmpty() + "\nTap to retry",
                    fontSize = 10.sp,
                    color = AppColors.error,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.padding(4.dp)
                )
            } else {
                CircularProgressIndicator(
                    modifier = Modifier.size(20.dp),
                    strokeWidth = 2.dp,
                    color = AppColors.primary
                )
            }
        }
    }
}

@Composable
private fun UserAttachmentFile(
    attachment: MessageAttachment,
    modifier: Modifier = Modifier
) {
    val openAttachment = LocalRemoteAttachmentOpener.current
    val shape = RoundedCornerShape(12.dp)
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = modifier
            .widthIn(max = 220.dp)
            .heightIn(min = 48.dp)
            .clip(shape)
            .background(AppColors.surfaceContainerHigh, shape)
            .border(1.dp, AppColors.outlineVariant, shape)
            .clickable(onClickLabel = "Open attachment") { openAttachment(attachment) }
            .padding(horizontal = 8.dp, vertical = 6.dp)
            .semantics { contentDescription = "Attached ${attachment.kind}: ${attachment.name}" }
    ) {
        ComposeAttachmentFileIcon(filename = attachment.name)
        Spacer(modifier = Modifier.width(8.dp))
        Column(modifier = Modifier.weight(1f, fill = false)) {
            Text(
                text = attachment.name,
                fontSize = 12.sp,
                fontWeight = FontWeight.SemiBold,
                color = AppColors.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                text = formatAttachmentMetadata(attachment.name),
                fontSize = 11.sp,
                color = AppColors.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

@Composable
private fun ComposeAttachmentFileIcon(filename: String, modifier: Modifier = Modifier) {
    val extension = remember(filename) {
        filename.substringAfterLast('.', "FILE").uppercase(Locale.ROOT)
    }
    val label = remember(extension) { extension.take(4) }
    val fill = remember(extension) {
        when (extension) {
            "PDF" -> androidx.compose.ui.graphics.Color(0xFFEA4335)
            "CSV", "XLS", "XLSX" -> androidx.compose.ui.graphics.Color(0xFF188038)
            "PPT", "PPTX" -> androidx.compose.ui.graphics.Color(0xFFF9AB00)
            "ZIP", "GZ", "TAR" -> androidx.compose.ui.graphics.Color(0xFF7F56D9)
            else -> androidx.compose.ui.graphics.Color(0xFF4285F4)
        }
    }

    Box(
        modifier = modifier
            .size(width = 28.dp, height = 34.dp)
            .clip(RoundedCornerShape(topStart = 4.dp, bottomStart = 4.dp, bottomEnd = 4.dp, topEnd = 8.dp))
            .background(fill),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = label,
            color = androidx.compose.ui.graphics.Color.White,
            fontWeight = FontWeight.Bold,
            fontSize = if (label.length > 3) 8.sp else 10.sp,
            maxLines = 1
        )
    }
}

private fun formatAttachmentMetadata(filename: String): String {
    val extension = filename.substringAfterLast('.', "").uppercase(Locale.ROOT)
    return when (extension) {
        "" -> "File"
        "ZIP", "GZ", "TAR" -> "$extension archive"
        else -> extension
    }
}

@Composable
fun AssistantMessageBubble(
    item: TimelineItem,
    onCopy: (String) -> Unit,
    onOpenArtifacts: () -> Unit = {},
    modifier: Modifier = Modifier,
    showCopyAction: Boolean = true
) {
    val uriHandler = LocalUriHandler.current
    var copied by remember(item.id) { mutableStateOf(false) }
    LaunchedEffect(copied) { if (copied) { delay(2000); copied = false } }

    Box(
        modifier = modifier.fillMaxWidth(),
        contentAlignment = Alignment.CenterStart
    ) {
        Column(
            horizontalAlignment = Alignment.Start,
            modifier = Modifier.fillMaxWidth()
        ) {
            ComposeMarkdown(
                markdown = item.text,
                onOpenUrl = { url ->
                    runCatching { uriHandler.openUri(url) }
                },
                onCopyCode = onCopy,
                onOpenArtifacts = onOpenArtifacts
            )

            if (showCopyAction) {
                Spacer(modifier = Modifier.height(4.dp))

                // Action row: Copy response
                Row(
                    modifier = Modifier
                        .heightIn(min = 48.dp)
                        .clip(RoundedCornerShape(6.dp))
                        .clickable {
                            onCopy(item.text)
                            copied = true
                        }
                        .padding(horizontal = 8.dp, vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        painter = painterResource(if (copied) R.drawable.ic_check else R.drawable.ic_copy_response),
                        contentDescription = "Copy response",
                        tint = if (copied) AppColors.success else AppColors.onSurfaceMuted,
                        modifier = Modifier.size(14.dp)
                    )
                    Spacer(modifier = Modifier.width(4.dp))
                    Text(
                        text = if (copied) "Copied" else "Copy",
                        fontSize = 11.sp,
                        color = if (copied) AppColors.success else AppColors.onSurfaceMuted
                    )
                }
            }
        }
    }
}

@Composable
fun ErrorMessageBubble(
    item: TimelineItem,
    modifier: Modifier = Modifier
) {
    val shape = RoundedCornerShape(12.dp)
    Box(
        modifier = modifier
            .fillMaxWidth()
            .clip(shape)
            .background(AppColors.errorContainer, shape)
            .padding(14.dp)
    ) {
        Column {
            Text(
                text = item.label.ifBlank { "Error" },
                fontSize = 13.sp,
                fontWeight = FontWeight.Bold,
                color = AppColors.error
            )
            if (item.text.isNotBlank()) {
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = item.text,
                    fontSize = 13.sp,
                    color = AppColors.error
                )
            }
        }
    }
}
