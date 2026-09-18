package app.codexremote.android.ui.conversation

import android.animation.ValueAnimator
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.MutableTransitionState
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.codexremote.android.MarkdownBlock
import app.codexremote.android.R
import app.codexremote.android.TimelineItem
import app.codexremote.android.presentation.conversation.ConversationController
import app.codexremote.android.ui.LocalRemoteImageScope
import app.codexremote.android.ui.theme.AppColors
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.drop

@Composable
fun ConversationScreen(
    controller: ConversationController,
    composerSlot: @Composable () -> Unit,
    modifier: Modifier = Modifier
) {
    val state = controller.uiState.value
    val imageScope = LocalRemoteImageScope.current
    var threadMenuExpanded by remember { mutableStateOf(false) }
    val seenItemIds = remember(imageScope, state.threadId) { state.items.map { it.id }.toMutableSet() }
    val showThinking = remember(state.isTurnRunning, state.isConnected, state.items) {
        state.isTurnRunning && state.isConnected && !hasActiveStatus(state.items)
    }

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(AppColors.background)
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .statusBarsPadding()
        ) {
            // Header bar
            ConversationHeader(
                controller = controller,
                threadMenuExpanded = threadMenuExpanded,
                onThreadMenuToggle = { threadMenuExpanded = it }
            )

            // Timeline items
            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
            ) {
                if (state.isLoading) {
                    Column(Modifier.fillMaxSize()) {
                        LinearProgressIndicator(
                            modifier = Modifier.fillMaxWidth(),
                            color = AppColors.primary,
                            trackColor = AppColors.surfaceContainerHigh
                        )
                        Text(
                            "Loading conversation…",
                            color = AppColors.onSurfaceVariant,
                            modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp)
                                .semantics { liveRegion = LiveRegionMode.Polite }
                        )
                        ConversationSkeletonComposable(
                            modifier = Modifier.fillMaxWidth().weight(1f).clearAndSetSemantics { }
                        )
                    }
                } else if (state.loadError != null) {
                    Column(
                        modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(24.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.Center
                    ) {
                        Text(
                            state.loadError.orEmpty(),
                            color = AppColors.onSurfaceVariant,
                            textAlign = TextAlign.Center,
                            modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite }
                        )
                        Spacer(Modifier.height(16.dp))
                        Button(onClick = controller::retryLoading) { Text("Retry") }
                    }
                } else if (state.items.isEmpty()) {
                    Box(modifier = Modifier.fillMaxSize()) {
                        EmptyConversationPlaceholder(
                            workspaceLabel = state.workspaceLabel,
                            serverHost = state.serverHost
                        )
                        if (showThinking) {
                            ThinkingIndicator(
                                modifier = Modifier
                                    .align(Alignment.BottomStart)
                                    .padding(start = 16.dp, end = 16.dp, bottom = 16.dp)
                            )
                        }
                    }
                } else {
                    val listState = rememberLazyListState()
                    val coroutineScope = rememberCoroutineScope()

                    var followLatest by remember(imageScope) { mutableStateOf(true) }
                    var autoScrolling by remember { mutableStateOf(false) }
                    val isNearBottom by remember {
                        derivedStateOf {
                            val layout = listState.layoutInfo
                            val last = layout.visibleItemsInfo.lastOrNull()
                            last == null || !listState.canScrollForward ||
                                (last.index >= layout.totalItemsCount - 2 && last.offset + last.size <= layout.viewportEndOffset + 80)
                        }
                    }
                    LaunchedEffect(listState, imageScope) {
                        snapshotFlow { listState.firstVisibleItemIndex to listState.firstVisibleItemScrollOffset }.drop(1).collect {
                            if (!autoScrolling) followLatest = isNearBottom
                        }
                    }
                    // A trailing spacer gives even an over-height streaming item a stable end anchor.
                    LaunchedEffect(imageScope, state.items, showThinking) {
                        if (followLatest && !listState.isScrollInProgress) {
                            autoScrolling = true
                            try { listState.scrollToItem(state.items.size + 1) }
                            finally { autoScrolling = false }
                        }
                    }

                    LazyColumn(
                        state = listState,
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(horizontal = 16.dp),
                        verticalArrangement = Arrangement.spacedBy(14.dp)
                    ) {
                        item { Spacer(modifier = Modifier.height(8.dp)) }

                        items(state.items, key = { it.id }) { item ->
                            val visibleState = remember(imageScope, state.threadId, item.id) {
                                val alreadySeen = !seenItemIds.add(item.id)
                                MutableTransitionState(alreadySeen).apply { targetState = true }
                            }
                            androidx.compose.animation.AnimatedVisibility(
                                visibleState = visibleState,
                                enter = fadeIn(animationSpec = tween(durationMillis = 150)) +
                                        scaleIn(animationSpec = tween(durationMillis = 150), initialScale = 0.96f),
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                when (item.kind) {
                                    TimelineItem.Kind.USER -> {
                                        UserMessageBubble(
                                            item = item,
                                            isExpanded = item.id in state.expandedUserMessages,
                                            onToggleExpand = { controller.toggleUserMessageExpansion(item.id) },
                                            deliveryStatus = state.messageDeliveries[item.id]
                                        )
                                    }
                                    TimelineItem.Kind.ASSISTANT,
                                    TimelineItem.Kind.COMMENTARY -> {
                                        AssistantMessageBubble(
                                            item = item,
                                            onCopy = { controller.copyText(it, "Response") },
                                            onOpenArtifacts = { controller.openArtifacts() },
                                            showCopyAction = item.kind != TimelineItem.Kind.COMMENTARY
                                        )
                                    }
                                    TimelineItem.Kind.REASONING,
                                    TimelineItem.Kind.PLAN -> {
                                        ReasoningPlanBubble(item = item)
                                    }
                                    TimelineItem.Kind.ACTIVITY_GROUP,
                                    TimelineItem.Kind.COMMAND,
                                    TimelineItem.Kind.FILE_CHANGE,
                                    TimelineItem.Kind.TOOL -> {
                                        ToolActivityCard(
                                            item = item,
                                            isExpanded = item.id in state.expandedGroups,
                                            onToggleExpand = { controller.toggleGroupExpansion(item.id) },
                                            onViewDiff = { files, path -> controller.openDiff(files, path) },
                                            onCopy = { controller.copyText(it, "Response") },
                                            onOpenArtifacts = { controller.openArtifacts() },
                                            scopeKey = "${imageScope.orEmpty()}/${state.threadId.orEmpty()}"
                                        )
                                    }
                                    TimelineItem.Kind.ERROR -> {
                                        ErrorMessageBubble(item = item)
                                    }
                                    TimelineItem.Kind.TURN_SEPARATOR -> {
                                        TurnSeparatorBubble(item = item)
                                    }
                                    else -> {
                                        if (item.imagePath?.isNotBlank() == true) {
                                            MarkdownImageComposable(
                                                image = MarkdownBlock.Image(alt = item.label.ifBlank { "Image" }, source = item.imagePath),
                                                onOpenImage = { _, _ -> }
                                            )
                                        } else if (item.text.isNotBlank()) {
                                            Text(
                                                text = item.text,
                                                fontSize = 14.sp,
                                                color = AppColors.onSurfaceVariant
                                            )
                                        }
                                    }
                                }
                            }
                        }

                        item {
                            Column(modifier = Modifier.fillMaxWidth()) {
                                if (showThinking) {
                                    ThinkingIndicator()
                                }
                                Spacer(modifier = Modifier.height(16.dp))
                            }
                        }
                    }

                    // Jump to latest message floating button
                    androidx.compose.animation.AnimatedVisibility(
                        visible = !isNearBottom && state.items.isNotEmpty(),
                        enter = fadeIn() + scaleIn(),
                        exit = fadeOut() + scaleOut(),
                        modifier = Modifier
                            .align(Alignment.BottomEnd)
                            .padding(16.dp)
                    ) {
                        FloatingActionButton(
                            onClick = {
                                coroutineScope.launch {
                                    followLatest = true
                                    autoScrolling = true
                                    try { listState.animateScrollToItem(state.items.size + 1) } finally { autoScrolling = false }
                                }
                            },
                            containerColor = AppColors.surfaceContainerHigh,
                            contentColor = AppColors.primary,
                            shape = CircleShape,
                            modifier = Modifier
                                .size(48.dp)
                                .semantics {
                                    contentDescription = "Scroll to latest message"
                                }
                        ) {
                            Icon(
                                painter = painterResource(R.drawable.ic_codex_chevron_down),
                                contentDescription = "Scroll to latest message",
                                modifier = Modifier.size(20.dp)
                            )
                        }
                    }
                }
            }

            // Bottom composer slot
            composerSlot()
        }

        // Diff Dialog
        if (state.isDiffOpen && state.diffFiles.isNotEmpty()) {
            FileDiffDialog(
                files = state.diffFiles,
                selectedPath = state.selectedDiffPath,
                onSelectFile = { controller.selectDiffFile(it) },
                onCopyPatch = { controller.copyText(it, "Patch") },
                onDismiss = { controller.closeDiff() }
            )
        }

        // Context Usage Dialog
        if (state.showUsagePopup) {
            val percent = state.tokenUsagePercent
            val left = (100 - percent).coerceAtLeast(0)
            val message = if (state.contextWindow != null) {
                "Context window:\n$percent% used ($left% left)\n${state.totalTokens ?: 0} / ${state.contextWindow} tokens used"
            } else {
                "Context window:\nUsage not reported yet"
            }
            AlertDialog(
                onDismissRequest = { controller.toggleUsagePopup(false) },
                title = { Text("Context usage", fontWeight = FontWeight.Bold, color = AppColors.onSurface) },
                text = { Text(message, fontSize = 14.sp, color = AppColors.onSurfaceVariant) },
                confirmButton = {
                    TextButton(onClick = { controller.toggleUsagePopup(false) }) {
                        Text("OK", color = AppColors.primary)
                    }
                },
                containerColor = AppColors.surfaceContainerHigh
            )
        }
    }
}

@Composable
internal fun ReasoningPlanBubble(
    item: TimelineItem,
    modifier: Modifier = Modifier
) {
    Box(
        modifier = modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp)
    ) {
        Text(
            text = item.text,
            fontSize = 13.sp,
            fontStyle = FontStyle.Italic,
            lineHeight = 18.sp,
            color = AppColors.onSurfaceMuted
        )
    }
}

@Composable
private fun TurnSeparatorBubble(
    item: TimelineItem,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        if (item.label.isNotBlank()) {
            Text(
                text = item.label,
                fontSize = 11.sp,
                color = AppColors.onSurfaceMuted
            )
            Spacer(modifier = Modifier.width(8.dp))
        }
        HorizontalDivider(
            color = AppColors.outlineVariant,
            thickness = 1.dp
        )
    }
}

@Composable
private fun ConversationHeader(
    controller: ConversationController,
    threadMenuExpanded: Boolean,
    onThreadMenuToggle: (Boolean) -> Unit,
    modifier: Modifier = Modifier
) {
    val state = controller.uiState.value

    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 8.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        // Menu button: Open remote chats
        IconButton(
            onClick = { controller.openSidebar() },
            modifier = Modifier.semantics {
                contentDescription = "Open remote chats"
            }
        ) {
            Icon(
                painter = painterResource(R.drawable.ic_menu),
                contentDescription = null,
                tint = AppColors.onSurface,
                modifier = Modifier.size(22.dp)
            )
        }

        // Title and workspace / server status
        Column(
            modifier = Modifier
                .weight(1f)
                .padding(horizontal = 4.dp)
        ) {
            Text(
                text = state.threadTitle.ifBlank { "Remote Chat" },
                fontSize = 16.sp,
                fontWeight = FontWeight.Bold,
                color = AppColors.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )

            Row(verticalAlignment = Alignment.CenterVertically) {
                if (state.workspaceLabel.isNotBlank()) {
                    Text(
                        text = state.workspaceLabel,
                        fontSize = 12.sp,
                        color = AppColors.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Text(" · ", fontSize = 12.sp, color = AppColors.onSurfaceMuted)
                }
                Text(
                    text = if (!state.isConnected) state.connectionReason ?: "Disconnected" else state.serverHost.ifBlank { "Connected" },
                    fontSize = 12.sp,
                    color = if (state.isConnected) AppColors.onSurfaceVariant else AppColors.warning,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }

        // Token usage indicator
        if (state.tokenUsagePercent > 0) {
            Box(
                modifier = Modifier
                    .size(36.dp)
                    .clip(RoundedCornerShape(18.dp))
                    .clickable { controller.toggleUsagePopup() }
                    .padding(8.dp),
                contentAlignment = Alignment.Center
            ) {
                val primaryColor = AppColors.primary
                val outlineColor = AppColors.outlineVariant
                Canvas(modifier = Modifier.size(20.dp)) {
                    val strokeWidth = 2.dp.toPx()
                    drawCircle(color = outlineColor, style = Stroke(width = strokeWidth))
                    drawArc(
                        color = primaryColor,
                        startAngle = -90f,
                        sweepAngle = state.tokenUsagePercent * 3.6f,
                        useCenter = false,
                        style = Stroke(width = strokeWidth)
                    )
                }
            }
        }

        // Existing runtime actions
        Box {
            IconButton(onClick = { onThreadMenuToggle(true) }) {
                Icon(
                    painter = painterResource(R.drawable.ic_more_vert),
                    contentDescription = "Thread options",
                    tint = AppColors.onSurfaceVariant,
                    modifier = Modifier.size(20.dp)
                )
            }

            DropdownMenu(
                expanded = threadMenuExpanded,
                onDismissRequest = { onThreadMenuToggle(false) },
                modifier = Modifier.background(AppColors.surfaceContainerHigh)
            ) {
                listOf(
                    "Export logs" to controller::exportDiagnostics,
                    "Task files" to controller::openArtifacts,
                    "Connections" to controller::openConnections,
                    "Enable notifications" to controller::enableNotifications,
                    "Diagnostics" to controller::showDiagnostics,
                    "Model / effort" to controller::openModel,
                    "Permissions" to controller::openPermissions,
                    "Context usage" to { controller.toggleUsagePopup(true) }
                ).forEach { (label, action) ->
                    DropdownMenuItem(text = { Text(label) }, onClick = {
                        onThreadMenuToggle(false)
                        action()
                    })
                }
            }
        }
    }
}

@Composable
fun ConversationSkeletonComposable(modifier: Modifier = Modifier) {
    val transition = rememberInfiniteTransition(label = "shimmer")
    val phase by transition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 2000, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "shimmerPhase"
    )

    val skeletonColor = AppColors.skeleton
    val highlightColor = AppColors.surfaceContainerHigh

    Canvas(
        modifier = modifier
            .padding(16.dp)
            .semantics { contentDescription = "Loading conversation" }
    ) {
        val width = size.width
        val brush = Brush.linearGradient(
            colors = listOf(skeletonColor, highlightColor, skeletonColor),
            start = Offset(x = (phase * 3f - 1.5f) * width, y = 0f),
            end = Offset(x = (phase * 3f - 0.5f) * width, y = 0f)
        )

        // Silhouette 1: User message placeholder
        drawRoundRect(
            brush = brush,
            topLeft = Offset(x = width * 0.22f, y = 21.dp.toPx()),
            size = Size(width = width * 0.78f, height = 48.dp.toPx()),
            cornerRadius = CornerRadius(8.dp.toPx(), 8.dp.toPx())
        )

        // Silhouette 2: Assistant response placeholder
        drawRoundRect(
            brush = brush,
            topLeft = Offset(x = 0f, y = 85.dp.toPx()),
            size = Size(width = width, height = 72.dp.toPx()),
            cornerRadius = CornerRadius(8.dp.toPx(), 8.dp.toPx())
        )
    }
}

@Composable
private fun EmptyConversationPlaceholder(
    workspaceLabel: String,
    serverHost: String,
    modifier: Modifier = Modifier
) {
    Box(
        modifier = modifier
            .fillMaxSize()
            .padding(32.dp),
        contentAlignment = Alignment.Center
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Icon(
                painter = painterResource(R.drawable.ic_computer),
                contentDescription = null,
                tint = AppColors.onSurfaceMuted,
                modifier = Modifier.size(48.dp)
            )
            Spacer(modifier = Modifier.height(16.dp))
            Text(
                text = "Codex Remote",
                fontSize = 18.sp,
                fontWeight = FontWeight.Bold,
                color = AppColors.onSurface
            )
            Spacer(modifier = Modifier.height(6.dp))
            Text(
                text = if (workspaceLabel.isNotBlank()) "Workspace: $workspaceLabel" else "Connected to $serverHost",
                fontSize = 14.sp,
                color = AppColors.onSurfaceVariant,
                textAlign = TextAlign.Center
            )
        }
    }
}
