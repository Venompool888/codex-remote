package app.codexremote.android.ui.composer

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
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.selection.LocalTextSelectionColors
import androidx.compose.foundation.text.selection.TextSelectionColors
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Constraints
import app.codexremote.android.R
import app.codexremote.android.compose.ComposerActionBar
import app.codexremote.android.compose.ComposerAttachmentStrip
import app.codexremote.android.presentation.composer.ComposerController
import app.codexremote.android.ui.theme.AppColors

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ComposerSection(
    controller: ComposerController,
    modifier: Modifier = Modifier
) {
    val state = controller.uiState.value
    var isFocused by remember { mutableStateOf(false) }

    val isExpanded = state.isExpanded || isFocused || state.text.isNotEmpty() ||
        state.attachments.isNotEmpty() || state.isTurnRunning

    val cornerRadius = if (isExpanded) 24.dp else 26.dp
    val shape = RoundedCornerShape(cornerRadius)

    Box(
        modifier = modifier
            .fillMaxWidth()
            .navigationBarsPadding()
            .imePadding()
            .padding(horizontal = 12.dp, vertical = 6.dp)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .clip(shape)
                .background(AppColors.composer, shape)
                .border(BorderStroke(1.dp, AppColors.outlineVariant), shape)
        ) {
            // 1. Attachment Strip
            if (state.attachments.isNotEmpty()) {
                ComposerAttachmentStrip(
                    items = state.attachments,
                    onCardClick = { controller.clickAttachment(it) },
                    onRemoveClick = { controller.removeAttachment(it) }
                )
            }

            // 2. Main composer core layout (Editor + Action Bar)
            ComposerCoreLayout(
                isExpanded = isExpanded,
                editor = {
                    val inputTextStyle = TextStyle(
                        fontSize = 15.sp,
                        lineHeight = 22.sp,
                        color = AppColors.onSurface
                    )

                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(
                                start = if (isExpanded) 16.dp else 4.dp,
                                end = if (isExpanded) 16.dp else 4.dp,
                                top = if (isExpanded) 14.dp else 0.dp,
                                bottom = if (isExpanded) 8.dp else 0.dp
                            ),
                        contentAlignment = if (isExpanded) Alignment.TopStart else Alignment.CenterStart
                    ) {
                        if (state.text.isEmpty()) {
                            val placeholder = state.hint?.takeIf { it.isNotBlank() }
                                ?: stringResource(R.string.composer_generic_hint)
                            Text(
                                text = placeholder,
                                style = inputTextStyle.copy(color = AppColors.onSurfaceMuted),
                                maxLines = if (isExpanded) Int.MAX_VALUE else 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }

                        val selectionColors = TextSelectionColors(
                            handleColor = AppColors.primary,
                            backgroundColor = AppColors.primaryContainer
                        )

                        CompositionLocalProvider(LocalTextSelectionColors provides selectionColors) {
                            BasicTextField(
                                value = state.textFieldValue,
                                onValueChange = { controller.updateTextFieldValue(it) },
                                textStyle = inputTextStyle,
                                cursorBrush = SolidColor(AppColors.primary),
                                maxLines = if (isExpanded) Int.MAX_VALUE else 1,
                                singleLine = !isExpanded,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .then(
                                        if (isExpanded) {
                                            Modifier.heightIn(min = 24.dp, max = 160.dp)
                                        } else {
                                            Modifier.heightIn(min = 22.dp)
                                        }
                                    )
                                    .onFocusChanged {
                                        isFocused = it.isFocused
                                        if (it.isFocused) {
                                            controller.setExpanded(true)
                                        } else if (state.text.isEmpty() && state.attachments.isEmpty() && !state.isTurnRunning) {
                                            controller.setExpanded(false)
                                        }
                                    }
                            )
                        }
                    }
                },
                actionBar = {
                    ComposerActionBar(
                        state = state.actionBarState.copy(isExpanded = isExpanded),
                        onPlusClick = { controller.toggleAddMenu(true) },
                        onModelClick = { controller.toggleModelMenu(true) },
                        onSendClick = { controller.sendOrStop() },
                        onPermissionClick = { controller.togglePermissionMenu(true) }
                    )
                }
            )
        }

        // Add Menu Bottom Sheet / Popup
        if (state.showAddMenu) {
            ModalBottomSheet(
                onDismissRequest = { controller.toggleAddMenu(false) },
                sheetState = rememberModalBottomSheetState(),
                containerColor = AppColors.surfaceContainerHigh
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 20.dp, vertical = 12.dp)
                ) {
                    Text(
                        text = "Add to conversation",
                        fontSize = 16.sp,
                        fontWeight = FontWeight.Bold,
                        color = AppColors.onSurface
                    )
                    Spacer(modifier = Modifier.height(14.dp))

                    ComposerMenuItem(
                        icon = R.drawable.ic_attach_file,
                        title = "Attach files",
                        subtitle = "Upload documents or code files",
                        onClick = { controller.pickFiles() }
                    )
                    ComposerMenuItem(
                        icon = R.drawable.ic_photo,
                        title = "Add photos",
                        subtitle = "Attach images for visual reasoning",
                        onClick = { controller.pickPhotos() }
                    )
                    ComposerMenuItem(
                        icon = R.drawable.ic_extension,
                        title = "Skills & capabilities",
                        subtitle = "Browse tools installed on host",
                        onClick = { controller.openSkillsCatalog() }
                    )
                    if (state.permissionOptions.isNotEmpty()) {
                        ComposerMenuItem(
                            icon = R.drawable.ic_codex_permission_profile,
                            title = "Permissions",
                            subtitle = "Mode: ${state.permissionMode.ifBlank { "Standard" }}",
                            onClick = {
                                controller.toggleAddMenu(false)
                                controller.togglePermissionMenu(true)
                            }
                        )
                    }
                    Spacer(modifier = Modifier.height(20.dp))
                }
            }
        }

        // Model & Reasoning Effort Bottom Sheet
        if (state.showModelMenu) {
            ModalBottomSheet(
                onDismissRequest = { controller.toggleModelMenu(false) },
                sheetState = rememberModalBottomSheetState(),
                containerColor = AppColors.surfaceContainerHigh
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 20.dp, vertical = 12.dp)
                ) {
                    Text(
                        text = "Model & Reasoning Effort",
                        fontSize = 16.sp,
                        fontWeight = FontWeight.Bold,
                        color = AppColors.onSurface
                    )
                    Spacer(modifier = Modifier.height(12.dp))

                    // Plan Mode toggle
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(8.dp))
                            .background(AppColors.surfaceContainerHighest)
                            .clickable { controller.setPlanMode(!state.planMode) }
                            .padding(horizontal = 12.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text("Plan Mode", fontSize = 14.sp, fontWeight = FontWeight.SemiBold, color = AppColors.onSurface)
                            Text("Formulate step-by-step plan before execution", fontSize = 12.sp, color = AppColors.onSurfaceVariant)
                        }
                        Switch(
                            checked = state.planMode,
                            onCheckedChange = { controller.setPlanMode(it) }
                        )
                    }

                    Spacer(modifier = Modifier.height(14.dp))

                    // Models
                    if (state.modelOptions.isNotEmpty()) {
                        Text("Model", fontSize = 12.sp, fontWeight = FontWeight.SemiBold, color = AppColors.onSurfaceMuted)
                        Spacer(modifier = Modifier.height(6.dp))
                        state.modelOptions.forEach { option ->
                            val isSelected = option.id == state.selectedModelId
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clip(RoundedCornerShape(8.dp))
                                    .background(if (isSelected) AppColors.surfaceContainerHighest else AppColors.surfaceContainerHigh)
                                    .clickable(enabled = option.enabled) { controller.setModel(option.id) }
                                    .padding(horizontal = 12.dp, vertical = 10.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        text = option.label,
                                        fontSize = 14.sp,
                                        fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal,
                                        color = if (option.enabled) AppColors.onSurface else AppColors.onSurfaceMuted
                                    )
                                    if (option.description.isNotBlank()) {
                                        Text(
                                            text = option.description,
                                            fontSize = 12.sp,
                                            color = AppColors.onSurfaceVariant
                                        )
                                    }
                                }
                                if (isSelected) {
                                    Icon(painter = painterResource(R.drawable.ic_check), contentDescription = null, tint = AppColors.primary, modifier = Modifier.size(16.dp))
                                }
                            }
                        }
                    }

                    // Reasoning Effort
                    if (state.effortOptions.isNotEmpty()) {
                        Spacer(modifier = Modifier.height(14.dp))
                        Text("Reasoning Effort", fontSize = 12.sp, fontWeight = FontWeight.SemiBold, color = AppColors.onSurfaceMuted)
                        Spacer(modifier = Modifier.height(6.dp))
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            state.effortOptions.forEach { option ->
                                val isSelected = option.id == state.selectedEffortId
                                Box(
                                    modifier = Modifier
                                        .weight(1f)
                                        .clip(RoundedCornerShape(8.dp))
                                        .background(if (isSelected) AppColors.primary else AppColors.surfaceContainerHighest)
                                        .clickable(enabled = option.enabled) { controller.setReasoningEffort(option.id) }
                                        .padding(vertical = 10.dp),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Text(
                                        text = option.label,
                                        fontSize = 13.sp,
                                        fontWeight = FontWeight.SemiBold,
                                        color = if (isSelected) AppColors.onPrimary else if (option.enabled) AppColors.onSurface else AppColors.onSurfaceMuted
                                    )
                                }
                            }
                        }
                    }

                    // Service Tier / Speed
                    if (state.serviceTierOptions.isNotEmpty()) {
                        Spacer(modifier = Modifier.height(14.dp))
                        Text("Speed & Service Tier", fontSize = 12.sp, fontWeight = FontWeight.SemiBold, color = AppColors.onSurfaceMuted)
                        Spacer(modifier = Modifier.height(6.dp))
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            state.serviceTierOptions.forEach { option ->
                                val isSelected = option.id == state.selectedServiceTierId
                                Box(
                                    modifier = Modifier
                                        .weight(1f)
                                        .clip(RoundedCornerShape(8.dp))
                                        .background(if (isSelected) AppColors.primary else AppColors.surfaceContainerHighest)
                                        .clickable(enabled = option.enabled) { controller.setServiceTier(option.id) }
                                        .padding(vertical = 10.dp),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Text(
                                        text = option.label,
                                        fontSize = 13.sp,
                                        fontWeight = FontWeight.SemiBold,
                                        color = if (isSelected) AppColors.onPrimary else if (option.enabled) AppColors.onSurface else AppColors.onSurfaceMuted
                                    )
                                }
                            }
                        }
                    }

                    Spacer(modifier = Modifier.height(24.dp))
                }
            }
        }

        // Permission Menu Bottom Sheet
        if (state.showPermissionMenu) {
            ModalBottomSheet(
                onDismissRequest = { controller.togglePermissionMenu(false) },
                sheetState = rememberModalBottomSheetState(),
                containerColor = AppColors.surfaceContainerHigh
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 20.dp, vertical = 12.dp)
                ) {
                    Text(
                        text = "Permissions",
                        fontSize = 16.sp,
                        fontWeight = FontWeight.Bold,
                        color = AppColors.onSurface
                    )
                    Spacer(modifier = Modifier.height(12.dp))

                    state.permissionOptions.forEach { option ->
                        val isSelected = option.id == state.selectedPermissionId
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(8.dp))
                                .background(if (isSelected) AppColors.surfaceContainerHighest else AppColors.surfaceContainerHigh)
                                .clickable(enabled = option.enabled) { controller.selectPermission(option.id) }
                                .padding(horizontal = 12.dp, vertical = 10.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = option.label,
                                    fontSize = 14.sp,
                                    fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal,
                                    color = if (option.enabled) AppColors.onSurface else AppColors.onSurfaceMuted
                                )
                                if (option.description.isNotBlank()) {
                                    Text(
                                        text = option.description,
                                        fontSize = 12.sp,
                                        color = AppColors.onSurfaceVariant
                                    )
                                }
                            }
                            if (isSelected) {
                                Icon(painter = painterResource(R.drawable.ic_check), contentDescription = null, tint = AppColors.primary, modifier = Modifier.size(16.dp))
                            }
                        }
                    }
                    Spacer(modifier = Modifier.height(24.dp))
                }
            }
        }

        // Full Access Warning Dialog
        if (state.showFullAccessWarning) {
            AlertDialog(
                onDismissRequest = { controller.dismissFullAccessWarning() },
                title = { Text("Enable Full Access?", fontWeight = FontWeight.Bold) },
                text = {
                    Text(
                        "Full access allows the assistant to run commands and execute scripts directly on the host without confirmation.",
                        fontSize = 14.sp,
                        color = AppColors.onSurfaceVariant
                    )
                },
                confirmButton = {
                    TextButton(
                        onClick = {
                            controller.confirmFullAccess()
                        }
                    ) {
                        Text("Enable", fontWeight = FontWeight.Bold, color = AppColors.error)
                    }
                },
                dismissButton = {
                    TextButton(onClick = { controller.dismissFullAccessWarning() }) {
                        Text("Cancel", color = AppColors.onSurfaceVariant)
                    }
                },
                containerColor = AppColors.surfaceContainerHigh
            )
        }
    }
}

@Composable
private fun ComposerMenuItem(
    icon: Int,
    title: String,
    subtitle: String,
    onClick: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .clickable(onClick = onClick)
            .padding(vertical = 10.dp, horizontal = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            painter = painterResource(icon),
            contentDescription = null,
            tint = AppColors.onSurface,
            modifier = Modifier.size(24.dp)
        )
        Spacer(modifier = Modifier.width(14.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(title, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, color = AppColors.onSurface)
            Text(subtitle, fontSize = 12.sp, color = AppColors.onSurfaceVariant)
        }
    }
}

@Composable
private fun ComposerCoreLayout(
    isExpanded: Boolean,
    editor: @Composable () -> Unit,
    actionBar: @Composable () -> Unit,
    modifier: Modifier = Modifier
) {
    Layout(
        content = {
            editor()
            actionBar()
        },
        modifier = modifier
    ) { measurables, constraints ->
        val editorMeasurable = measurables[0]
        val actionBarMeasurable = measurables[1]

        if (!isExpanded) {
            val pillHeight = 52.dp.roundToPx()
            val plusWidth = 48.dp.roundToPx()
            val sendWidth = 52.dp.roundToPx()
            val editorWidth = (constraints.maxWidth - plusWidth - sendWidth).coerceAtLeast(0)

            val actionBarPlaceable = actionBarMeasurable.measure(
                Constraints.fixed(constraints.maxWidth, pillHeight)
            )
            val editorPlaceable = editorMeasurable.measure(
                Constraints(
                    minWidth = 0,
                    maxWidth = editorWidth,
                    minHeight = 0,
                    maxHeight = pillHeight
                )
            )

            layout(constraints.maxWidth, pillHeight) {
                actionBarPlaceable.placeRelative(0, 0)
                val editorY = ((pillHeight - editorPlaceable.height) / 2).coerceAtLeast(0)
                editorPlaceable.placeRelative(plusWidth, editorY)
            }
        } else {
            val actionBarPlaceable = actionBarMeasurable.measure(
                Constraints(
                    minWidth = constraints.maxWidth,
                    maxWidth = constraints.maxWidth,
                    minHeight = 48.dp.roundToPx(),
                    maxHeight = 48.dp.roundToPx()
                )
            )
            val editorPlaceable = editorMeasurable.measure(
                Constraints(
                    minWidth = constraints.maxWidth,
                    maxWidth = constraints.maxWidth,
                    minHeight = 0,
                    maxHeight = 160.dp.roundToPx() + 22.dp.roundToPx()
                )
            )

            val totalHeight = editorPlaceable.height + actionBarPlaceable.height

            layout(constraints.maxWidth, totalHeight) {
                editorPlaceable.placeRelative(0, 0)
                actionBarPlaceable.placeRelative(0, editorPlaceable.height)
            }
        }
    }
}
