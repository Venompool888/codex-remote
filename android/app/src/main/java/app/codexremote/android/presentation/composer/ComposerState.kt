package app.codexremote.android.presentation.composer

import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import app.codexremote.android.R
import app.codexremote.android.compose.AttachmentItemUiState
import app.codexremote.android.compose.ComposerActionBarUiState
import app.codexremote.android.compose.calculateComposerActionBarUiState
import app.codexremote.android.compose.formatConciseModelLabel

data class ComposerOption(
    val id: String,
    val label: String,
    val description: String = "",
    val enabled: Boolean = true
)

fun resolvePermissionIcon(id: String?): Int = when {
    id == null -> R.drawable.ic_codex_permission_hand
    id.contains("full", ignoreCase = true) -> R.drawable.ic_warning_amber
    id.contains("guardian", ignoreCase = true) || id.contains("auto-review", ignoreCase = true) -> R.drawable.ic_permission_auto_review
    id.contains("workspace", ignoreCase = true) -> R.drawable.ic_codex_permission_hand
    id.contains("read", ignoreCase = true) -> R.drawable.ic_permission_read_only
    else -> R.drawable.ic_codex_permission_custom
}

data class ComposerUiState(
    val textFieldValue: TextFieldValue = TextFieldValue(),
    val attachments: List<AttachmentItemUiState> = emptyList(),
    val isTurnRunning: Boolean = false,
    val canSteer: Boolean = false,
    val isSteering: Boolean = false,
    val isAwaitingAttachments: Boolean = false,
    val isExpanded: Boolean = false,
    val modelOptions: List<ComposerOption> = emptyList(),
    val selectedModelId: String? = null,
    val effortOptions: List<ComposerOption> = emptyList(),
    val selectedEffortId: String? = null,
    val serviceTierOptions: List<ComposerOption> = emptyList(),
    val selectedServiceTierId: String? = null,
    val permissionOptions: List<ComposerOption> = emptyList(),
    val selectedPermissionId: String? = null,
    val pendingPermissionId: String? = null,
    val planMode: Boolean = false,
    val draftIdentity: String? = null,
    val hint: String? = null,
    val showAddMenu: Boolean = false,
    val showModelMenu: Boolean = false,
    val showPermissionMenu: Boolean = false,
    val showFullAccessWarning: Boolean = false
) {
    val text: String get() = textFieldValue.text
    val selection: TextRange get() = textFieldValue.selection

    val activeModel: String
        get() = modelOptions.firstOrNull { it.id == selectedModelId }?.label
            ?: selectedModelId
            ?: ""

    val availableModels: List<String>
        get() = modelOptions.map { it.label }

    val reasoningEffort: String
        get() = effortOptions.firstOrNull { it.id == selectedEffortId }?.label
            ?: selectedEffortId
            ?: ""

    val availableEfforts: List<String>
        get() = effortOptions.map { it.label }

    val hasFastTier: Boolean
        get() = serviceTierOptions.any {
            it.id == selectedServiceTierId && it.id.isNotBlank() && it.enabled &&
                (it.id == "fast" || it.id == "priority" || it.label.equals("Fast", ignoreCase = true))
        }

    val permissionMode: String
        get() = permissionOptions.firstOrNull { it.id == selectedPermissionId }?.label
            ?: selectedPermissionId
            ?: ""

    val availablePermissionModes: List<String>
        get() = permissionOptions.map { it.label }

    val fullModelLabel: String
        get() = if (reasoningEffort.isNotBlank() && reasoningEffort != "Standard") {
            "$activeModel ($reasoningEffort)"
        } else {
            activeModel
        }

    val conciseModelLabel: String
        get() = formatConciseModelLabel(activeModel, reasoningEffort).ifBlank { activeModel.ifBlank { "Model" } }

    val actionBarState: ComposerActionBarUiState
        get() = calculateComposerActionBarUiState(
            turnRunning = isTurnRunning,
            draftText = text,
            attachmentsCount = attachments.size,
            awaitingAttachments = isAwaitingAttachments,
            isExpanded = isExpanded || text.isNotBlank() || attachments.isNotEmpty() || isTurnRunning,
            modelLabel = conciseModelLabel,
            hasFastTier = hasFastTier,
            hasPermissionOptions = permissionOptions.isNotEmpty(),
            permissionLabel = permissionMode,
            permissionIconRes = resolvePermissionIcon(selectedPermissionId),
            modelContentDescription = if (fullModelLabel.isNotBlank()) "Model and reasoning effort, $fullModelLabel" else "Model and reasoning effort"
        )
}
