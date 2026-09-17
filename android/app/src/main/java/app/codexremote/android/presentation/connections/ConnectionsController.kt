package app.codexremote.android.presentation.connections

import androidx.compose.runtime.State
import androidx.compose.runtime.mutableStateOf
import app.codexremote.android.RemoteConnection
import app.codexremote.android.RemoteProject
import app.codexremote.android.RemoteProjectStore

class ConnectionsController(
    private val onConnectServer: (String) -> Unit = {},
    private val onDeleteConfirmed: (Set<String>) -> Unit = {},
    private val onPairSubmit: (String, String) -> Unit = { _, _ -> },
    private val onAddProject: (String) -> Unit = {},
    private val onSelectProject: (RemoteProject) -> Unit = {},
    private val onSaveConnection: (originalServer: String, name: String, newServer: String, pairingCode: String) -> Unit = { _, _, _, _ -> },
    private val onRotateCredential: (String) -> Unit = {},
    private val onRevokeCredential: (String) -> Unit = {},
    private val onRemoveProject: (RemoteProject) -> Unit = {}
) {
    private val _uiState = mutableStateOf(ConnectionsUiState())
    val uiState: State<ConnectionsUiState> = _uiState

    fun updateConnections(
        projects: List<RemoteProject>,
        activeProject: String?,
        activeServer: String?,
        connectedUrls: Set<String>,
        connectingUrls: Set<String>,
        savedConnections: List<RemoteConnection>? = null
    ) {
        val grouped = savedConnections ?: RemoteProjectStore.groupConnections(projects)
        _uiState.value = _uiState.value.copy(
            connections = grouped,
            selectedServers = _uiState.value.selectedServers.intersect(grouped.map { it.serverUrl }.toSet()),
            activeProjectId = activeProject,
            activeServerUrl = activeServer,
            connectedServerUrls = connectedUrls,
            connectingServers = connectingUrls
        )
    }

    fun openManager(asRoot: Boolean = false) {
        _uiState.value = _uiState.value.copy(
            isManagerOpen = true,
            isRoot = asRoot
        )
    }

    fun closeManager() {
        if (!_uiState.value.isRoot) {
            _uiState.value = _uiState.value.copy(isManagerOpen = false)
        }
    }

    fun connectServer(serverUrl: String) {
        onConnectServer(serverUrl)
    }

    fun selectProject(project: RemoteProject) {
        onSelectProject(project)
        closeManager()
    }

    fun requestDelete(servers: Set<String>, names: List<String>) {
        _uiState.value = _uiState.value.copy(
            deleteConfirmationServers = servers,
            deleteConfirmationNames = names
        )
    }

    fun confirmDelete() {
        val servers = _uiState.value.deleteConfirmationServers ?: return
        _uiState.value = _uiState.value.copy(deleteConfirmationServers = null, deleteConfirmationNames = emptyList())
        exitSelection()
        onDeleteConfirmed(servers)
    }

    fun dismissDelete() {
        _uiState.value = _uiState.value.copy(deleteConfirmationServers = null, deleteConfirmationNames = emptyList())
    }

    fun startPairing(serverUrl: String) {
        _uiState.value = _uiState.value.copy(
            pairingServerUrl = serverUrl,
            pairingCode = "",
            pairingError = null
        )
    }

    fun updatePairingCode(code: String) {
        _uiState.value = _uiState.value.copy(pairingCode = code, pairingError = null)
    }

    fun submitPairing() {
        val server = _uiState.value.pairingServerUrl ?: return
        val code = _uiState.value.pairingCode.trim()
        if (code.isBlank()) {
            _uiState.value = _uiState.value.copy(pairingError = "Enter a pairing code")
            return
        }
        _uiState.value = _uiState.value.copy(isBusy = true)
        onPairSubmit(server, code)
    }

    fun finishPairing(error: String? = null) {
        _uiState.value = _uiState.value.copy(
            isBusy = false,
            pairingError = error,
            pairingServerUrl = if (error == null) null else _uiState.value.pairingServerUrl
        )
    }

    fun dismissPairing() {
        _uiState.value = _uiState.value.copy(
            pairingServerUrl = null,
            pairingCode = "",
            pairingError = null,
            isBusy = false
        )
    }

    fun showConversation() {
        _uiState.value = _uiState.value.copy(isManagerOpen = false, isRoot = false)
    }

    fun startEditing(connection: RemoteConnection) {
        _uiState.value = _uiState.value.copy(
            editingConnection = connection,
            settingsServer = connection.serverUrl,
            settingsName = connection.name,
            settingsNewServer = connection.serverUrl,
            settingsPairingCode = "",
            settingsStatus = "",
            settingsCredentialSummary = "",
            settingsAccountStatus = "",
            settingsError = null,
            isSettingsBusy = false
        )
    }

    fun dismissEditing() {
        _uiState.value = _uiState.value.copy(
            editingConnection = null,
            settingsServer = null,
            settingsName = "",
            settingsNewServer = "",
            settingsPairingCode = "",
            settingsError = null,
            isSettingsBusy = false,
            revokeConfirmationServer = null
        )
    }

    fun updateSettingsName(name: String) {
        _uiState.value = _uiState.value.copy(settingsName = name, settingsError = null)
    }

    fun updateSettingsServer(server: String) {
        _uiState.value = _uiState.value.copy(settingsNewServer = server, settingsError = null)
    }

    fun updateSettingsPairingCode(code: String) {
        _uiState.value = _uiState.value.copy(settingsPairingCode = code, settingsError = null)
    }

    fun updateSettingsStatus(
        server: String,
        status: String,
        credentialSummary: String,
        accountStatus: String,
        busy: Boolean = false
    ) {
        if (_uiState.value.settingsServer == server || _uiState.value.editingConnection?.serverUrl == server) {
            _uiState.value = _uiState.value.copy(
                settingsStatus = status,
                settingsCredentialSummary = credentialSummary,
                settingsAccountStatus = accountStatus,
                isSettingsBusy = busy
            )
        }
    }

    fun saveSettings() {
        val originalServer = _uiState.value.settingsServer ?: return
        if (_uiState.value.isSettingsBusy) return
        val name = _uiState.value.settingsName.trim()
        val newServer = _uiState.value.settingsNewServer.trim()
        val pairingCode = _uiState.value.settingsPairingCode.trim()

        if (newServer.isBlank()) {
            _uiState.value = _uiState.value.copy(settingsError = "Host URL cannot be empty")
            return
        }

        _uiState.value = _uiState.value.copy(isSettingsBusy = true, settingsError = null)
        onSaveConnection(originalServer, name, newServer, pairingCode)
    }

    fun finishSaveSettings(error: String? = null) {
        if (error == null) {
            dismissEditing()
        } else {
            _uiState.value = _uiState.value.copy(isSettingsBusy = false, settingsError = error)
        }
    }

    fun rotateCredential(server: String) {
        if (_uiState.value.isSettingsBusy) return
        _uiState.value = _uiState.value.copy(isSettingsBusy = true)
        onRotateCredential(server)
    }

    fun requestRevokeCredential(server: String) {
        _uiState.value = _uiState.value.copy(revokeConfirmationServer = server)
    }

    fun dismissRevokeCredential() {
        _uiState.value = _uiState.value.copy(revokeConfirmationServer = null)
    }

    fun confirmRevokeCredential() {
        val server = _uiState.value.revokeConfirmationServer ?: return
        _uiState.value = _uiState.value.copy(revokeConfirmationServer = null, isSettingsBusy = true)
        onRevokeCredential(server)
    }

    fun removeProject(project: RemoteProject) {
        onRemoveProject(project)
    }

    fun requestAddProject(serverUrl: String) {
        onAddProject(serverUrl)
    }

    fun startSelection(server: String) { _uiState.value = _uiState.value.copy(selectionMode = true, selectedServers = setOf(server)) }
    fun exitSelection() { _uiState.value = _uiState.value.copy(selectionMode = false, selectedServers = emptySet()) }
    fun toggleSelected(server: String) {
        val selected = _uiState.value.selectedServers
        _uiState.value = _uiState.value.copy(selectedServers = if (server in selected) selected - server else selected + server)
    }
    fun selectAll() {
        val all = _uiState.value.connections.map { it.serverUrl }.toSet()
        _uiState.value = _uiState.value.copy(selectedServers = if (_uiState.value.selectedServers == all) emptySet() else all)
    }
    fun deleteSelected() {
        val state = _uiState.value
        if (state.selectedServers.isNotEmpty()) requestDelete(state.selectedServers, state.connections.filter { it.serverUrl in state.selectedServers }.map { it.name })
    }
}
