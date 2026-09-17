@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
package app.codexremote.android.ui.connections

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.BasicAlertDialog
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.text.style.TextOverflow
import app.codexremote.android.RemoteConnection
import app.codexremote.android.RemoteProject
import app.codexremote.android.presentation.connections.ConnectionsController
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import app.codexremote.android.R
import app.codexremote.android.ui.theme.AppColors

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DeleteConnectionDialog(
    serverNames: List<String>,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit
) {
    BasicAlertDialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        val shape = RoundedCornerShape(16.dp)
        Box(
            modifier = Modifier
                .fillMaxWidth(0.9f)
                .clip(shape)
                .background(AppColors.surfaceContainer, shape)
                .padding(24.dp)
        ) {
            Column {
                Text(
                    text = "Delete connection?",
                    fontSize = 18.sp,
                    fontWeight = FontWeight.Bold,
                    color = AppColors.onSurface
                )
                Spacer(modifier = Modifier.height(12.dp))
                val target = if (serverNames.size == 1) {
                    serverNames.first()
                } else {
                    "${serverNames.size} connections"
                }
                Text(
                    text = "This will remove $target and all associated remote projects and credentials from this device.",
                    fontSize = 14.sp,
                    color = AppColors.onSurfaceVariant
                )
                Spacer(modifier = Modifier.height(20.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End
                ) {
                    TextButton(onClick = onDismiss) {
                        Text("Cancel", color = AppColors.onSurfaceVariant)
                    }
                    Spacer(modifier = Modifier.width(8.dp))
                    TextButton(
                        onClick = onConfirm,
                        colors = ButtonDefaults.textButtonColors(contentColor = AppColors.error)
                    ) {
                        Text("Delete", fontWeight = FontWeight.Bold)
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PairingDialog(
    serverUrl: String,
    code: String,
    error: String?,
    isBusy: Boolean,
    onCodeChange: (String) -> Unit,
    onSubmit: () -> Unit,
    onDismiss: () -> Unit
) {
    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        val shape = RoundedCornerShape(16.dp)
        Box(
            modifier = Modifier
                .fillMaxWidth(0.9f)
                .clip(shape)
                .background(AppColors.surfaceContainer, shape)
                .padding(24.dp)
        ) {
            Column {
                Text(
                    text = "Pair with host",
                    fontSize = 18.sp,
                    fontWeight = FontWeight.Bold,
                    color = AppColors.onSurface
                )
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = "Server: $serverUrl",
                    fontSize = 13.sp,
                    color = AppColors.onSurfaceVariant
                )
                Spacer(modifier = Modifier.height(16.dp))

                OutlinedTextField(
                    value = code,
                    onValueChange = onCodeChange,
                    label = { Text("Pairing code") },
                    singleLine = true,
                    enabled = !isBusy,
                    visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                    keyboardActions = KeyboardActions(onDone = { onSubmit() }),
                    modifier = Modifier.fillMaxWidth(),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = AppColors.primary,
                        unfocusedBorderColor = AppColors.outlineVariant,
                        focusedTextColor = AppColors.onSurface,
                        unfocusedTextColor = AppColors.onSurface
                    )
                )

                if (!error.isNullOrBlank()) {
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = error,
                        fontSize = 12.sp,
                        color = AppColors.error
                    )
                }

                Spacer(modifier = Modifier.height(20.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End
                ) {
                    TextButton(
                        onClick = onDismiss,
                        enabled = !isBusy
                    ) {
                        Text("Cancel", color = AppColors.onSurfaceVariant)
                    }
                    Spacer(modifier = Modifier.width(8.dp))
                    TextButton(
                        onClick = onSubmit,
                        enabled = !isBusy
                    ) {
                        Text(if (isBusy) "Pairing…" else "Pair", fontWeight = FontWeight.Bold, color = AppColors.primary)
                    }
                }
            }
        }
    }
}

@Composable
fun ConnectionSettingsDialog(
    connection: RemoteConnection,
    controller: ConnectionsController
) {
    val state = controller.uiState.value

    Dialog(
        onDismissRequest = { if (!state.isSettingsBusy) controller.dismissEditing() },
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        val shape = RoundedCornerShape(16.dp)
        Box(
            modifier = Modifier
                .fillMaxWidth(0.92f)
                .clip(shape)
                .background(AppColors.surfaceContainer, shape)
                .padding(24.dp)
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState())
            ) {
                Text(
                    text = "Connection settings",
                    fontSize = 18.sp,
                    fontWeight = FontWeight.Bold,
                    color = AppColors.onSurface
                )
                Spacer(modifier = Modifier.height(16.dp))

                OutlinedTextField(
                    value = state.settingsName,
                    onValueChange = { controller.updateSettingsName(it) },
                    label = { Text("Connection name") },
                    singleLine = true,
                    enabled = !state.isSettingsBusy,
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(modifier = Modifier.height(12.dp))

                OutlinedTextField(
                    value = state.settingsNewServer,
                    onValueChange = { controller.updateSettingsServer(it) },
                    label = { Text("Server URL") },
                    singleLine = true,
                    enabled = !state.isSettingsBusy,
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(modifier = Modifier.height(12.dp))

                OutlinedTextField(
                    value = state.settingsPairingCode,
                    onValueChange = { controller.updateSettingsPairingCode(it) },
                    label = { Text("New pairing code (optional)") },
                    singleLine = true,
                    enabled = !state.isSettingsBusy,
                    visualTransformation = PasswordVisualTransformation(),
                    modifier = Modifier.fillMaxWidth()
                )

                if (state.settingsCredentialSummary.isNotBlank() || state.settingsStatus.isNotBlank()) {
                    Spacer(modifier = Modifier.height(14.dp))
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(8.dp))
                            .background(AppColors.surfaceContainerHighest)
                            .padding(12.dp)
                    ) {
                        Column {
                            if (state.settingsCredentialSummary.isNotBlank()) {
                                Text(
                                    text = "Security: ${state.settingsCredentialSummary}",
                                    fontSize = 12.sp,
                                    color = AppColors.onSurface
                                )
                            }
                            if (state.settingsStatus.isNotBlank()) {
                                Text(
                                    text = "Status: ${state.settingsStatus}",
                                    fontSize = 12.sp,
                                    color = AppColors.onSurfaceVariant
                                )
                            }
                            if (state.settingsAccountStatus.isNotBlank()) {
                                Text(
                                    text = "Account: ${state.settingsAccountStatus}",
                                    fontSize = 12.sp,
                                    color = AppColors.onSurfaceVariant
                                )
                            }
                        }
                    }
                }

                Spacer(modifier = Modifier.height(14.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    OutlinedButton(
                        onClick = { controller.rotateCredential(connection.serverUrl) },
                        enabled = !state.isSettingsBusy,
                        modifier = Modifier.weight(1f)
                    ) {
                        Text("Rotate key", fontSize = 13.sp)
                    }
                    OutlinedButton(
                        onClick = { controller.requestRevokeCredential(connection.serverUrl) },
                        enabled = !state.isSettingsBusy,
                        colors = ButtonDefaults.outlinedButtonColors(contentColor = AppColors.error),
                        modifier = Modifier.weight(1f)
                    ) {
                        Text("Revoke", fontSize = 13.sp, color = AppColors.error)
                    }
                }

                if (connection.projects.isNotEmpty()) {
                    Spacer(modifier = Modifier.height(16.dp))
                    Text(
                        text = "Associated projects",
                        fontSize = 14.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = AppColors.onSurface
                    )
                    Spacer(modifier = Modifier.height(6.dp))
                    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        connection.projects.forEach { proj ->
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clip(RoundedCornerShape(6.dp))
                                    .background(AppColors.surfaceContainerHigh)
                                    .padding(horizontal = 10.dp, vertical = 6.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        text = proj.name,
                                        fontSize = 13.sp,
                                        fontWeight = FontWeight.Medium,
                                        color = AppColors.onSurface
                                    )
                                    Text(
                                        text = proj.workspace,
                                        fontSize = 11.sp,
                                        color = AppColors.onSurfaceVariant,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                }
                                IconButton(
                                    onClick = { controller.removeProject(proj) },
                                    modifier = Modifier.size(24.dp)
                                ) {
                                    Icon(
                                        painter = painterResource(R.drawable.ic_close),
                                        contentDescription = "Remove project",
                                        tint = AppColors.onSurfaceVariant,
                                        modifier = Modifier.size(16.dp)
                                    )
                                }
                            }
                        }
                    }
                }

                if (!state.settingsError.isNullOrBlank()) {
                    Spacer(modifier = Modifier.height(10.dp))
                    Text(
                        text = state.settingsError,
                        fontSize = 12.sp,
                        color = AppColors.error
                    )
                }

                Spacer(modifier = Modifier.height(20.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End
                ) {
                    TextButton(
                        onClick = { controller.dismissEditing() },
                        enabled = !state.isSettingsBusy
                    ) {
                        Text("Cancel", color = AppColors.onSurfaceVariant)
                    }
                    Spacer(modifier = Modifier.width(8.dp))
                    TextButton(
                        onClick = { controller.saveSettings() },
                        enabled = !state.isSettingsBusy
                    ) {
                        Text(if (state.isSettingsBusy) "Saving…" else "Save", fontWeight = FontWeight.Bold, color = AppColors.primary)
                    }
                }
            }
        }
    }
}

@Composable
fun RevokeCredentialDialog(
    serverUrl: String,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit
) {
    BasicAlertDialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        val shape = RoundedCornerShape(16.dp)
        Box(
            modifier = Modifier
                .fillMaxWidth(0.9f)
                .clip(shape)
                .background(AppColors.surfaceContainer, shape)
                .padding(24.dp)
        ) {
            Column {
                Text(
                    text = "Revoke authorization?",
                    fontSize = 18.sp,
                    fontWeight = FontWeight.Bold,
                    color = AppColors.onSurface
                )
                Spacer(modifier = Modifier.height(12.dp))
                Text(
                    text = "This immediately disconnects this phone from $serverUrl. Projects and chats remain on the host, but a new pairing code will be required.",
                    fontSize = 14.sp,
                    color = AppColors.onSurfaceVariant
                )
                Spacer(modifier = Modifier.height(20.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End
                ) {
                    TextButton(onClick = onDismiss) {
                        Text("Cancel", color = AppColors.onSurfaceVariant)
                    }
                    Spacer(modifier = Modifier.width(8.dp))
                    TextButton(
                        onClick = onConfirm,
                        colors = ButtonDefaults.textButtonColors(contentColor = AppColors.error)
                    ) {
                        Text("Revoke", fontWeight = FontWeight.Bold)
                    }
                }
            }
        }
    }
}
