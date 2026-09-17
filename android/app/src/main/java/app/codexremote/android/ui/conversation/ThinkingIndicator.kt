package app.codexremote.android.ui.conversation

import android.animation.ValueAnimator
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.codexremote.android.TimelineItem
import app.codexremote.android.ui.theme.AppColors

private val RUNNING_STATUSES = setOf("active", "running", "inprogress", "started", "pending")
private val COMPLETED_PHASES = setOf("completed", "finished", "failed", "cancelled", "canceled", "done", "success")

/**
 * Detects whether the conversation timeline already contains an active user-facing
 * status (such as an active concrete tool or final assistant answer in the current turn).
 * Active commentary, reasoning, planning, and group-only active states
 * retain the lightweight bottom thinking indicator without creating duplicate noisy status.
 */
internal fun hasActiveStatus(items: List<TimelineItem>): Boolean {
    val lastUserIndex = items.indexOfLast { it.kind == TimelineItem.Kind.USER }
    val currentTurnItems = if (lastUserIndex >= 0) {
        items.subList(lastUserIndex + 1, items.size)
    } else {
        items
    }

    // Final assistant answer arriving before turn completion must not produce a trailing ThinkingIndicator
    if (currentTurnItems.any { it.kind == TimelineItem.Kind.ASSISTANT && it.text.isNotBlank() }) {
        return true
    }

    return currentTurnItems.any { item ->
        when (item.kind) {
            TimelineItem.Kind.ACTIVITY_GROUP -> {
                // Completed/inactive group and historical children must not count as current active status
                if (!item.active || item.phase?.lowercase() in COMPLETED_PHASES) {
                    false
                } else {
                    item.children.any { child -> isConcreteToolActive(child) }
                }
            }
            TimelineItem.Kind.COMMAND,
            TimelineItem.Kind.FILE_CHANGE,
            TimelineItem.Kind.TOOL -> {
                isConcreteToolActive(item)
            }
            else -> false
        }
    }
}

private fun isConcreteToolActive(item: TimelineItem): Boolean {
    if (!item.active && (item.phase == null || item.phase.lowercase() !in RUNNING_STATUSES)) {
        return false
    }
    if (item.phase != null && item.phase.lowercase() in COMPLETED_PHASES) {
        return false
    }
    val isRunning = item.active || (item.phase != null && item.phase.lowercase() in RUNNING_STATUSES)
    return isRunning && item.kind in setOf(
        TimelineItem.Kind.COMMAND,
        TimelineItem.Kind.FILE_CHANGE,
        TimelineItem.Kind.TOOL
    )
}

/**
 * Text-only Thinking indicator with a continuous sheen sweeping left to right.
 * Honors system animator duration scale; renders a stable readable label when
 * animations are disabled without an infinite animation or coroutine leak.
 */
@Composable
fun ThinkingIndicator(
    modifier: Modifier = Modifier
) {
    val animatorsEnabled = remember {
        runCatching { ValueAnimator.areAnimatorsEnabled() }.getOrDefault(true)
    }

    val baseColor = AppColors.onSurfaceMuted
    val sheenColor = AppColors.onSurface

    if (!animatorsEnabled) {
        Text(
            text = "Thinking",
            color = baseColor,
            fontSize = 13.sp,
            lineHeight = 18.sp,
            fontStyle = FontStyle.Italic,
            maxLines = 1,
            softWrap = false,
            overflow = TextOverflow.Ellipsis,
            modifier = modifier
                .testTag("Thinking indicator")
                .semantics {
                    contentDescription = "Thinking"
                }
        )
    } else {
        val transition = rememberInfiniteTransition(label = "thinkingShimmer")
        val phase by transition.animateFloat(
            initialValue = 0f,
            targetValue = 1f,
            animationSpec = infiniteRepeatable(
                animation = tween(durationMillis = 1600, easing = LinearEasing),
                repeatMode = RepeatMode.Restart
            ),
            label = "thinkingShimmerPhase"
        )

        var textWidthPx by remember { mutableFloatStateOf(0f) }
        val density = LocalDensity.current
        val fallbackWidthPx = remember(density) { with(density) { 60.dp.toPx() } }
        val width = if (textWidthPx > 0f) textWidthPx else fallbackWidthPx
        val sheenWidth = (width * 0.7f).coerceAtLeast(30f)
        val totalTravel = width + sheenWidth * 2f
        val startX = (phase * totalTravel) - sheenWidth
        val endX = startX + sheenWidth

        val brush = remember(phase, width, startX, endX, baseColor, sheenColor) {
            Brush.linearGradient(
                colors = listOf(baseColor, sheenColor, baseColor),
                start = Offset(startX, 0f),
                end = Offset(endX, 0f)
            )
        }

        Text(
            text = "Thinking",
            style = TextStyle(
                brush = brush,
                alpha = 1f,
                fontSize = 13.sp,
                lineHeight = 18.sp,
                fontStyle = FontStyle.Italic
            ),
            maxLines = 1,
            softWrap = false,
            overflow = TextOverflow.Ellipsis,
            modifier = modifier
                .onSizeChanged { size ->
                    if (size.width > 0) {
                        textWidthPx = size.width.toFloat()
                    }
                }
                .testTag("Thinking indicator")
                .semantics {
                    contentDescription = "Thinking"
                }
        )
    }
}
