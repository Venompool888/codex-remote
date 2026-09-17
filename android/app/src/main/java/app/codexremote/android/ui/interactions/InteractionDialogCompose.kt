package app.codexremote.android.ui.interactions

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CheckboxDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.RadioButton
import androidx.compose.material3.RadioButtonDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import app.codexremote.android.InteractionFormModel
import app.codexremote.android.presentation.interactions.InteractionsController
import app.codexremote.android.ui.theme.AppColors

@Composable
fun InteractionDialogCompose(
    controller: InteractionsController,
    modifier: Modifier = Modifier
) {
    val state = controller.uiState.value ?: return
    val form = state.form ?: return

    Dialog(
        onDismissRequest = { /* cancel disabled on outside tap */ },
        properties = DialogProperties(
            dismissOnBackPress = false,
            dismissOnClickOutside = false,
            usePlatformDefaultWidth = false
        )
    ) {
        val shape = RoundedCornerShape(16.dp)

        Box(
            modifier = modifier
                .fillMaxSize()
                .background(AppColors.drawerScrim)
                .imePadding()
                .padding(20.dp),
            contentAlignment = Alignment.Center
        ) {
            Surface(
                modifier = Modifier
                    .fillMaxWidth()
                    .fillMaxHeight(0.88f),
                shape = shape,
                color = AppColors.surfaceContainer
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(20.dp)
                ) {
                    // Title
                    Text(
                        text = form.title,
                        fontSize = 18.sp,
                        fontWeight = FontWeight.Bold,
                        color = AppColors.onSurface
                    )

                    if (form.message.isNotBlank()) {
                        Spacer(modifier = Modifier.height(6.dp))
                        Text(
                            text = form.message,
                            fontSize = 14.sp,
                            color = AppColors.onSurfaceVariant
                        )
                    }

                    // Error text
                    if (!state.errorMessage.isNullOrBlank()) {
                        Spacer(modifier = Modifier.height(8.dp))
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(8.dp))
                                .background(AppColors.errorContainer)
                                .padding(10.dp)
                        ) {
                            Text(
                                text = state.errorMessage,
                                fontSize = 13.sp,
                                color = AppColors.error
                            )
                        }
                    }

                    // Rejection / Status message
                    if (!state.statusMessage.isNullOrBlank()) {
                        Spacer(modifier = Modifier.height(8.dp))
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(8.dp))
                                .background(AppColors.surfaceContainerHigh)
                                .padding(10.dp)
                        ) {
                            Text(
                                text = state.statusMessage,
                                fontSize = 13.sp,
                                color = AppColors.onSurfaceVariant
                            )
                        }
                    }

                    Spacer(modifier = Modifier.height(14.dp))

                    // Form Body
                    Column(
                        modifier = Modifier
                            .weight(1f)
                            .verticalScroll(rememberScrollState()),
                        verticalArrangement = Arrangement.spacedBy(16.dp)
                    ) {
                        if (form.websiteUrl != null) {
                            Text(
                                text = "Website: ${form.websiteUrl}\n\nOpens in your browser. Credentials stay with the website; Remote does not collect them.",
                                fontSize = 14.sp,
                                color = AppColors.onSurfaceVariant
                            )
                            Button(
                                onClick = { controller.openWebsiteUrl() },
                                shape = RoundedCornerShape(8.dp),
                                colors = ButtonDefaults.buttonColors(
                                    containerColor = AppColors.surfaceContainerHighest,
                                    contentColor = AppColors.primary
                                )
                            ) {
                                Text("Open Website")
                            }
                        } else {
                            form.fields.forEach { field ->
                                FormFieldView(
                                    field = field,
                                    form = form,
                                    controller = controller,
                                    revision = state.revision
                                )
                            }
                        }
                    }

                    Spacer(modifier = Modifier.height(16.dp))

                    // Buttons
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.End,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        TextButton(
                            onClick = { controller.cancel() },
                            enabled = !state.isBusy
                        ) {
                            Text(if (form.websiteUrl != null) "Close" else "Cancel")
                        }

                        Spacer(modifier = Modifier.width(8.dp))

                        Button(
                            onClick = { controller.submit() },
                            enabled = !state.isBusy,
                            shape = RoundedCornerShape(8.dp),
                            colors = ButtonDefaults.buttonColors(
                                containerColor = AppColors.primary,
                                contentColor = AppColors.onPrimary
                            )
                        ) {
                            if (state.isSubmitting) {
                                CircularProgressIndicator(
                                    modifier = Modifier.size(16.dp),
                                    strokeWidth = 2.dp,
                                    color = AppColors.onPrimary
                                )
                            } else {
                                Text(if (form.websiteUrl != null) "Accept" else "Submit")
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun FormFieldView(
    field: InteractionFormModel.Field,
    form: InteractionFormModel,
    controller: InteractionsController,
    revision: Long
) {
    val enabled = controller.uiState.value?.isBusy != true
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        if (field.header.isNotBlank()) {
            Text(
                text = field.header,
                fontSize = 15.sp,
                fontWeight = FontWeight.Bold,
                color = AppColors.onSurface
            )
        }

        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = field.title,
                fontSize = 14.sp,
                fontWeight = FontWeight.SemiBold,
                color = AppColors.onSurface
            )
            if (field.required) {
                Text(text = " *", color = AppColors.error, fontSize = 14.sp)
            }
        }

        if (field.description.isNotBlank()) {
            Text(
                text = field.description,
                fontSize = 12.sp,
                color = AppColors.onSurfaceVariant
            )
        }

        when (field.kind) {
            InteractionFormModel.Kind.BOOLEAN -> {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier
                        .clip(RoundedCornerShape(6.dp))
                        .clickable(enabled = enabled) { controller.setChecked(field.id, !form.checked(field.id)) }
                        .padding(vertical = 4.dp)
                ) {
                    Checkbox(
                        enabled = enabled,
                        checked = form.checked(field.id),
                        onCheckedChange = { controller.setChecked(field.id, it) },
                        colors = CheckboxDefaults.colors(
                            checkedColor = AppColors.primary,
                            uncheckedColor = AppColors.outline
                        )
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(text = "Enable", fontSize = 13.sp, color = AppColors.onSurface)
                }
            }

            InteractionFormModel.Kind.SINGLE -> {
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    val selectedSet = form.selected(field.id)
                    field.options.forEach { opt ->
                        val isSelected = opt.value in selectedSet
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(6.dp))
                                .clickable(enabled = enabled) { controller.select(field.id, opt.value, true) }
                                .padding(vertical = 4.dp)
                        ) {
                            RadioButton(
                                enabled = enabled,
                                selected = isSelected,
                                onClick = { controller.select(field.id, opt.value, true) },
                                colors = RadioButtonDefaults.colors(
                                    selectedColor = AppColors.primary,
                                    unselectedColor = AppColors.outline
                                )
                            )
                            Spacer(modifier = Modifier.width(6.dp))
                            Column {
                                Text(text = opt.title, fontSize = 13.sp, color = AppColors.onSurface)
                                if (opt.description.isNotBlank()) {
                                    Text(text = opt.description, fontSize = 11.sp, color = AppColors.onSurfaceVariant)
                                }
                            }
                        }
                    }
                }
            }

            InteractionFormModel.Kind.MULTIPLE -> {
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    val selectedSet = form.selected(field.id)
                    field.options.forEach { opt ->
                        val isSelected = opt.value in selectedSet
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(6.dp))
                                .clickable(enabled = enabled) { controller.select(field.id, opt.value, !isSelected) }
                                .padding(vertical = 4.dp)
                        ) {
                            Checkbox(
                                enabled = enabled,
                                checked = isSelected,
                                onCheckedChange = { controller.select(field.id, opt.value, it) },
                                colors = CheckboxDefaults.colors(
                                    checkedColor = AppColors.primary,
                                    uncheckedColor = AppColors.outline
                                )
                            )
                            Spacer(modifier = Modifier.width(6.dp))
                            Column {
                                Text(text = opt.title, fontSize = 13.sp, color = AppColors.onSurface)
                                if (opt.description.isNotBlank()) {
                                    Text(text = opt.description, fontSize = 11.sp, color = AppColors.onSurfaceVariant)
                                }
                            }
                        }
                    }
                }
            }

            InteractionFormModel.Kind.QUESTION -> {
                if (field.options.isNotEmpty()) {
                    val selectedSet = form.selected(field.id)
                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        field.options.forEach { opt ->
                            val isSelected = opt.value in selectedSet
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clip(RoundedCornerShape(6.dp))
                                    .clickable(enabled = enabled) { controller.select(field.id, opt.value, true) }
                                    .padding(vertical = 4.dp)
                            ) {
                                RadioButton(
                                    enabled = enabled,
                                    selected = isSelected,
                                    onClick = { controller.select(field.id, opt.value, true) },
                                    colors = RadioButtonDefaults.colors(
                                        selectedColor = AppColors.primary,
                                        unselectedColor = AppColors.outline
                                    )
                                )
                                Spacer(modifier = Modifier.width(6.dp))
                                Column {
                                    Text(text = opt.title, fontSize = 13.sp, color = AppColors.onSurface)
                                    if (opt.description.isNotBlank()) {
                                        Text(text = opt.description, fontSize = 11.sp, color = AppColors.onSurfaceVariant)
                                    }
                                }
                            }
                        }
                    }
                }

                // Text entry for questions
                OutlinedTextField(
                        enabled = enabled,
                    value = form.text(field.id),
                    onValueChange = { controller.setText(field.id, it) },
                    placeholder = { Text(if (field.options.isNotEmpty()) "Other answer…" else "Your answer…", fontSize = 13.sp) },
                    visualTransformation = if (field.secret) PasswordVisualTransformation() else VisualTransformation.None,
                    modifier = Modifier.fillMaxWidth(),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = AppColors.primary,
                        unfocusedBorderColor = AppColors.outlineVariant,
                        focusedTextColor = AppColors.onSurface,
                        unfocusedTextColor = AppColors.onSurface
                    )
                )
            }

            InteractionFormModel.Kind.NUMBER, InteractionFormModel.Kind.INTEGER -> {
                OutlinedTextField(
                        enabled = enabled,
                    value = form.text(field.id),
                    onValueChange = { controller.setText(field.id, it) },
                    placeholder = { Text("0", fontSize = 13.sp) },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = AppColors.primary,
                        unfocusedBorderColor = AppColors.outlineVariant,
                        focusedTextColor = AppColors.onSurface,
                        unfocusedTextColor = AppColors.onSurface
                    )
                )
            }

            InteractionFormModel.Kind.TEXT -> {
                OutlinedTextField(
                        enabled = enabled,
                    value = form.text(field.id),
                    onValueChange = { controller.setText(field.id, it) },
                    placeholder = { Text("Enter text…", fontSize = 13.sp) },
                    visualTransformation = if (field.secret) PasswordVisualTransformation() else VisualTransformation.None,
                    modifier = Modifier.fillMaxWidth(),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = AppColors.primary,
                        unfocusedBorderColor = AppColors.outlineVariant,
                        focusedTextColor = AppColors.onSurface,
                        unfocusedTextColor = AppColors.onSurface
                    )
                )
            }
        }
    }
}
