package app.codexremote.android.ui.conversation

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import app.codexremote.android.R
import app.codexremote.android.SubagentReference
import app.codexremote.android.TimelineItem
import app.codexremote.android.presentation.conversation.SubagentViewerController
import app.codexremote.android.presentation.conversation.SubagentViewerState
import app.codexremote.android.ui.theme.AppColors
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import java.util.Locale

/**
 * Small four-square outlined grid icon corresponding to Scheme A quiet header icon.
 */
@Composable
private fun SubagentGridIcon(
    modifier: Modifier = Modifier,
    tint: Color = AppColors.primary,
) {
    Canvas(modifier = modifier.size(16.dp)) {
        val s = size.width / 24f
        val strokeWidth = (2f * s).coerceAtLeast(1f)
        val rectSize = Size(7f * s, 7f * s)
        val stroke = Stroke(width = strokeWidth)

        drawRect(
            color = tint,
            topLeft = Offset(3f * s, 3f * s),
            size = rectSize,
            style = stroke,
        )
        drawRect(
            color = tint,
            topLeft = Offset(14f * s, 3f * s),
            size = rectSize,
            style = stroke,
        )
        drawRect(
            color = tint,
            topLeft = Offset(3f * s, 14f * s),
            size = rectSize,
            style = stroke,
        )
        drawRect(
            color = tint,
            topLeft = Offset(14f * s, 14f * s),
            size = rectSize,
            style = stroke,
        )
    }
}

/**
 * Compact expandable strip displaying discovered subagents with status, summary, and model.
 * Adopts Scheme A quiet grouped surface (16dp rounded corners, quiet header row, status dots, grid icon).
 * Expanded state includes shared horizontal status filters and quiet rows.
 * Tapping a subagent opens the full-height SubagentViewer inspector.
 */
@Composable
fun SubagentStrip(
    items: List<TimelineItem>,
    viewer: SubagentViewerController,
    modifier: Modifier = Modifier,
    onBrowseAll: (() -> Unit)? = null,
) {
    val subagents = remember(items) { SubagentReference.collect(items) }
    if (subagents.isEmpty()) return

    var userExpanded by remember { mutableStateOf<Boolean?>(null) }
    val expanded = userExpanded ?: true

    var selectedFilter by remember { mutableStateOf(SubagentStatusFilter.ALL) }
    val shape = RoundedCornerShape(16.dp)

    val filterCounts = remember(subagents) {
        SubagentStatusFilter.entries.associateWith { filter ->
            if (filter == SubagentStatusFilter.ALL) {
                subagents.size
            } else {
                subagents.count { filter.matches(it.status) }
            }
        }
    }

    val filteredSubagents = remember(subagents, selectedFilter) {
        if (selectedFilter == SubagentStatusFilter.ALL) {
            subagents
        } else {
            subagents.filter { selectedFilter.matches(it.status) }
        }
    }

    val successColor = AppColors.success
    val runningColor = AppColors.running
    val warningColor = AppColors.warning
    val neutralColor = AppColors.onSurfaceMuted

    val statusDots = remember(subagents, successColor, runningColor, warningColor, neutralColor) {
        if (subagents.isEmpty()) emptyList<Color>()
        else {
            val hasCompleted = subagents.any { SubagentStatusFilter.COMPLETED.matches(it.status) }
            val hasRunning = subagents.any { SubagentStatusFilter.RUNNING.matches(it.status) }
            val hasAttention = subagents.any { SubagentStatusFilter.NEEDS_ATTENTION.matches(it.status) }
            val hasNeutral = subagents.any {
                !SubagentStatusFilter.COMPLETED.matches(it.status) &&
                !SubagentStatusFilter.RUNNING.matches(it.status) &&
                !SubagentStatusFilter.NEEDS_ATTENTION.matches(it.status)
            }
            buildList {
                if (hasCompleted) add(successColor)
                if (hasRunning) add(runningColor)
                if (hasAttention) add(warningColor)
                if (hasNeutral) add(neutralColor)
            }
        }
    }

    Box(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 4.dp)
            .clip(shape)
            .background(AppColors.surfaceContainerLow)
            .border(BorderStroke(1.dp, AppColors.outlineVariant), shape)
    ) {
        Column(modifier = Modifier.fillMaxWidth()) {
            // Accessible strip header (single clear expand/collapse target, min 48dp touch target)
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 48.dp)
                    .clickable { userExpanded = !expanded }
                    .semantics {
                        contentDescription = if (expanded) {
                            "Collapse subagents list (${subagents.size} subagents)"
                        } else {
                            "Expand subagents list (${subagents.size} subagents)"
                        }
                    }
                    .padding(horizontal = 14.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                // Left: small grid icon and compact title Subagents (N)
                Row(
                    modifier = Modifier.weight(1f, fill = false),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    SubagentGridIcon(tint = AppColors.primary)
                    Text(
                        text = "Subagents (${subagents.size})",
                        fontSize = 13.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = AppColors.onSurface,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }

                Spacer(modifier = Modifier.width(8.dp))

                // Right: status indicator dots and chevron (Scheme A quiet-indicator-dots)
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    statusDots.forEach { dotColor ->
                        Box(
                            modifier = Modifier
                                .size(7.dp)
                                .clip(CircleShape)
                                .background(dotColor)
                        )
                    }
                    Spacer(modifier = Modifier.width(4.dp))
                    Icon(
                        painter = painterResource(
                            if (expanded) R.drawable.ic_expand_less else R.drawable.ic_codex_chevron_down
                        ),
                        contentDescription = null,
                        tint = AppColors.onSurfaceMuted,
                        modifier = Modifier.size(16.dp)
                    )
                }
            }

            // Expanded vertical list detailing name, status, model, and message
            // Capped at max 260.dp height with vertical scroll to avoid consuming screen
            AnimatedVisibility(
                visible = expanded,
                enter = fadeIn() + expandVertically(),
                exit = fadeOut() + shrinkVertically()
            ) {
                Column(modifier = Modifier.fillMaxWidth()) {
                    HorizontalDivider(
                        color = AppColors.outlineVariant.copy(alpha = 0.4f),
                        thickness = 0.5.dp
                    )

                    // Shared horizontal status filters
                    SubagentStatusFilterBar(
                        selectedFilter = selectedFilter,
                        onFilterSelected = { selectedFilter = it },
                        counts = filterCounts
                    )

                    HorizontalDivider(
                        color = AppColors.outlineVariant.copy(alpha = 0.3f),
                        thickness = 0.5.dp
                    )

                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(max = 260.dp)
                            .verticalScroll(rememberScrollState())
                    ) {
                        if (filteredSubagents.isEmpty()) {
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(vertical = 20.dp, horizontal = 16.dp),
                                contentAlignment = Alignment.Center
                            ) {
                                Text(
                                    text = "No subagents matching \"${selectedFilter.label}\"",
                                    fontSize = 12.sp,
                                    color = AppColors.onSurfaceMuted,
                                    textAlign = TextAlign.Center
                                )
                            }
                        } else {
                            filteredSubagents.forEach { agent ->
                                StripSubagentRow(
                                    agent = agent,
                                    allAgents = subagents,
                                    onClick = { viewer.open(agent) }
                                )
                            }
                        }
                    }

                    // Quiet TextButton / footer for browsing all subagents when expanded
                    if (onBrowseAll != null) {
                        HorizontalDivider(
                            color = AppColors.outlineVariant.copy(alpha = 0.3f),
                            thickness = 0.5.dp
                        )
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .heightIn(min = 48.dp)
                                .clickable(onClick = onBrowseAll)
                                .semantics { contentDescription = "All subagents" }
                                .padding(horizontal = 14.dp, vertical = 8.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                text = "All subagents",
                                fontSize = 13.sp,
                                fontWeight = FontWeight.SemiBold,
                                color = AppColors.primary
                            )
                        }
                    }
                }
            }
        }
    }
}

/**
 * Restrained quiet row inside the expanded SubagentStrip.
 * Employs a small status dot, task name, secondary model, and summary message on a single surface.
 */
@Composable
private fun StripSubagentRow(
    agent: SubagentReference,
    allAgents: List<SubagentReference>,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val displayName = disambiguateName(agent, allAgents)
    val shortId = shortThreadId(agent.threadId)

    Column(modifier = modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable(onClick = onClick)
                .heightIn(min = 48.dp)
                .padding(horizontal = 12.dp, vertical = 8.dp)
                .semantics {
                    contentDescription = "Open subagent $displayName, status ${subagentStatusLabel(agent.status)}"
                },
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Subagent avatar replaces identity placeholder dot
            SubagentAvatar(
                threadId = agent.threadId,
                size = 28.dp
            )

            Spacer(modifier = Modifier.width(10.dp))

            // Main content column
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(2.dp)
            ) {
                // Line 1: Task name & status text
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text(
                        text = displayName,
                        fontSize = 13.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = AppColors.onSurface,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f, fill = false)
                    )

                    Spacer(modifier = Modifier.width(8.dp))

                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        Box(
                            modifier = Modifier
                                .size(6.dp)
                                .clip(CircleShape)
                                .background(subagentStatusColor(agent.status))
                        )
                        Text(
                            text = subagentStatusLabel(agent.status),
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Medium,
                            color = subagentStatusColor(agent.status),
                            maxLines = 1
                        )
                    }
                }

                // Line 2: Secondary model and de-emphasized short ID
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    if (agent.model.isNotBlank()) {
                        Text(
                            text = agent.model,
                            fontSize = 11.sp,
                            color = AppColors.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                    if (shortId.isNotBlank()) {
                        if (agent.model.isNotBlank()) {
                            Text("·", fontSize = 11.sp, color = AppColors.onSurfaceMuted)
                        }
                        Text(
                            text = "#$shortId",
                            fontSize = 10.sp,
                            fontFamily = FontFamily.Monospace,
                            color = AppColors.onSurfaceMuted.copy(alpha = 0.7f),
                            maxLines = 1
                        )
                    }
                }

                // Line 3: Message / summary when available
                if (agent.message.isNotBlank()) {
                    Text(
                        text = agent.message,
                        fontSize = 12.sp,
                        color = AppColors.onSurfaceVariant,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                        lineHeight = 16.sp
                    )
                }
            }

            Spacer(modifier = Modifier.width(8.dp))

            // Subtle quiet chevron
            Text(
                text = "›",
                fontSize = 16.sp,
                fontWeight = FontWeight.Bold,
                color = AppColors.onSurfaceMuted
            )
        }

        HorizontalDivider(
            color = AppColors.outlineVariant.copy(alpha = 0.25f),
            thickness = 0.5.dp
        )
    }
}

/**
 * Read-only full-height dialog viewer for a selected subagent.
 * Displays activity timeline, child subagent links, status, and model.
 * Features safe bottom insets and performs 3-second lifecycle-aware polling while visible.
 */
@Composable
fun SubagentViewer(
    viewer: SubagentViewerController,
    modifier: Modifier = Modifier,
) {
    val state = viewer.uiState.value
    val agent = state.agent ?: return

    // Navigation back-stack for nested subagents
    var navStack by remember { mutableStateOf(listOf<SubagentReference>()) }

    // Close on disposal
    DisposableEffect(viewer) {
        onDispose {
            viewer.close()
        }
    }

    // Lifecycle-aware 3-second polling; stop on error until Retry
    val lifecycleOwner = LocalLifecycleOwner.current
    LaunchedEffect(viewer, lifecycleOwner, state.error, agent.threadId) {
        if (state.error != null) return@LaunchedEffect
        lifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
            while (isActive) {
                delay(3000L)
                if (viewer.uiState.value.error != null) break
                viewer.refresh()
            }
        }
    }

    Dialog(
        onDismissRequest = {
            val previous = navStack.lastOrNull()
            if (previous != null) {
                navStack = navStack.dropLast(1)
                viewer.open(previous)
            } else {
                navStack = emptyList()
                viewer.close()
            }
        },
        properties = DialogProperties(
            usePlatformDefaultWidth = false,
            dismissOnBackPress = true,
            dismissOnClickOutside = false
        )
    ) {
        Surface(
            modifier = modifier
                .fillMaxSize()
                .background(AppColors.background)
                .safeDrawingPadding(),
            color = AppColors.background
        ) {
            Column(
                modifier = Modifier.fillMaxSize()
            ) {
                // Header with back/close, name, status, model, short ID, and read-only / direct input capability
                SubagentViewerHeader(
                    agent = agent,
                    canAcceptDirectInput = state.canAcceptDirectInput,
                    hasBack = navStack.isNotEmpty(),
                    onBack = {
                        val previous = navStack.lastOrNull()
                        if (previous != null) {
                            navStack = navStack.dropLast(1)
                            viewer.open(previous)
                        } else {
                            navStack = emptyList()
                            viewer.close()
                        }
                    },
                    onClose = {
                        navStack = emptyList()
                        viewer.close()
                    }
                )

                HorizontalDivider(color = AppColors.outlineVariant, thickness = 1.dp)

                // Loading bar
                if (state.loading) {
                    LinearProgressIndicator(
                        modifier = Modifier.fillMaxWidth(),
                        color = AppColors.primary,
                        trackColor = AppColors.surfaceContainerHigh
                    )
                }

                // Error banner / view
                if (state.error != null) {
                    SubagentErrorView(
                        errorMessage = state.error,
                        onRetry = { viewer.refresh() },
                        isFullScreen = state.items.isEmpty()
                    )
                }

                // Activity timeline
                if (state.items.isNotEmpty() || (state.canAcceptDirectInput && !state.loading && state.error == null)) {
                    val childSubagents = remember(state.items) { SubagentReference.collect(state.items) }
                    val listState = rememberLazyListState()

                    SelectionContainer(
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxWidth()
                    ) {
                        LazyColumn(
                            state = listState,
                            modifier = Modifier
                                .fillMaxSize()
                                .padding(horizontal = 16.dp),
                            verticalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            item(key = "viewer_top_spacer") {
                                Spacer(modifier = Modifier.height(8.dp))
                            }

                            // Direct input / Open task card
                            if (state.canAcceptDirectInput && !state.loading && state.error == null) {
                                item(key = "viewer_open_task_card") {
                                    OpenTaskCard(onOpenAsTask = { viewer.openAsTask() })
                                }
                            }

                            // Nested child agents section if any exist
                            if (childSubagents.isNotEmpty()) {
                                item(key = "nested_child_agents_bar") {
                                    NestedSubagentsBar(
                                        childSubagents = childSubagents,
                                        onOpenChild = { child ->
                                            navStack = navStack + agent
                                            viewer.open(child)
                                        }
                                    )
                                }
                            }

                            if (state.items.isNotEmpty()) {
                                // Safe unique keys using id and index
                                itemsIndexed(
                                    items = state.items,
                                    key = { index, item ->
                                        val safeId = item.id.trim()
                                        if (safeId.isNotBlank()) "${safeId}_$index" else "item_$index"
                                    }
                                ) { _, item ->
                                    SubagentTimelineItemView(
                                        item = item,
                                        onOpenSubagent = { childRef ->
                                            navStack = navStack + agent
                                            viewer.open(childRef)
                                        }
                                    )
                                }
                            } else if (!state.loading) {
                                item(key = "viewer_empty_state") {
                                    Box(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .padding(32.dp),
                                        contentAlignment = Alignment.Center
                                    ) {
                                        Text(
                                            text = "No activity yet for this subagent.",
                                            fontSize = 14.sp,
                                            color = AppColors.onSurfaceMuted,
                                            textAlign = TextAlign.Center
                                        )
                                    }
                                }
                            }

                            item(key = "viewer_bottom_spacer") {
                                Spacer(modifier = Modifier.height(16.dp))
                            }
                        }
                    }
                } else if (!state.loading && state.error == null) {
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxWidth()
                            .padding(32.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = "No activity yet for this subagent.",
                            fontSize = 14.sp,
                            color = AppColors.onSurfaceMuted,
                            textAlign = TextAlign.Center
                        )
                    }
                } else if (state.loading && state.items.isEmpty()) {
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxWidth()
                            .padding(32.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = "Loading subagent…",
                            fontSize = 14.sp,
                            color = AppColors.onSurfaceVariant,
                            textAlign = TextAlign.Center
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun OpenTaskCard(
    onOpenAsTask: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val shape = RoundedCornerShape(10.dp)
    Box(
        modifier = modifier
            .fillMaxWidth()
            .clip(shape)
            .background(AppColors.surfaceContainerLow)
            .border(BorderStroke(1.dp, AppColors.outlineVariant), shape)
            .padding(12.dp)
    ) {
        Column(
            modifier = Modifier.fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    modifier = Modifier.weight(1f, fill = false)
                ) {
                    Box(
                        modifier = Modifier
                            .size(8.dp)
                            .clip(CircleShape)
                            .background(AppColors.primary)
                    )
                    Text(
                        text = "Direct input",
                        fontSize = 13.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = AppColors.onSurface,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }

                Spacer(modifier = Modifier.width(8.dp))

                Button(
                    onClick = onOpenAsTask,
                    modifier = Modifier.heightIn(min = 48.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = AppColors.primary,
                        contentColor = AppColors.onPrimary
                    ),
                    shape = RoundedCornerShape(8.dp)
                ) {
                    Text(
                        text = "Open task",
                        fontSize = 13.sp,
                        fontWeight = FontWeight.SemiBold
                    )
                }
            }

            Text(
                text = "This subagent accepts direct input. It opens the normal conversation; no automatic message or cancellation.",
                fontSize = 12.sp,
                color = AppColors.onSurfaceVariant,
                lineHeight = 16.sp
            )
        }
    }
}

/**
 * Simplified subagent detail header with clean typography and no duplicate chips.
 * Ensures 48dp minimum touch target for action controls and respects dynamic font scaling.
 */
@Composable
private fun SubagentViewerHeader(
    agent: SubagentReference,
    canAcceptDirectInput: Boolean,
    hasBack: Boolean,
    onBack: () -> Unit,
    onClose: () -> Unit,
    modifier: Modifier = Modifier
) {
    val shortId = shortThreadId(agent.threadId)
    val displayName = if (agent.name.isNotBlank()) agent.name else "Subagent"

    Row(
        modifier = modifier
            .fillMaxWidth()
            .heightIn(min = 56.dp)
            .background(AppColors.surfaceContainerLow)
            .padding(horizontal = 8.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        if (hasBack) {
            IconButton(
                onClick = onBack,
                modifier = Modifier
                    .size(48.dp)
                    .semantics { contentDescription = "Back to previous subagent" }
            ) {
                Text(
                    text = "←",
                    fontSize = 20.sp,
                    fontWeight = FontWeight.Bold,
                    color = AppColors.onSurface
                )
            }
        }

        SubagentAvatar(
            threadId = agent.threadId,
            size = 28.dp,
            modifier = Modifier.padding(start = if (hasBack) 0.dp else 4.dp, end = 4.dp)
        )

        Column(
            modifier = Modifier
                .weight(1f)
                .padding(horizontal = 8.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            // Line 1: Display name and status indicator
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(
                    text = displayName,
                    fontSize = 16.sp,
                    fontWeight = FontWeight.Bold,
                    color = AppColors.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false)
                )

                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    Box(
                        modifier = Modifier
                            .size(7.dp)
                            .clip(CircleShape)
                            .background(subagentStatusColor(agent.status))
                    )
                    Text(
                        text = subagentStatusLabel(agent.status),
                        fontSize = 12.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = subagentStatusColor(agent.status),
                        maxLines = 1
                    )
                }
            }

            // Line 2: Capability, model, and de-emphasized short ID
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(
                    text = if (canAcceptDirectInput) "Direct input" else "Read-only",
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Medium,
                    color = if (canAcceptDirectInput) AppColors.primary else AppColors.onSurfaceMuted,
                    maxLines = 1
                )

                if (agent.model.isNotBlank()) {
                    Text("·", fontSize = 12.sp, color = AppColors.onSurfaceMuted)
                    Text(
                        text = agent.model,
                        fontSize = 12.sp,
                        color = AppColors.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }

                if (shortId.isNotBlank()) {
                    Text("·", fontSize = 12.sp, color = AppColors.onSurfaceMuted)
                    Text(
                        text = "#$shortId",
                        fontSize = 11.sp,
                        fontFamily = FontFamily.Monospace,
                        color = AppColors.onSurfaceMuted.copy(alpha = 0.7f),
                        maxLines = 1
                    )
                }
            }
        }

        IconButton(
            onClick = onClose,
            modifier = Modifier
                .size(48.dp)
                .semantics { contentDescription = "Close subagent viewer" }
        ) {
            Text(
                text = "✕",
                fontSize = 18.sp,
                fontWeight = FontWeight.Bold,
                color = AppColors.onSurface
            )
        }
    }
}

@Composable
private fun SubagentErrorView(
    errorMessage: String,
    onRetry: () -> Unit,
    isFullScreen: Boolean,
    modifier: Modifier = Modifier
) {
    if (isFullScreen) {
        Column(
            modifier = modifier
                .fillMaxWidth()
                .padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            Text(
                text = errorMessage,
                color = AppColors.error,
                fontSize = 14.sp,
                textAlign = TextAlign.Center,
                modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite }
            )
            Spacer(modifier = Modifier.height(16.dp))
            Button(
                onClick = onRetry,
                colors = ButtonDefaults.buttonColors(
                    containerColor = AppColors.primary,
                    contentColor = AppColors.onPrimary
                ),
                modifier = Modifier.heightIn(min = 48.dp)
            ) {
                Text("Retry", fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
            }
        }
    } else {
        Row(
            modifier = modifier
                .fillMaxWidth()
                .background(AppColors.errorContainer)
                .padding(horizontal = 16.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text(
                text = errorMessage,
                color = AppColors.error,
                fontSize = 12.sp,
                modifier = Modifier.weight(1f)
            )
            Spacer(modifier = Modifier.width(8.dp))
            Button(
                onClick = onRetry,
                colors = ButtonDefaults.buttonColors(
                    containerColor = AppColors.error,
                    contentColor = Color.White
                ),
                modifier = Modifier.heightIn(min = 48.dp)
            ) {
                Text("Retry", fontSize = 12.sp)
            }
        }
    }
}

@Composable
private fun NestedSubagentsBar(
    childSubagents: List<SubagentReference>,
    onOpenChild: (SubagentReference) -> Unit,
    modifier: Modifier = Modifier
) {
    val barShape = RoundedCornerShape(8.dp)
    Column(
        modifier = modifier
            .fillMaxWidth()
            .clip(barShape)
            .background(AppColors.surfaceContainerLow)
            .border(BorderStroke(1.dp, AppColors.outlineVariant), barShape)
            .padding(10.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        Text(
            text = "Child subagents (${childSubagents.size})",
            fontSize = 12.sp,
            fontWeight = FontWeight.SemiBold,
            color = AppColors.onSurfaceVariant
        )
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            childSubagents.forEach { child ->
                val shortId = shortThreadId(child.threadId)
                val displayName = if (child.name.isNotBlank()) child.name else "Subagent"
                val nameWithId = if (shortId.isNotBlank()) "$displayName ($shortId)" else displayName

                CompactSubagentChip(
                    threadId = child.threadId,
                    name = nameWithId,
                    status = child.status,
                    onClick = { onOpenChild(child) }
                )
            }
        }
    }
}

@Composable
private fun CompactSubagentChip(
    threadId: String,
    name: String,
    status: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val chipShape = RoundedCornerShape(8.dp)
    Box(
        modifier = modifier
            .clip(chipShape)
            .background(AppColors.surfaceContainer)
            .border(BorderStroke(0.5.dp, AppColors.outlineVariant), chipShape)
            .clickable(onClick = onClick)
            .heightIn(min = 48.dp)
            .padding(horizontal = 10.dp, vertical = 6.dp)
            .semantics {
                contentDescription = "Open subagent $name, status ${subagentStatusLabel(status)}"
            },
        contentAlignment = Alignment.Center
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            SubagentAvatar(
                threadId = threadId,
                size = 18.dp
            )
            Box(
                modifier = Modifier
                    .size(6.dp)
                    .clip(CircleShape)
                    .background(subagentStatusColor(status))
            )
            Text(
                text = name,
                fontSize = 12.sp,
                fontWeight = FontWeight.Medium,
                color = AppColors.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                text = subagentStatusLabel(status),
                fontSize = 11.sp,
                color = subagentStatusColor(status),
                maxLines = 1
            )
        }
    }
}

@Composable
private fun SubagentTimelineItemView(
    item: TimelineItem,
    onOpenSubagent: (SubagentReference) -> Unit,
    modifier: Modifier = Modifier
) {
    val shape = RoundedCornerShape(8.dp)

    when (item.kind) {
        TimelineItem.Kind.USER -> {
            Box(
                modifier = modifier
                    .fillMaxWidth()
                    .clip(shape)
                    .background(AppColors.surfaceContainerHigh)
                    .border(BorderStroke(0.5.dp, AppColors.outlineVariant), shape)
                    .padding(12.dp)
            ) {
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(
                        text = item.label.ifBlank { "User" },
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold,
                        color = AppColors.primary
                    )
                    Text(
                        text = item.text,
                        fontSize = 14.sp,
                        color = AppColors.onSurface,
                        lineHeight = 20.sp
                    )
                }
            }
        }

        TimelineItem.Kind.ASSISTANT,
        TimelineItem.Kind.COMMENTARY -> {
            Box(
                modifier = modifier
                    .fillMaxWidth()
                    .clip(shape)
                    .background(AppColors.surfaceContainerLow)
                    .border(BorderStroke(0.5.dp, AppColors.outlineVariant.copy(alpha = 0.5f)), shape)
                    .padding(12.dp)
            ) {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(
                        text = item.label.ifBlank { "Assistant" },
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold,
                        color = AppColors.onSurfaceVariant
                    )
                    if (item.text.isNotBlank()) {
                        Text(
                            text = item.text,
                            fontSize = 14.sp,
                            color = AppColors.onSurface,
                            lineHeight = 20.sp
                        )
                    }
                    if (item.subagents.isNotEmpty()) {
                        SubagentItemLinks(subagents = item.subagents, onOpenSubagent = onOpenSubagent)
                    }
                }
            }
        }

        TimelineItem.Kind.REASONING,
        TimelineItem.Kind.PLAN -> {
            Box(
                modifier = modifier
                    .fillMaxWidth()
                    .padding(horizontal = 4.dp, vertical = 2.dp)
            ) {
                Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    if (item.label.isNotBlank()) {
                        Text(
                            text = item.label,
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Medium,
                            color = AppColors.onSurfaceMuted
                        )
                    }
                    Text(
                        text = item.text,
                        fontSize = 13.sp,
                        fontStyle = FontStyle.Italic,
                        color = AppColors.onSurfaceMuted,
                        lineHeight = 18.sp
                    )
                }
            }
        }

        TimelineItem.Kind.COMMAND -> {
            var expanded by remember { mutableStateOf(false) }
            val command = item.rawCommand?.takeIf(String::isNotBlank) ?: item.label.takeIf(String::isNotBlank) ?: "Command"
            val hasOutput = item.text.isNotBlank()

            Column(
                modifier = modifier
                    .fillMaxWidth()
                    .clip(shape)
                    .background(AppColors.surfaceContainer)
                    .border(BorderStroke(0.5.dp, AppColors.outlineVariant), shape)
                    .padding(10.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 48.dp)
                        .then(
                            if (hasOutput) Modifier.clickable { expanded = !expanded } else Modifier
                        ),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text(
                        text = "$ $command",
                        fontFamily = FontFamily.Monospace,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = AppColors.onSurface,
                        maxLines = if (expanded) Int.MAX_VALUE else 2,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f)
                    )
                    if (hasOutput) {
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(
                            text = if (expanded) "▲" else "▼",
                            fontSize = 10.sp,
                            color = AppColors.onSurfaceMuted
                        )
                    }
                }

                if (hasOutput && expanded) {
                    HorizontalDivider(color = AppColors.outlineVariant.copy(alpha = 0.3f), thickness = 0.5.dp)
                    Text(
                        text = item.text,
                        fontFamily = FontFamily.Monospace,
                        fontSize = 11.sp,
                        color = AppColors.onSurfaceVariant
                    )
                }
            }
        }

        TimelineItem.Kind.TOOL,
        TimelineItem.Kind.FILE_CHANGE -> {
            var expanded by remember { mutableStateOf(false) }
            val label = item.label.ifBlank { if (item.kind == TimelineItem.Kind.FILE_CHANGE) "File Change" else "Tool Activity" }
            val hasDetails = item.text.isNotBlank() || item.children.isNotEmpty()
            val phaseColor = when (item.phase) {
                "failed" -> AppColors.error
                "cancelled", "canceled" -> AppColors.warning
                else -> AppColors.onSurfaceVariant
            }

            Column(
                modifier = modifier
                    .fillMaxWidth()
                    .clip(shape)
                    .background(AppColors.surfaceContainerLow)
                    .border(BorderStroke(0.5.dp, AppColors.outlineVariant), shape)
                    .padding(10.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 48.dp)
                        .then(
                            if (hasDetails) Modifier.clickable { expanded = !expanded } else Modifier
                        ),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = label,
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Medium,
                        color = AppColors.onSurface,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f)
                    )
                    if (!item.phase.isNullOrBlank()) {
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(
                            text = item.phase,
                            fontSize = 11.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = phaseColor
                        )
                    }
                    if (hasDetails) {
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(
                            text = if (expanded) "▲" else "▼",
                            fontSize = 10.sp,
                            color = AppColors.onSurfaceMuted
                        )
                    }
                }

                if (hasDetails && expanded) {
                    HorizontalDivider(color = AppColors.outlineVariant.copy(alpha = 0.3f), thickness = 0.5.dp)
                    if (item.text.isNotBlank()) {
                        Text(
                            text = item.text,
                            fontFamily = FontFamily.Monospace,
                            fontSize = 11.sp,
                            color = AppColors.onSurfaceVariant
                        )
                    }
                    if (item.children.isNotEmpty()) {
                        item.children.forEach { child ->
                            SubagentTimelineItemView(item = child, onOpenSubagent = onOpenSubagent)
                        }
                    }
                }
            }
        }

        TimelineItem.Kind.ACTIVITY_GROUP,
        TimelineItem.Kind.ACTION_GROUP -> {
            var expanded by remember(item.id) { mutableStateOf(item.active) }
            val label = item.label.ifBlank { "Activity Group" }
            val hasChildren = item.children.isNotEmpty() || item.text.isNotBlank()

            Column(
                modifier = modifier
                    .fillMaxWidth()
                    .clip(shape)
                    .background(AppColors.surfaceContainerLow)
                    .border(BorderStroke(0.5.dp, AppColors.outlineVariant), shape)
                    .padding(10.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 48.dp)
                        .then(
                            if (hasChildren) Modifier.clickable { expanded = !expanded } else Modifier
                        ),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = label,
                        fontSize = 13.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = AppColors.onSurface,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f)
                    )
                    if (hasChildren) {
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(
                            text = if (expanded) "▲" else "▼",
                            fontSize = 10.sp,
                            color = AppColors.onSurfaceMuted
                        )
                    }
                }

                if (hasChildren && expanded) {
                    HorizontalDivider(color = AppColors.outlineVariant.copy(alpha = 0.3f), thickness = 0.5.dp)
                    if (item.text.isNotBlank()) {
                        Text(
                            text = item.text,
                            fontSize = 12.sp,
                            color = AppColors.onSurfaceVariant
                        )
                    }
                    item.children.forEach { child ->
                        SubagentTimelineItemView(item = child, onOpenSubagent = onOpenSubagent)
                    }
                }
            }
        }

        TimelineItem.Kind.ERROR -> {
            Box(
                modifier = modifier
                    .fillMaxWidth()
                    .clip(shape)
                    .background(AppColors.errorContainer)
                    .border(BorderStroke(0.5.dp, AppColors.error), shape)
                    .padding(12.dp)
            ) {
                Text(
                    text = item.text.ifBlank { item.label.ifBlank { "Error" } },
                    fontSize = 13.sp,
                    color = AppColors.error
                )
            }
        }

        TimelineItem.Kind.TURN_SEPARATOR -> {
            Row(
                modifier = modifier
                    .fillMaxWidth()
                    .padding(vertical = 4.dp),
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
                HorizontalDivider(color = AppColors.outlineVariant, thickness = 1.dp)
            }
        }

        else -> {
            if (item.text.isNotBlank()) {
                Box(
                    modifier = modifier
                        .fillMaxWidth()
                        .clip(shape)
                        .background(AppColors.surfaceContainerLow)
                        .padding(10.dp)
                ) {
                    Text(
                        text = item.text,
                        fontSize = 13.sp,
                        color = AppColors.onSurfaceVariant
                    )
                }
            }
        }
    }
}

@Composable
private fun SubagentItemLinks(
    subagents: List<SubagentReference>,
    onOpenSubagent: (SubagentReference) -> Unit,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        Text(
            text = "Referenced subagents:",
            fontSize = 11.sp,
            fontWeight = FontWeight.Medium,
            color = AppColors.onSurfaceMuted
        )
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            subagents.forEach { agent ->
                val shortId = shortThreadId(agent.threadId)
                val label = if (shortId.isNotBlank()) "${agent.name} ($shortId)" else agent.name
                CompactSubagentChip(
                    threadId = agent.threadId,
                    name = label,
                    status = agent.status,
                    onClick = { onOpenSubagent(agent) }
                )
            }
        }
    }
}

internal fun isSubagentActive(rawStatus: String): Boolean = when (rawStatus.trim().lowercase(Locale.US)) {
    "active", "running", "pendinginit" -> true
    else -> false
}

internal fun shortThreadId(threadId: String): String {
    val clean = threadId.removePrefix("thread_").trim()
    return if (clean.length > 8) clean.takeLast(8) else clean
}

internal fun disambiguateName(agent: SubagentReference, allAgents: List<SubagentReference>): String {
    val shortId = shortThreadId(agent.threadId)
    val hasDuplicateName = allAgents.count { it.name.trim().equals(agent.name.trim(), ignoreCase = true) } > 1
    val isGenericName = agent.name.isBlank() || agent.name.trim().equals("Subagent", ignoreCase = true)
    return when {
        hasDuplicateName || isGenericName -> {
            val base = agent.name.trim().ifBlank { "Subagent" }
            if (shortId.isNotBlank()) "$base ($shortId)" else base
        }
        else -> agent.name.trim()
    }
}

internal fun subagentStatusLabel(rawStatus: String): String = when (rawStatus.trim().lowercase(Locale.US)) {
    "active" -> "Active"
    "running" -> "Running"
    "pendinginit" -> "Pending init"
    "idle" -> "Idle"
    "notloaded" -> "Not loaded"
    "systemerror" -> "System error"
    "errored" -> "Error"
    "interrupted" -> "Interrupted"
    "shutdown" -> "Shutdown"
    "notfound" -> "Not found"
    "completed" -> "Completed"
    "failed" -> "Failed"
    "cancelled", "canceled" -> "Cancelled"
    "unknown", "" -> "Unknown"
    else -> rawStatus.trim().replaceFirstChar { if (it.isLowerCase()) it.titlecase(Locale.US) else it.toString() }
}

@Composable
internal fun subagentStatusColor(rawStatus: String): Color = when (rawStatus.trim().lowercase(Locale.US)) {
    "active", "running", "pendinginit" -> AppColors.running
    "idle" -> AppColors.onSurfaceVariant
    "completed" -> AppColors.success
    "systemerror", "errored", "failed" -> AppColors.error
    "interrupted" -> AppColors.warning
    "cancelled", "canceled" -> AppColors.warning
    "shutdown" -> AppColors.onSurfaceMuted
    "notfound" -> AppColors.onSurfaceMuted
    "notloaded", "unknown", "" -> AppColors.onSurfaceMuted
    else -> AppColors.onSurfaceVariant
}
