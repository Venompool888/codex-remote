package app.codexremote.android.presentation.conversation

import androidx.compose.runtime.State
import androidx.compose.runtime.mutableStateOf
import app.codexremote.android.FileDiffDetail
import app.codexremote.android.TimelineItem

class ConversationController(
    private val onOpenSidebar: () -> Unit = {},
    private val onOpenArtifacts: () -> Unit = {},
    private val onCopyText: (String, String) -> Unit = { _, _ -> },
    private val onOpenConnections: () -> Unit = {},
    private val onEnableNotifications: () -> Unit = {},
    private val onShowDiagnostics: () -> Unit = {},
    private val onOpenModel: () -> Unit = {},
    private val onOpenPermissions: () -> Unit = {},
    private val onRetryLoading: () -> Unit = {},
    private val onExportDiagnostics: () -> Unit = {},
    private val onLoadOlderHistory: () -> Unit = {},
    private val onOpenSubagentDirectory: () -> Unit = {},
    val subagents: SubagentViewerController = SubagentViewerController()
) {
    private val _uiState = mutableStateOf(ConversationUiState())
    val uiState: State<ConversationUiState> = _uiState
    var isThreadLoadPending: Boolean = false
        private set

    fun updateThread(
        threadId: String?,
        title: String,
        cwd: String,
        workspaceLabel: String,
        serverHost: String,
        isConnected: Boolean,
        reason: String? = null
    ) {
        val previous = _uiState.value
        if (previous.threadId != threadId || previous.serverHost != serverHost || !isConnected) subagents.close()
        _uiState.value = _uiState.value.copy(
            threadId = threadId,
            threadTitle = title,
            cwd = cwd,
            workspaceLabel = workspaceLabel,
            serverHost = serverHost,
            isConnected = isConnected,
            connectionReason = reason
        )
    }

    fun setTimelineItems(items: List<TimelineItem>, isTurnRunning: Boolean, messageDeliveries: Map<String, MessageDeliveryStatus> = emptyMap()) {
        _uiState.value = _uiState.value.copy(
            items = items,
            messageDeliveries = messageDeliveries,
            isTurnRunning = isTurnRunning,
            isLoading = isThreadLoadPending
        )
    }

    fun setSubagentDiscoveryAvailable(available: Boolean) {
        _uiState.value = _uiState.value.copy(canBrowseSubagents = available)
    }
    fun openSubagentDirectory() { if (_uiState.value.canBrowseSubagents) onOpenSubagentDirectory() }
    fun setHistoryPagingUiReady(ready: Boolean) { _uiState.value = _uiState.value.copy(historyPagingUiReady = ready) }

    fun setThreadStatus(status: String) {
        _uiState.value = _uiState.value.copy(threadStatus = status)
    }

    fun setHistoryPaging(hasMore: Boolean, loading: Boolean = false, error: String? = null) {
        _uiState.value = _uiState.value.copy(hasOlderHistory = hasMore, loadingOlderHistory = loading, historyPageError = error)
    }

    fun loadOlderHistory() {
        if (_uiState.value.hasOlderHistory && !_uiState.value.loadingOlderHistory) onLoadOlderHistory()
    }

    fun setLoading(loading: Boolean) {
        isThreadLoadPending = false
        _uiState.value = _uiState.value.copy(isLoading = loading, loadError = null)
    }

    fun beginThreadLoad() {
        isThreadLoadPending = true
        _uiState.value = _uiState.value.copy(isLoading = true, loadError = null, items = emptyList())
    }

    fun finishThreadLoad() {
        isThreadLoadPending = false
        _uiState.value = _uiState.value.copy(isLoading = false, loadError = null)
    }

    fun failThreadLoad(message: String) {
        isThreadLoadPending = false
        _uiState.value = _uiState.value.copy(isLoading = false, loadError = message)
    }

    fun retryLoading() { if (!isThreadLoadPending && _uiState.value.loadError != null) onRetryLoading() }

    fun toggleUserMessageExpansion(id: String) {
        val set = _uiState.value.expandedUserMessages.toMutableSet()
        if (id in set) set.remove(id) else set.add(id)
        _uiState.value = _uiState.value.copy(expandedUserMessages = set)
    }

    fun toggleGroupExpansion(id: String) {
        val set = _uiState.value.expandedGroups.toMutableSet()
        if (id in set) set.remove(id) else set.add(id)
        _uiState.value = _uiState.value.copy(expandedGroups = set)
    }

    fun toggleToolExpansion(id: String) {
        val set = _uiState.value.expandedTools.toMutableSet()
        if (id in set) set.remove(id) else set.add(id)
        _uiState.value = _uiState.value.copy(expandedTools = set)
    }

    fun toggleFileExpansion(id: String) {
        val set = _uiState.value.expandedFiles.toMutableSet()
        if (id in set) set.remove(id) else set.add(id)
        _uiState.value = _uiState.value.copy(expandedFiles = set)
    }

    fun openDiff(files: List<FileDiffDetail>, initialPath: String) {
        _uiState.value = _uiState.value.copy(
            diffFiles = files,
            selectedDiffPath = initialPath,
            isDiffOpen = true
        )
    }

    fun selectDiffFile(path: String) {
        _uiState.value = _uiState.value.copy(selectedDiffPath = path)
    }

    fun closeDiff() {
        _uiState.value = _uiState.value.copy(isDiffOpen = false)
    }

    fun updateTokenUsage(percent: Int, totalTokens: Long? = null, contextWindow: Long? = null) {
        _uiState.value = _uiState.value.copy(tokenUsagePercent = percent.coerceIn(0, 100), totalTokens = totalTokens, contextWindow = contextWindow)
    }

    fun toggleUsagePopup(visible: Boolean? = null) {
        val next = visible ?: !_uiState.value.showUsagePopup
        _uiState.value = _uiState.value.copy(showUsagePopup = next)
    }

    fun resetDisclosures() {
        _uiState.value = _uiState.value.copy(
            expandedUserMessages = emptySet(),
            expandedGroups = emptySet(),
            expandedTools = emptySet(),
            expandedFiles = emptySet()
        )
    }

    fun openSidebar() = onOpenSidebar()
    fun openArtifacts() = onOpenArtifacts()
    fun openConnections() = onOpenConnections()
    fun enableNotifications() = onEnableNotifications()
    fun showDiagnostics() = onShowDiagnostics()
    fun exportDiagnostics() = onExportDiagnostics()
    fun openModel() = onOpenModel()
    fun openPermissions() = onOpenPermissions()
    fun copyText(text: String, label: String) = onCopyText(text, label)
}
