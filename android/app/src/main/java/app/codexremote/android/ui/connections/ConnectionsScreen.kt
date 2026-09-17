package app.codexremote.android.ui.connections

import androidx.compose.foundation.background
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.TextButton
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.activity.compose.BackHandler
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.codexremote.android.R
import app.codexremote.android.presentation.connections.ConnectionsController
import app.codexremote.android.ui.theme.AppColors

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ConnectionsScreen(
    controller: ConnectionsController,
    onOpenAddProject: (String?) -> Unit,
    modifier: Modifier = Modifier,
    onOpenAddConnection: () -> Unit = {}
) {
    val state = controller.uiState.value
    BackHandler(enabled = state.selectionMode) { controller.exitSelection() }

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(AppColors.background)
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 20.dp)
        ) {
            // Header
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 16.dp, bottom = 12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                if (!state.isRoot) {
                    IconButton(onClick = { controller.closeManager() }) {
                        Icon(
                            painter = painterResource(R.drawable.ic_close),
                            contentDescription = "Close",
                            tint = AppColors.onSurface
                        )
                    }
                    Spacer(modifier = Modifier.width(8.dp))
                } else if (state.connections.isNotEmpty() || state.activeProjectId != null) {
                    IconButton(onClick = { controller.showConversation() }) {
                        Icon(
                            painter = painterResource(R.drawable.ic_arrow_back),
                            contentDescription = "Back to conversation",
                            tint = AppColors.onSurface
                        )
                    }
                    Spacer(modifier = Modifier.width(8.dp))
                }

                Text(
                    text = if (state.selectionMode) {
                        "${state.selectedServers.size} selected"
                    } else if (state.connections.isEmpty()) {
                        "Connections"
                    } else {
                        "Connection management"
                    },
                    fontSize = 22.sp,
                    fontWeight = FontWeight.Bold,
                    color = AppColors.onSurface,
                    modifier = Modifier.weight(1f)
                )

                if (!state.selectionMode && state.connections.isNotEmpty()) Button(
                    onClick = onOpenAddConnection,
                    shape = RoundedCornerShape(20.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = AppColors.primary,
                        contentColor = AppColors.onPrimary
                    )
                ) {
                    Icon(
                        painter = painterResource(R.drawable.ic_codex_plus),
                        contentDescription = null,
                        modifier = Modifier.size(18.dp)
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text("Add Connection", fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
                }
            }

            if (state.selectionMode) FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TextButton(onClick = controller::exitSelection) { Text("Cancel") }
                TextButton(onClick = controller::selectAll) { Text(if (state.selectedServers.size == state.connections.size) "Deselect all" else "Select all") }
                Button(onClick = controller::deleteSelected, enabled = state.selectedServers.isNotEmpty()) { Text("Delete selected") }
            }
            // Body
            if (state.connections.isEmpty()) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(bottom = 64.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        modifier = Modifier.padding(horizontal = 32.dp)
                    ) {
                        Icon(
                            painter = painterResource(R.drawable.ic_computer),
                            contentDescription = null,
                            tint = AppColors.onSurfaceMuted,
                            modifier = Modifier.size(48.dp)
                        )
                        Spacer(modifier = Modifier.height(16.dp))
                        Text(
                            text = "No connections yet",
                            fontSize = 18.sp,
                            fontWeight = FontWeight.Bold,
                            color = AppColors.onSurface,
                            textAlign = TextAlign.Center
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(
                            text = "Connect to your remote development host using a pair code or by scanning a QR code.",
                            fontSize = 14.sp,
                            color = AppColors.onSurfaceVariant,
                            textAlign = TextAlign.Center
                        )
                        Spacer(modifier = Modifier.height(24.dp))
                        Button(
                            onClick = onOpenAddConnection,
                            shape = RoundedCornerShape(22.dp),
                            colors = ButtonDefaults.buttonColors(
                                containerColor = AppColors.primary,
                                contentColor = AppColors.onPrimary
                            )
                        ) {
                            Text("Add Connection", fontSize = 15.sp, fontWeight = FontWeight.Bold)
                        }
                    }
                }
            } else {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .verticalScroll(rememberScrollState())
                        .padding(vertical = 12.dp),
                    verticalArrangement = Arrangement.spacedBy(16.dp)
                ) {
                    state.connections.forEach { conn ->
                        ConnectionCard(
                            connection = conn,
                            isConnected = conn.serverUrl in state.connectedServerUrls,
                            isConnecting = conn.serverUrl in state.connectingServers,
                            activeProjectId = state.activeProjectId,
                            onSelectProject = { controller.selectProject(it) },
                            onReconnect = { controller.connectServer(it) },
                            onPair = { controller.startPairing(it) },
                            onAddProject = { onOpenAddProject(it) },
                            onDelete = {
                                controller.requestDelete(
                                    setOf(it.serverUrl),
                                    listOf(it.name.ifBlank { it.serverUrl })
                                )
                            },
                            onEdit = { controller.startEditing(it) },
                            selectionMode = state.selectionMode,
                            isSelected = conn.serverUrl in state.selectedServers,
                            onSelect = { controller.startSelection(conn.serverUrl) },
                            onToggleSelected = { controller.toggleSelected(conn.serverUrl) }
                        )
                    }
                    Spacer(modifier = Modifier.height(48.dp))
                }
            }
        }

        // Dialogs
        if (state.deleteConfirmationServers != null) {
            DeleteConnectionDialog(
                serverNames = state.deleteConfirmationNames,
                onConfirm = { controller.confirmDelete() },
                onDismiss = { controller.dismissDelete() }
            )
        }

        if (state.pairingServerUrl != null) {
            PairingDialog(
                serverUrl = state.pairingServerUrl,
                code = state.pairingCode,
                error = state.pairingError,
                isBusy = state.isBusy,
                onCodeChange = { controller.updatePairingCode(it) },
                onSubmit = { controller.submitPairing() },
                onDismiss = { controller.dismissPairing() }
            )
        }

        if (state.editingConnection != null) {
            ConnectionSettingsDialog(
                connection = state.editingConnection,
                controller = controller
            )
        }

        if (state.revokeConfirmationServer != null) {
            RevokeCredentialDialog(
                serverUrl = state.revokeConfirmationServer,
                onConfirm = { controller.confirmRevokeCredential() },
                onDismiss = { controller.dismissRevokeCredential() }
            )
        }
    }
}
