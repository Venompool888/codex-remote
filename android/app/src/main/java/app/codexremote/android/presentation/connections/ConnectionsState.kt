package app.codexremote.android.presentation.connections

import app.codexremote.android.RemoteConnection
import app.codexremote.android.RemoteProject

data class ConnectionsUiState(
    val selectionMode: Boolean = false,
    val selectedServers: Set<String> = emptySet(),
    val connections: List<RemoteConnection> = emptyList(),
    val connectedServerUrls: Set<String> = emptySet(),
    val connectingServers: Set<String> = emptySet(),
    val activeServerUrl: String? = null,
    val activeProjectId: String? = null,
    val isManagerOpen: Boolean = false,
    val isRoot: Boolean = false,
    val deleteConfirmationServers: Set<String>? = null,
    val deleteConfirmationNames: List<String> = emptyList(),
    val editingConnection: RemoteConnection? = null,
    val settingsServer: String? = null,
    val settingsName: String = "",
    val settingsNewServer: String = "",
    val settingsPairingCode: String = "",
    val settingsStatus: String = "",
    val settingsCredentialSummary: String = "",
    val settingsAccountStatus: String = "",
    val settingsError: String? = null,
    val isSettingsBusy: Boolean = false,
    val revokeConfirmationServer: String? = null,
    val pairingServerUrl: String? = null,
    val pairingCode: String = "",
    val pairingError: String? = null,
    val isBusy: Boolean = false
)
