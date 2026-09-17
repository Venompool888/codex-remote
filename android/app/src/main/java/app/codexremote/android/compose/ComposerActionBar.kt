package app.codexremote.android.compose

import android.animation.ValueAnimator
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.wrapContentSize
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.ripple
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.colorResource
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.codexremote.android.R

@Composable
fun ComposerActionBar(
    state: ComposerActionBarUiState,
    onPlusClick: () -> Unit = {},
    onModelClick: () -> Unit = {},
    onSendClick: () -> Unit = {},
    onPermissionClick: () -> Unit = {},
    modifier: Modifier = Modifier
) {
    val primaryColor = colorResource(R.color.app_on_surface)
    val secondaryColor = colorResource(R.color.app_on_surface_variant)
    val buttonBgColor = colorResource(R.color.app_on_surface)
    val buttonIconColor = colorResource(R.color.app_background)

    val animatorsEnabled = remember { ValueAnimator.areAnimatorsEnabled() }
    val modelTargetAlpha = if (state.isExpanded) 1f else 0f
    val animatedModelAlpha by animateFloatAsState(
        targetValue = modelTargetAlpha,
        animationSpec = if (animatorsEnabled) tween(durationMillis = 100) else tween(durationMillis = 0),
        label = "modelAlpha"
    )

    val permissionTargetAlpha = if (state.isExpanded && state.hasPermissionOptions) 1f else 0f
    val animatedPermissionAlpha by animateFloatAsState(
        targetValue = permissionTargetAlpha,
        animationSpec = if (animatorsEnabled) tween(durationMillis = 100) else tween(durationMillis = 0),
        label = "permissionAlpha"
    )

    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(48.dp)
    ) {
        // 1. Left: Plus button (48dp touch target, 22dp icon)
        Box(
            modifier = Modifier
                .align(Alignment.CenterStart)
                .size(48.dp)
                .clip(CircleShape)
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = ripple(bounded = true, radius = 24.dp),
                    role = Role.Button,
                    onClickLabel = state.plusButtonContentDescription
                ) {
                    onPlusClick()
                }
                .semantics {
                    contentDescription = state.plusButtonContentDescription
                },
            contentAlignment = Alignment.Center
        ) {
            Icon(
                painter = painterResource(R.drawable.ic_codex_plus),
                contentDescription = null,
                tint = primaryColor,
                modifier = Modifier.size(22.dp)
            )
        }

        // 2. Permission button (48dp touch target, non-overlapping directly adjacent to plus)
        if (state.hasPermissionOptions && (state.isExpanded || animatedPermissionAlpha > 0.01f)) {
            Box(
                modifier = Modifier
                    .align(Alignment.CenterStart)
                    .padding(start = 48.dp)
                    .size(48.dp)
                    .alpha(animatedPermissionAlpha)
                    .clip(CircleShape)
                    .clickable(
                        enabled = state.isExpanded,
                        interactionSource = remember { MutableInteractionSource() },
                        indication = ripple(bounded = true, radius = 24.dp),
                        role = Role.Button,
                        onClickLabel = state.permissionContentDescription
                    ) {
                        onPermissionClick()
                    }
                    .semantics {
                        contentDescription = state.permissionContentDescription
                    },
                contentAlignment = Alignment.Center
            ) {
                val tintColor = if (state.permissionIconRes == R.drawable.ic_warning_amber) {
                    colorResource(R.color.app_warning)
                } else {
                    primaryColor
                }
                Icon(
                    painter = painterResource(state.permissionIconRes),
                    contentDescription = null,
                    tint = tintColor,
                    modifier = Modifier.size(20.dp)
                )
            }
        }

        // 3. Center-Right: Model / Reasoning effort label
        // Outermost container is positioned at CenterEnd with margins, wrapContentSize ensures
        // the button is exactly as wide as its content (not stretched).
        if (state.isExpanded || animatedModelAlpha > 0.01f) {
            val modelStartPadding = if (state.hasPermissionOptions) 96.dp else 48.dp
            Box(
                modifier = Modifier
                    .align(Alignment.CenterEnd)
                    .padding(start = modelStartPadding, end = 56.dp)
                    .wrapContentSize(Alignment.CenterEnd)
                    .height(48.dp)
                    .widthIn(min = 48.dp)
                    .alpha(animatedModelAlpha)
                    .clip(RoundedCornerShape(8.dp))
                    .clickable(
                        enabled = state.isExpanded,
                        interactionSource = remember { MutableInteractionSource() },
                        indication = ripple(bounded = true),
                        role = Role.Button,
                        onClickLabel = state.modelContentDescription
                    ) {
                        onModelClick()
                    }
                    .semantics {
                        contentDescription = state.modelContentDescription
                    },
                contentAlignment = Alignment.Center
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.padding(horizontal = 8.dp)
                ) {
                    if (state.hasFastTier) {
                        Icon(
                            painter = painterResource(R.drawable.ic_codex_fast),
                            contentDescription = null,
                            tint = secondaryColor,
                            modifier = Modifier.size(17.dp)
                        )
                        Spacer(modifier = Modifier.width(4.dp))
                    }
                    Text(
                        text = state.modelLabel,
                        color = primaryColor,
                        fontSize = 14.sp,
                        fontWeight = FontWeight.Bold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f, fill = false)
                    )
                    Spacer(modifier = Modifier.width(4.dp))
                    Icon(
                        painter = painterResource(R.drawable.ic_codex_chevron_down),
                        contentDescription = null,
                        tint = secondaryColor,
                        modifier = Modifier.size(15.dp)
                    )
                }
            }
        }

        // 4. Right: Send / Stop button (48dp touch target, centered 36dp circle, 2dp end margin)
        Box(
            modifier = Modifier
                .align(Alignment.CenterEnd)
                .padding(end = 2.dp)
                .size(48.dp)
                .clip(CircleShape)
                .clickable(
                    enabled = state.canSend,
                    interactionSource = remember { MutableInteractionSource() },
                    indication = if (state.canSend) ripple(bounded = true, radius = 24.dp) else null,
                    role = Role.Button,
                    onClickLabel = state.sendButtonContentDescription
                ) {
                    onSendClick()
                }
                .semantics {
                    contentDescription = state.sendButtonContentDescription
                },
            contentAlignment = Alignment.Center
        ) {
            Box(
                modifier = Modifier
                    .size(36.dp)
                    .alpha(if (state.canSend) 1f else 0.38f)
                    .background(buttonBgColor, CircleShape),
                contentAlignment = Alignment.Center
            ) {
                val iconRes = if (state.isTurnRunning) R.drawable.ic_codex_stop else R.drawable.ic_codex_arrow_up
                val iconSize = if (state.isTurnRunning) 18.dp else 20.dp
                Icon(
                    painter = painterResource(iconRes),
                    contentDescription = null,
                    tint = buttonIconColor,
                    modifier = Modifier.size(iconSize)
                )
            }
        }
    }
}
