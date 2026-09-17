package app.codexremote.android.ui.conversation

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.runtime.Composable
import androidx.compose.runtime.key
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.codexremote.android.FileDiffDetail
import app.codexremote.android.MarkdownBlock
import app.codexremote.android.R
import app.codexremote.android.ThreadProjection
import app.codexremote.android.TimelineItem
import app.codexremote.android.ui.theme.AppColors
import org.json.JSONObject
import java.util.Locale

@Composable
fun ToolActivityCard(
    item: TimelineItem,
    isExpanded: Boolean,
    onToggleExpand: () -> Unit,
    onViewDiff: (List<FileDiffDetail>, String) -> Unit,
    modifier: Modifier = Modifier,
    onCopy: (String) -> Unit = {},
    onOpenArtifacts: () -> Unit = {},
    scopeKey: String = ""
) {
    if (item.kind != TimelineItem.Kind.ACTIVITY_GROUP) {
        ToolCallRow(
            item = item,
            isExpanded = isExpanded,
            onToggleExpand = onToggleExpand,
            onViewDiff = onViewDiff,
            modifier = modifier
        )
        return
    }

    var expandedChildIds by remember(scopeKey, item.id, item.active) { mutableStateOf(setOf<String>()) }
    val onToggleChildExpand: (String) -> Unit = { childId ->
        expandedChildIds = if (childId in expandedChildIds) expandedChildIds - childId else expandedChildIds + childId
    }

    if (item.active) {
        Column(
            modifier = modifier.fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            ActivityProcessContent(
                children = item.children,
                expandedChildIds = expandedChildIds,
                onToggleChildExpand = onToggleChildExpand,
                onViewDiff = onViewDiff,
                onCopy = onCopy,
                onOpenArtifacts = onOpenArtifacts
            )

            item.imagePath?.takeIf(String::isNotBlank)?.let { path ->
                MarkdownImageComposable(
                    image = MarkdownBlock.Image(alt = "Generated image", source = path),
                    onOpenImage = { _, _ -> }
                )
            }
        }
        return
    }

    val summaryLabel = remember(item) { activityGroupDurationLabel(item) }
    val canCollapse = item.children.isNotEmpty() || item.fileDiffs.isNotEmpty() || item.fileChanges.isNotEmpty() || item.imagePath != null
    val shape = RoundedCornerShape(12.dp)
    val statusColor = when (item.phase) {
        "failed" -> AppColors.error
        "cancelled", "canceled" -> AppColors.warning
        else -> AppColors.onSurfaceMuted
    }
    val glyph = when (item.phase) {
        "failed" -> "×"
        "cancelled", "canceled" -> "⊘"
        else -> "✓"
    }
    val disclosureRotation by animateFloatAsState(
        targetValue = if (isExpanded) 180f else 0f,
        animationSpec = tween(
            durationMillis = if (isExpanded) 320 else 260,
            easing = FastOutSlowInEasing
        ),
        label = "activity disclosure indicator"
    )

    Box(
        modifier = modifier
            .fillMaxWidth()
            .clip(shape)
            .background(AppColors.surfaceContainerLow, shape)
            .border(BorderStroke(1.dp, AppColors.outlineVariant), shape)
    ) {
        Column {
            // Header row
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(shape)
                    .heightIn(min = 48.dp)
                    .clickable(enabled = canCollapse) { onToggleExpand() }
                    .padding(12.dp)
                    .semantics {
                        contentDescription = if (canCollapse) {
                            if (isExpanded) "Collapse $summaryLabel" else "Expand $summaryLabel"
                        } else summaryLabel
                    },
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = glyph,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Bold,
                    color = statusColor
                )
                Spacer(modifier = Modifier.width(8.dp))

                Text(
                    text = summaryLabel,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = AppColors.onSurface,
                    modifier = Modifier.weight(1f),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )

                if (canCollapse) {
                    Icon(
                        painter = painterResource(R.drawable.ic_codex_chevron_down),
                        contentDescription = if (isExpanded) "Collapse" else "Expand",
                        tint = AppColors.onSurfaceMuted,
                        modifier = Modifier
                            .size(18.dp)
                            .graphicsLayer { rotationZ = disclosureRotation }
                    )
                }
            }

            // Expanded execution children and details
            AnimatedVisibility(
                visible = isExpanded,
                enter = fadeIn(
                    animationSpec = tween(
                        durationMillis = 220,
                        delayMillis = 40,
                        easing = FastOutSlowInEasing
                    )
                ) + expandVertically(
                    animationSpec = tween(
                        durationMillis = 320,
                        easing = FastOutSlowInEasing
                    )
                ),
                exit = fadeOut(
                    animationSpec = tween(
                        durationMillis = 160,
                        easing = FastOutSlowInEasing
                    )
                ) + shrinkVertically(
                    animationSpec = tween(
                        durationMillis = 260,
                        easing = FastOutSlowInEasing
                    )
                )
            ) {
                Column(
                    modifier = Modifier.padding(start = 12.dp, end = 12.dp, bottom = 12.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    HorizontalDivider(
                        color = AppColors.outlineVariant.copy(alpha = 0.5f),
                        thickness = 0.5.dp
                    )

                    // 1. File diffs drawer if present
                    if (item.fileDiffs.isNotEmpty()) {
                        FileDiffDrawer(
                            item = item,
                            onViewDiff = onViewDiff
                        )
                    } else if (item.fileChanges.isNotEmpty()) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.End
                        ) {
                            Button(
                                onClick = { onViewDiff(item.fileChanges, item.fileChanges.first().path) },
                                shape = RoundedCornerShape(8.dp),
                                colors = ButtonDefaults.buttonColors(
                                    containerColor = AppColors.primary,
                                    contentColor = AppColors.onPrimary
                                ),
                                modifier = Modifier.height(32.dp)
                            ) {
                                Text("View diff", fontSize = 12.sp, fontWeight = FontWeight.Bold)
                            }
                        }
                    }

                    // 2. Render nested children
                    ActivityProcessContent(
                        children = item.children,
                        expandedChildIds = expandedChildIds,
                        onToggleChildExpand = onToggleChildExpand,
                        onViewDiff = onViewDiff,
                        onCopy = onCopy,
                        onOpenArtifacts = onOpenArtifacts
                    )

                    // 3. Image preview if item has imagePath
                    item.imagePath?.takeIf(String::isNotBlank)?.let { path ->
                        MarkdownImageComposable(
                            image = MarkdownBlock.Image(alt = "Generated image", source = path),
                            onOpenImage = { _, _ -> }
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun FileDiffDrawer(
    item: TimelineItem,
    onViewDiff: (List<FileDiffDetail>, String) -> Unit,
    modifier: Modifier = Modifier
) {
    val files = remember(item.fileDiffs) { item.fileDiffs.toSortedMap().entries.toList() }
    var showAll by remember { mutableStateOf(false) }
    val displayFiles = if (showAll || files.size <= 3) files else files.take(3)

    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(vertical = 2.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp)
    ) {
        displayFiles.forEach { (path, stats) ->
            val displayName = item.fileChanges.lastOrNull { it.path == path }?.displayName ?: path
            val detail = item.fileChanges.lastOrNull { it.path.trimStart('/') == path.trimStart('/') }
            val clickable = detail != null && detail.patch.isNotBlank()

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(4.dp))
                    .then(
                        if (clickable) Modifier.clickable { onViewDiff(item.fileChanges, path) }
                        else Modifier
                    )
                    .padding(horizontal = 6.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(
                    text = displayName,
                    fontFamily = FontFamily.Monospace,
                    fontSize = 11.sp,
                    color = AppColors.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f)
                )

                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (stats.additions > 0) {
                        Text(
                            text = "+${stats.additions}",
                            fontFamily = FontFamily.Monospace,
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Bold,
                            color = Color(0xFF4ADE80)
                        )
                    }
                    if (stats.deletions > 0) {
                        if (stats.additions > 0) Spacer(modifier = Modifier.width(6.dp))
                        Text(
                            text = "-${stats.deletions}",
                            fontFamily = FontFamily.Monospace,
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Bold,
                            color = Color(0xFFF87171)
                        )
                    }
                }
            }
        }

        val hiddenCount = (files.size - 3).coerceAtLeast(0)
        if (hiddenCount > 0) {
            Text(
                text = if (showAll) "Show fewer files" else "Show $hiddenCount more file${if (hiddenCount == 1) "" else "s"}",
                fontSize = 11.sp,
                fontWeight = FontWeight.SemiBold,
                color = AppColors.primary,
                modifier = Modifier
                    .clickable { showAll = !showAll }
                    .padding(horizontal = 6.dp, vertical = 4.dp)
            )
        }
    }
}

@Composable
internal fun ToolCallRow(
    item: TimelineItem,
    isExpanded: Boolean,
    onToggleExpand: () -> Unit,
    onViewDiff: (List<FileDiffDetail>, String) -> Unit,
    modifier: Modifier = Modifier
) {
    val summary = item.label.ifBlank { item.text.lineSequence().firstOrNull().orEmpty().trim().ifBlank { "Tool" } }
    val iconRes = remember(item) { toolDrawable(item) }

    val iconTint = when (item.phase) {
        "failed" -> AppColors.error
        "cancelled", "canceled" -> AppColors.warning
        else -> if (item.active) AppColors.running else AppColors.onSurfaceVariant
    }

    val hasDiff = item.fileDiffs.isNotEmpty() || item.fileChanges.isNotEmpty()
    val hasCommandOutput = !item.rawCommand.isNullOrBlank() || item.text.isNotBlank()
    val hasImage = item.imagePath?.isNotBlank() == true
    val hasDetails = hasDiff || hasCommandOutput || hasImage

    Column(
        modifier = modifier.fillMaxWidth()
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(4.dp))
                .heightIn(min = 48.dp)
                .then(
                    if (hasDetails) {
                        Modifier.clickable(
                            onClickLabel = if (isExpanded) "Collapse $summary" else "Expand $summary"
                        ) {
                            onToggleExpand()
                        }
                    } else Modifier
                )
                .padding(vertical = 4.dp, horizontal = 2.dp)
                .semantics {
                    if (hasDetails) {
                        contentDescription = if (isExpanded) "Collapse $summary" else "Expand $summary"
                    }
                },
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                painter = painterResource(iconRes),
                contentDescription = null,
                tint = iconTint,
                modifier = Modifier.size(18.dp)
            )
            Spacer(modifier = Modifier.width(8.dp))
            Text(
                text = summary,
                fontSize = 13.sp,
                color = AppColors.onSurfaceMuted,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f)
            )
            if (item.active) {
                Spacer(modifier = Modifier.width(6.dp))
                CircularProgressIndicator(
                    modifier = Modifier.size(12.dp),
                    strokeWidth = 1.5.dp,
                    color = AppColors.running
                )
            }
            if (hasDetails) {
                Spacer(modifier = Modifier.width(6.dp))
                Icon(
                    painter = painterResource(if (isExpanded) R.drawable.ic_expand_less else R.drawable.ic_codex_chevron_down),
                    contentDescription = if (isExpanded) "Collapse" else "Expand",
                    tint = AppColors.onSurfaceMuted,
                    modifier = Modifier.size(16.dp)
                )
            }
        }

        AnimatedVisibility(
            visible = isExpanded && hasDetails,
            enter = fadeIn(animationSpec = tween(150)) + expandVertically(animationSpec = tween(150)),
            exit = fadeOut(animationSpec = tween(120)) + shrinkVertically(animationSpec = tween(120))
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 4.dp, bottom = 4.dp)
                    .clip(RoundedCornerShape(8.dp))
                    .background(AppColors.surfaceContainerLow)
                    .border(BorderStroke(1.dp, AppColors.outlineVariant.copy(alpha = 0.5f)), RoundedCornerShape(8.dp))
                    .padding(8.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                if (item.fileDiffs.isNotEmpty()) {
                    FileDiffDrawer(
                        item = item,
                        onViewDiff = onViewDiff
                    )
                } else if (item.fileChanges.isNotEmpty()) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.End
                    ) {
                        Button(
                            onClick = { onViewDiff(item.fileChanges, item.fileChanges.first().path) },
                            shape = RoundedCornerShape(6.dp),
                            colors = ButtonDefaults.buttonColors(
                                containerColor = AppColors.surfaceContainerHighest,
                                contentColor = AppColors.primary
                            ),
                            modifier = Modifier.height(28.dp)
                        ) {
                            Text("View diff", fontSize = 11.sp, fontWeight = FontWeight.Bold)
                        }
                    }
                }

                if (!item.rawCommand.isNullOrBlank() || item.text.isNotBlank()) {
                    CommandExecutionOutput(
                        rawCommand = item.rawCommand,
                        output = item.text
                    )
                }

                item.imagePath?.takeIf(String::isNotBlank)?.let { path ->
                    MarkdownImageComposable(
                        image = MarkdownBlock.Image(alt = "Generated image", source = path),
                        onOpenImage = { _, _ -> }
                    )
                }
            }
        }
    }
}

@Composable
internal fun ActivityProcessContent(
    children: List<TimelineItem>,
    expandedChildIds: Set<String>,
    onToggleChildExpand: (String) -> Unit,
    onViewDiff: (List<FileDiffDetail>, String) -> Unit,
    onCopy: (String) -> Unit,
    onOpenArtifacts: () -> Unit,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        ThreadProjection.activitySections(children).forEach { child ->
            key(child.id) {
                when (child.kind) {
                    TimelineItem.Kind.COMMENTARY,
                    TimelineItem.Kind.ASSISTANT -> {
                        AssistantMessageBubble(
                            item = child,
                            onCopy = onCopy,
                            onOpenArtifacts = onOpenArtifacts,
                            showCopyAction = false
                        )
                    }
                    TimelineItem.Kind.REASONING,
                    TimelineItem.Kind.PLAN -> {
                        ReasoningPlanBubble(item = child)
                    }
                    TimelineItem.Kind.COMMAND,
                    TimelineItem.Kind.FILE_CHANGE,
                    TimelineItem.Kind.TOOL -> {
                        ToolCallRow(
                            item = child,
                            isExpanded = child.id in expandedChildIds,
                            onToggleExpand = { onToggleChildExpand(child.id) },
                            onViewDiff = onViewDiff
                        )
                    }
                    TimelineItem.Kind.ERROR -> {
                        ErrorMessageBubble(item = child)
                    }
                    else -> {
                        if (child.imagePath?.isNotBlank() == true) {
                            MarkdownImageComposable(
                                image = MarkdownBlock.Image(alt = child.label.ifBlank { "Image" }, source = child.imagePath),
                                onOpenImage = { _, _ -> }
                            )
                        } else if (child.text.isNotBlank()) {
                            Text(
                                text = child.text,
                                fontSize = 14.sp,
                                color = AppColors.onSurfaceVariant
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun CommandExecutionOutput(
    rawCommand: String?,
    output: String,
    modifier: Modifier = Modifier
) {
    val parsed = remember(output) { parseCommandOutput(output) }
    var expanded by remember { mutableStateOf(false) }

    SelectionContainer {
        Column(
            modifier = modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(6.dp))
                .background(AppColors.surfaceContainer)
                .padding(horizontal = 10.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            // Raw command header
            if (!rawCommand.isNullOrBlank()) {
                Text(
                    text = "$ $rawCommand",
                    fontFamily = FontFamily.Monospace,
                    fontSize = 11.sp,
                    color = AppColors.onSurface,
                    maxLines = if (expanded) Int.MAX_VALUE else 3,
                    overflow = TextOverflow.Ellipsis
                )
            }

            // Exit code & wall time badge
            if (parsed.meta != null) {
                Text(
                    text = parsed.meta,
                    fontSize = 10.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = if (parsed.exitCode != null && parsed.exitCode != 0) AppColors.error else AppColors.onSurfaceMuted
                )
            }

            // Separate stdout
            if (parsed.stdout.isNotBlank()) {
                Text(
                    text = parsed.stdout,
                    fontFamily = FontFamily.Monospace,
                    fontSize = 11.sp,
                    color = AppColors.onSurfaceVariant,
                    maxLines = if (expanded) Int.MAX_VALUE else 10,
                    overflow = TextOverflow.Ellipsis
                )
            }

            // Separate stderr
            if (parsed.stderr.isNotBlank()) {
                Text(
                    text = parsed.stderr,
                    fontFamily = FontFamily.Monospace,
                    fontSize = 11.sp,
                    color = AppColors.error,
                    maxLines = if (expanded) Int.MAX_VALUE else 10,
                    overflow = TextOverflow.Ellipsis
                )
            }

            // Show more/less toggle
            val totalLines = (parsed.stdout + "\n" + parsed.stderr).count { it == '\n' }
            if (totalLines > 10 || (parsed.stdout.length + parsed.stderr.length) > 500) {
                Text(
                    text = if (expanded) "Show less output" else "Show full output",
                    fontSize = 11.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = AppColors.primary,
                    modifier = Modifier
                        .clickable { expanded = !expanded }
                        .padding(vertical = 2.dp)
                )
            }
        }
    }
}

internal fun activityGroupDurationLabel(item: TimelineItem, nowMs: Long = System.currentTimeMillis()): String {
    if (!item.active && item.phase !in setOf("failed", "cancelled", "canceled") && item.filesChanged > 0) {
        return "Edited ${item.filesChanged} file${if (item.filesChanged == 1) "" else "s"}"
    }
    val elapsed = item.durationMs ?: item.startedAtEpochMs?.let { (nowMs - it).coerceAtLeast(0L) }
    val prefix = when (item.phase) {
        "failed" -> "Failed in"
        "cancelled", "canceled" -> "Cancelled in"
        else -> if (item.active) "Working for" else "Worked for"
    }
    return elapsed?.let { "$prefix ${compactDuration(it)}" } ?: item.label
}

internal fun compactDuration(durationMs: Long): String {
    val totalSeconds = (durationMs / 1_000L).coerceAtLeast(0L)
    val hours = totalSeconds / 3_600L
    val minutes = totalSeconds % 3_600L / 60L
    val seconds = totalSeconds % 60L
    return buildList {
        if (hours > 0) add("${hours}h")
        if (minutes > 0 || hours > 0) add("${minutes}m")
        add("${seconds}s")
    }.joinToString(" ")
}

internal data class ParsedCommandOutput(
    val meta: String? = null,
    val exitCode: Int? = null,
    val stdout: String = "",
    val stderr: String = ""
)

internal fun parseCommandOutput(raw: String): ParsedCommandOutput {
    if (raw.isBlank()) return ParsedCommandOutput()
    val candidates = buildList {
        add(raw.trim())
        val marker = raw.lastIndexOf("Output:")
        if (marker >= 0) add(raw.substring(marker + "Output:".length).trim())
    }.asReversed()

    for (candidate in candidates) {
        val envelope = runCatching { JSONObject(candidate) }.getOrNull() ?: continue
        val exitCode = envelope.optInt("exit_code", Int.MIN_VALUE).takeIf { it != Int.MIN_VALUE }
        val wallTime = envelope.optDouble("wall_time_seconds", Double.NaN)
        val meta = buildList {
            if (exitCode != null) add("Exit $exitCode")
            if (!wallTime.isNaN()) add("${String.format(Locale.US, "%.1f", wallTime)}s")
        }.joinToString(" · ").ifBlank { null }

        val stdout = envelope.optString("stdout").ifBlank { envelope.optString("output") }
        val stderr = envelope.optString("stderr")
        if (stdout.isNotBlank() || stderr.isNotBlank() || meta != null) {
            return ParsedCommandOutput(
                meta = meta,
                exitCode = exitCode,
                stdout = stdout.trimEnd(),
                stderr = stderr.trimEnd()
            )
        }
    }
    return ParsedCommandOutput(stdout = raw.trimEnd())
}

internal fun toolDrawable(item: TimelineItem): Int {
    if (item.label.equals("wait", ignoreCase = true) || item.label.startsWith("Waiting ", ignoreCase = true)) {
        return R.drawable.ic_more_horizontal
    }
    return when (item.toolStyle) {
        TimelineItem.ToolStyle.SEARCH -> R.drawable.ic_search
        TimelineItem.ToolStyle.READ -> R.drawable.ic_folder
        TimelineItem.ToolStyle.WEB -> R.drawable.ic_public
        TimelineItem.ToolStyle.SKILL -> R.drawable.ic_school
        TimelineItem.ToolStyle.INTEGRATION -> R.drawable.ic_extension
        TimelineItem.ToolStyle.GENERIC -> when (item.kind) {
            TimelineItem.Kind.COMMAND -> R.drawable.ic_terminal
            TimelineItem.Kind.FILE_CHANGE -> R.drawable.ic_folder
            else -> R.drawable.ic_extension
        }
    }
}
