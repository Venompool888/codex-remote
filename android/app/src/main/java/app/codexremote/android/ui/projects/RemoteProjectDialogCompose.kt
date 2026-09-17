package app.codexremote.android.ui.projects

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
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.error
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import app.codexremote.android.R
import app.codexremote.android.presentation.projects.ProjectsController
import app.codexremote.android.ui.theme.AppColors
import kotlinx.coroutines.delay

@Composable
fun RemoteProjectDialogCompose(
    controller: ProjectsController,
    modifier: Modifier = Modifier
) {
    val state = controller.uiState.value
    if (!state.isOpen) return

    val formScroll = rememberScrollState()
    val currentServerUrl = controller.currentServerUrl()
    LaunchedEffect(state.formError) { if (state.formError != null) formScroll.animateScrollTo(0) }
    LaunchedEffect(state.folderPath, state.isEditingFolderPath, currentServerUrl) {
        if (state.isEditingFolderPath) {
            delay(250)
            controller.completeFolderPath(state.folderPath)
        }
    }
    var hostMenuExpanded by remember { mutableStateOf(false) }

    Dialog(
        onDismissRequest = { controller.dismissDialog() },
        properties = DialogProperties(usePlatformDefaultWidth = false)
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
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(shape)
                    .background(AppColors.surfaceContainer, shape)
                    .border(BorderStroke(1.dp, AppColors.outlineVariant), shape)
                    .padding(20.dp)
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .verticalScroll(formScroll)
                ) {
                    // Header
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = "New remote project",
                            fontSize = 18.sp,
                            fontWeight = FontWeight.Bold,
                            color = AppColors.onSurface,
                            modifier = Modifier.weight(1f)
                        )
                        IconButton(
                            onClick = { controller.dismissDialog() },
                            enabled = !state.isBusy
                        ) {
                            Icon(
                                painter = painterResource(R.drawable.ic_close),
                                contentDescription = "Close",
                                tint = AppColors.onSurfaceMuted,
                                modifier = Modifier.size(20.dp)
                            )
                        }
                    }

                    // Error banner
                    if (!state.formError.isNullOrBlank()) {
                        Spacer(modifier = Modifier.height(10.dp))
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(8.dp))
                                .background(AppColors.errorContainer)
                                .padding(horizontal = 12.dp, vertical = 8.dp)
                        ) {
                            Text(
                                text = state.formError,
                                fontSize = 13.sp,
                                color = AppColors.error
                            )
                        }
                    }

                    Spacer(modifier = Modifier.height(14.dp))

                    // Project name input
                    OutlinedTextField(
                        value = state.projectName,
                        onValueChange = { controller.updateProjectName(it) },
                        label = { Text("Project name") },
                        placeholder = { Text("My project") },
                        leadingIcon = {
                            Icon(
                                painter = painterResource(R.drawable.ic_remote_folder),
                                contentDescription = null,
                                tint = AppColors.onSurfaceMuted,
                                modifier = Modifier.size(20.dp)
                            )
                        },
                        singleLine = true,
                        enabled = !state.isBusy,
                        modifier = Modifier.fillMaxWidth(),
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedBorderColor = AppColors.primary,
                            unfocusedBorderColor = AppColors.outlineVariant,
                            focusedTextColor = AppColors.onSurface,
                            unfocusedTextColor = AppColors.onSurface
                        )
                    )

                    Spacer(modifier = Modifier.height(14.dp))
                    Text(
                        text = "Remote host",
                        fontSize = 14.sp,
                        fontWeight = FontWeight.Bold,
                        color = AppColors.onSurface
                    )
                    Spacer(modifier = Modifier.height(6.dp))

                    // Host selector capsule
                    Box(modifier = Modifier.fillMaxWidth()) {
                        val hostLabel = if (state.isNewHost) "New remote host" else state.selectedHost?.name?.ifBlank { state.selectedHost.serverUrl } ?: "Select remote host"
                        val hostShape = RoundedCornerShape(12.dp)
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(48.dp)
                                .clip(hostShape)
                                .background(AppColors.surfaceContainerLow, hostShape)
                                .border(BorderStroke(1.dp, AppColors.outlineVariant), hostShape)
                                .clickable(enabled = !state.isBusy) { hostMenuExpanded = true }
                                .padding(horizontal = 14.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(
                                painter = painterResource(R.drawable.ic_public),
                                contentDescription = null,
                                tint = AppColors.onSurfaceVariant,
                                modifier = Modifier.size(18.dp)
                            )
                            Spacer(modifier = Modifier.width(10.dp))
                            Text(
                                text = hostLabel,
                                fontSize = 15.sp,
                                color = AppColors.onSurface,
                                modifier = Modifier.weight(1f),
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                            Icon(
                                painter = painterResource(R.drawable.ic_codex_chevron_down),
                                contentDescription = null,
                                tint = AppColors.onSurfaceMuted,
                                modifier = Modifier.size(18.dp)
                            )
                        }

                        DropdownMenu(
                            expanded = hostMenuExpanded,
                            onDismissRequest = { hostMenuExpanded = false },
                            modifier = Modifier.background(AppColors.surfaceContainerHigh)
                        ) {
                            state.hosts.forEach { host ->
                                DropdownMenuItem(
                                    text = { Text(host.name.ifBlank { host.serverUrl }, color = AppColors.onSurface) },
                                    onClick = {
                                        hostMenuExpanded = false
                                        controller.selectHost(host)
                                    }
                                )
                            }
                            DropdownMenuItem(
                                text = { Text("New remote host", fontWeight = FontWeight.Bold, color = AppColors.onSurface) },
                                onClick = {
                                    hostMenuExpanded = false
                                    controller.selectHost(null)
                                }
                            )
                        }
                    }

                    // New host fields
                    if (state.isNewHost) {
                        Spacer(modifier = Modifier.height(10.dp))
                        OutlinedTextField(
                            value = state.serverUrl,
                            onValueChange = { controller.updateServerUrl(it) },
                            label = { Text("Server URL") },
                            placeholder = { Text("http://192.168.1.5:8080") },
                            singleLine = true,
                            enabled = !state.isBusy,
                            modifier = Modifier.fillMaxWidth(),
                            colors = OutlinedTextFieldDefaults.colors(
                                focusedBorderColor = AppColors.primary,
                                unfocusedBorderColor = AppColors.outlineVariant,
                                focusedTextColor = AppColors.onSurface,
                                unfocusedTextColor = AppColors.onSurface
                            )
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        OutlinedTextField(
                            value = state.connectionName,
                            onValueChange = { controller.updateConnectionName(it) },
                            label = { Text("Connection name") },
                            singleLine = true,
                            enabled = !state.isBusy,
                            modifier = Modifier.fillMaxWidth(),
                            colors = OutlinedTextFieldDefaults.colors(
                                focusedBorderColor = AppColors.primary,
                                unfocusedBorderColor = AppColors.outlineVariant,
                                focusedTextColor = AppColors.onSurface,
                                unfocusedTextColor = AppColors.onSurface
                            )
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        OutlinedTextField(
                            value = state.pairingCode,
                            onValueChange = { controller.updatePairingCode(it) },
                            label = { Text("Pairing code (optional)") },
                            visualTransformation = PasswordVisualTransformation(),
                            singleLine = true,
                            enabled = !state.isBusy,
                            modifier = Modifier.fillMaxWidth(),
                            colors = OutlinedTextFieldDefaults.colors(
                                focusedBorderColor = AppColors.primary,
                                unfocusedBorderColor = AppColors.outlineVariant,
                                focusedTextColor = AppColors.onSurface,
                                unfocusedTextColor = AppColors.onSurface
                            )
                        )
                    }

                    // Source folder section
                    Spacer(modifier = Modifier.height(14.dp))
                    Text(
                        text = "Source folder",
                        fontSize = 14.sp,
                        fontWeight = FontWeight.Bold,
                        color = AppColors.onSurface
                    )
                    Spacer(modifier = Modifier.height(6.dp))

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        IconButton(
                            onClick = { controller.navigateUp() },
                            enabled = !state.isBusy,
                            modifier = Modifier
                                .size(44.dp)
                                .clip(RoundedCornerShape(10.dp))
                                .background(AppColors.surfaceContainerHighest)
                        ) {
                            Icon(
                                painter = painterResource(R.drawable.ic_arrow_upward),
                                contentDescription = "Up one folder",
                                tint = AppColors.onSurfaceMuted,
                                modifier = Modifier.size(20.dp)
                            )
                        }
                        Spacer(modifier = Modifier.width(6.dp))
                        OutlinedTextField(
                            value = state.folderPath,
                            onValueChange = { controller.updateFolderPath(it) },
                            placeholder = { Text("Remote source folder") },
                            singleLine = true,
                            enabled = !state.isBusy,
                            modifier = Modifier.weight(1f),
                            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                            keyboardActions = KeyboardActions(onDone = { controller.browseFolders(state.folderPath) }),
                            colors = OutlinedTextFieldDefaults.colors(
                                focusedBorderColor = AppColors.primary,
                                unfocusedBorderColor = AppColors.outlineVariant,
                                focusedTextColor = AppColors.onSurface,
                                unfocusedTextColor = AppColors.onSurface
                            )
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Button(
                            onClick = { controller.browseFolders(state.folderPath) },
                            enabled = !state.isBusy,
                            shape = RoundedCornerShape(10.dp),
                            colors = ButtonDefaults.buttonColors(
                                containerColor = AppColors.surfaceContainerHighest,
                                contentColor = AppColors.onSurface
                            ),
                            modifier = Modifier.height(48.dp)
                        ) {
                            Text("Browse", fontSize = 13.sp, fontWeight = FontWeight.Bold)
                        }
                    }

                    // Folder listing box
                    Spacer(modifier = Modifier.height(8.dp))
                    val listShape = RoundedCornerShape(10.dp)
                    val browseError = state.browseError
                    val browseFailed = !browseError.isNullOrBlank() && state.folders.isEmpty()
                    val permissionDenied = browseError?.let {
                        it.contains("(EACCES)") || it.contains("(EPERM)")
                    } == true
                    val failedPath = state.folderPath.ifBlank { stringResource(R.string.remote_folder_current) }
                    val failureTitle = stringResource(R.string.remote_folder_cannot_open, failedPath)
                    val failureMessage = if (permissionDenied) {
                        stringResource(R.string.remote_folder_permission_denied)
                    } else {
                        browseError.orEmpty()
                    }
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(150.dp)
                            .clip(listShape)
                            .background(
                                if (browseFailed) AppColors.errorContainer else AppColors.surfaceContainerLow,
                                listShape
                            )
                            .border(
                                BorderStroke(1.dp, if (browseFailed) AppColors.error else AppColors.outlineVariant),
                                listShape
                            )
                            .then(
                                if (browseFailed) Modifier.semantics {
                                    error("$failureTitle. $failureMessage")
                                    liveRegion = LiveRegionMode.Assertive
                                } else Modifier
                            )
                            .padding(8.dp)
                    ) {
                        if (state.isLoadingFolders) {
                            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                                    Spacer(modifier = Modifier.width(8.dp))
                                    Text("Loading folders…", fontSize = 13.sp, color = AppColors.onSurfaceMuted)
                                }
                            }
                        } else if (browseFailed) {
                            Column(
                                modifier = Modifier
                                    .fillMaxSize()
                                    .padding(8.dp),
                                verticalArrangement = Arrangement.Center
                            ) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Icon(
                                        painter = painterResource(R.drawable.ic_warning_amber),
                                        contentDescription = null,
                                        tint = AppColors.error,
                                        modifier = Modifier.size(22.dp)
                                    )
                                    Spacer(modifier = Modifier.width(8.dp))
                                    Text(
                                        text = failureTitle,
                                        fontSize = 14.sp,
                                        fontWeight = FontWeight.Bold,
                                        color = AppColors.error
                                    )
                                }
                                Spacer(modifier = Modifier.height(8.dp))
                                Text(
                                    text = failureMessage,
                                    fontSize = 12.sp,
                                    color = AppColors.error
                                )
                            }
                        } else {
                            Column(
                                modifier = Modifier
                                    .fillMaxSize()
                                    .verticalScroll(rememberScrollState())
                            ) {
                                if (!state.browseError.isNullOrBlank()) {
                                    Text(
                                        text = state.browseError,
                                        fontSize = 13.sp,
                                        color = AppColors.onSurfaceMuted,
                                        modifier = Modifier.padding(8.dp)
                                    )
                                }
                                if (state.folders.isEmpty() && state.browseError.isNullOrBlank()) {
                                    Text(
                                        text = stringResource(
                                            if (state.isShowingFolderSuggestions) R.string.remote_folder_no_matches
                                            else R.string.remote_folder_no_subdirectories
                                        ),
                                        fontSize = 13.sp,
                                        color = AppColors.onSurfaceMuted,
                                        modifier = Modifier.padding(8.dp)
                                    )
                                }
                                state.folders.forEach { folder ->
                                    Row(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .clip(RoundedCornerShape(6.dp))
                                            .clickable(enabled = !state.isBusy) {
                                                controller.selectFolder(folder.path)
                                            }
                                            .padding(horizontal = 8.dp, vertical = 6.dp),
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Icon(
                                            painter = painterResource(R.drawable.ic_remote_folder),
                                            contentDescription = null,
                                            tint = AppColors.onSurfaceMuted,
                                            modifier = Modifier.size(18.dp)
                                        )
                                        Spacer(modifier = Modifier.width(8.dp))
                                        Text(
                                            text = folder.name.ifBlank { folder.path },
                                            fontSize = 13.sp,
                                            color = AppColors.onSurface,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis
                                        )
                                    }
                                }
                            }
                        }
                    }

                    // Footer actions
                    Spacer(modifier = Modifier.height(18.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.End,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        TextButton(
                            onClick = { controller.dismissDialog() },
                            enabled = !state.isBusy
                        ) {
                            Text("Cancel", fontSize = 15.sp, color = AppColors.onSurfaceMuted)
                        }
                        Spacer(modifier = Modifier.width(8.dp))
                        Button(
                            onClick = { controller.submit() },
                            enabled = !state.isBusy,
                            shape = RoundedCornerShape(22.dp),
                            colors = ButtonDefaults.buttonColors(
                                containerColor = AppColors.primary,
                                contentColor = AppColors.onPrimary
                            )
                        ) {
                            Text("Add project", fontSize = 15.sp, fontWeight = FontWeight.Bold)
                        }
                    }
                }
            }
        }
    }
}
