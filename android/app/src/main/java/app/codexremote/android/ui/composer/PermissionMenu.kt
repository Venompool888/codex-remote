package app.codexremote.android.ui.composer

import androidx.activity.compose.BackHandler
import androidx.compose.ui.window.PopupProperties
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.statusBars
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.res.colorResource
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.codexremote.android.R
import app.codexremote.android.presentation.composer.ComposerOption
import app.codexremote.android.presentation.composer.resolvePermissionIcon
import app.codexremote.android.ui.theme.AppColors

/** Anchored to the composer, with bounded scrolling supplied by DropdownMenu. */
@Composable
internal fun PermissionMenu(
    expanded: Boolean,
    anchorTopPx: Float,
    options: List<ComposerOption>,
    selectedId: String?,
    onDismiss: () -> Unit,
    onSelect: (String) -> Unit
) {
    val screenWidth = LocalConfiguration.current.screenWidthDp
    val width = (screenWidth - 64).coerceIn(0, 320).dp
    val density = LocalDensity.current
    val statusTop = WindowInsets.statusBars.getTop(density)
    val availableHeight = with(density) { (anchorTopPx - statusTop).coerceAtLeast(0f).toDp() }
    BackHandler(enabled = expanded, onBack = onDismiss)
    DropdownMenu(
        expanded = expanded,
        onDismissRequest = onDismiss,
        offset = DpOffset(((screenWidth - width.value) / 2 - 12).dp, 0.dp),
        modifier = Modifier.width(width).heightIn(max = (availableHeight - 8.dp).coerceAtLeast(48.dp)),
        shape = RoundedCornerShape(24.dp),
        containerColor = colorResource(R.color.app_menu_surface),
        tonalElevation = 0.dp,
        shadowElevation = 12.dp,
        properties = PopupProperties(focusable = false)
    ) {
        options.forEach { option ->
            val icon = resolvePermissionIcon(option.id)
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .selectable(
                        selected = option.id == selectedId,
                        enabled = option.enabled,
                        role = Role.RadioButton,
                        onClick = { onSelect(option.id) }
                    )
                    .heightIn(min = 64.dp)
                    .alpha(if (option.enabled) 1f else 0.5f)
                    .padding(horizontal = 20.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                Icon(
                    painter = painterResource(icon),
                    contentDescription = null,
                    tint = if (icon == R.drawable.ic_warning_amber) colorResource(R.color.app_warning) else AppColors.onSurfaceVariant,
                    modifier = Modifier.size(24.dp)
                )
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text(option.label, color = AppColors.onSurface, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
                    if (option.description.isNotBlank()) {
                        Text(option.description, color = AppColors.onSurfaceVariant, fontSize = 14.sp, lineHeight = 20.sp)
                    }
                }
                if (option.id == selectedId) {
                    Icon(painterResource(R.drawable.ic_check), null, Modifier.size(20.dp), tint = AppColors.onSurface)
                } else {
                    Spacer(Modifier.width(20.dp))
                }
            }
        }
    }
}
