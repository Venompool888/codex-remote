package app.codexremote.android.presentation.conversation

import app.codexremote.android.FileDiffDetail
import app.codexremote.android.TimelineItem

data class ConversationUiState(
    val threadId: String? = null,
    val threadTitle: String = "Chat",
    val threadStatus: String = "",
    val cwd: String = "",
    val workspaceLabel: String = "",
    val serverHost: String = "",
    val isConnected: Boolean = true,
    val connectionReason: String? = null,
    val isLoading: Boolean = false,
    val loadError: String? = null,
    val items: List<TimelineItem> = emptyList(),
    val isTurnRunning: Boolean = false,
    val messageDeliveries: Map<String, MessageDeliveryStatus> = emptyMap(),
    val expandedUserMessages: Set<String> = emptySet(),
    val expandedGroups: Set<String> = emptySet(),
    val expandedTools: Set<String> = emptySet(),
    val expandedFiles: Set<String> = emptySet(),
    val diffFiles: List<FileDiffDetail> = emptyList(),
    val selectedDiffPath: String? = null,
    val isDiffOpen: Boolean = false,
    val tokenUsagePercent: Int = 0,
    val totalTokens: Long? = null,
    val contextWindow: Long? = null,
    val showUsagePopup: Boolean = false
)
