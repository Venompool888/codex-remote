package app.codexremote.android.presentation.composer

import androidx.compose.runtime.State
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import app.codexremote.android.compose.AttachmentItemUiState

class ComposerController(
    private val onSend: (String, List<AttachmentItemUiState>, onDispatched: (Boolean) -> Unit) -> Unit = { _, _, _ -> },
    private val onStopTurn: () -> Unit = {},
    private val onPickFiles: () -> Unit = {},
    private val onPickPhotos: () -> Unit = {},
    private val onOpenSkillsCatalog: () -> Unit = {},
    private val onOpenAttachmentImage: (AttachmentItemUiState) -> Unit = {},
    private val onRemoveAttachment: (AttachmentItemUiState) -> Unit = {},
    private val onModelChanged: (String) -> Unit = {},
    private val onReasoningEffortChanged: (String) -> Unit = {},
    private val onPermissionModeChanged: (String) -> Unit = {},
    private val onServiceTierChanged: (String) -> Unit = {},
    private val onPlanModeChanged: (Boolean) -> Unit = {},
    private val onTextChanged: (TextFieldValue) -> Unit = {}
) {
    private val _uiState = mutableStateOf(ComposerUiState())
    val uiState: State<ComposerUiState> = _uiState

    fun updateTextFieldValue(value: TextFieldValue) {
        _uiState.value = _uiState.value.copy(
            textFieldValue = value,
            isExpanded = _uiState.value.isExpanded || value.text.isNotBlank()
        )
        onTextChanged(value)
    }

    fun updateText(text: String, selection: TextRange = TextRange(text.length)) {
        val value = TextFieldValue(text = text, selection = selection)
        _uiState.value = _uiState.value.copy(
            textFieldValue = value,
            isExpanded = _uiState.value.isExpanded || text.isNotBlank()
        )
        onTextChanged(value)
    }

    fun setAttachments(attachments: List<AttachmentItemUiState>, awaiting: Boolean = false) {
        _uiState.value = _uiState.value.copy(
            attachments = attachments,
            isAwaitingAttachments = awaiting,
            isExpanded = _uiState.value.isExpanded || attachments.isNotEmpty()
        )
    }

    fun removeAttachment(item: AttachmentItemUiState) {
        onRemoveAttachment(item)
        val next = _uiState.value.attachments.filterNot { it.localId == item.localId }
        _uiState.value = _uiState.value.copy(attachments = next)
    }

    fun clickAttachment(item: AttachmentItemUiState) {
        onOpenAttachmentImage(item)
    }

    fun setTurnRunning(running: Boolean) {
        _uiState.value = _uiState.value.copy(isTurnRunning = running)
    }

    fun setExpanded(expanded: Boolean) {
        _uiState.value = _uiState.value.copy(isExpanded = expanded)
    }

    fun setModelOptions(options: List<ComposerOption>, selected: String?) {
        _uiState.value = _uiState.value.copy(
            modelOptions = options,
            selectedModelId = selected ?: options.firstOrNull { it.enabled }?.id
        )
    }

    fun setEffortOptions(options: List<ComposerOption>, selected: String?) {
        _uiState.value = _uiState.value.copy(
            effortOptions = options,
            selectedEffortId = selected ?: options.firstOrNull { it.enabled }?.id
        )
    }

    fun setServiceTierOptions(options: List<ComposerOption>, selected: String?) {
        _uiState.value = _uiState.value.copy(
            serviceTierOptions = options,
            selectedServiceTierId = selected ?: options.firstOrNull { it.enabled }?.id
        )
    }

    fun setPermissionOptions(options: List<ComposerOption>, selected: String?) {
        _uiState.value = _uiState.value.copy(
            permissionOptions = options,
            selectedPermissionId = selected ?: options.firstOrNull { it.enabled }?.id
        )
    }

    fun setPlanMode(enabled: Boolean) {
        _uiState.value = _uiState.value.copy(planMode = enabled)
        onPlanModeChanged(enabled)
    }

    fun setDraftIdentity(identity: String?) {
        if (_uiState.value.draftIdentity == identity) return
        _uiState.value = _uiState.value.copy(draftIdentity = identity,
            showAddMenu = false, showModelMenu = false, showPermissionMenu = false,
            pendingPermissionId = null, showFullAccessWarning = false)
    }

    fun setHint(hint: String?) {
        _uiState.value = _uiState.value.copy(hint = hint)
    }

    // Compatibility methods
    fun setAvailableModels(models: List<String>, current: String? = null) {
        val options = models.map { ComposerOption(id = it, label = it) }
        setModelOptions(options, current ?: _uiState.value.selectedModelId)
    }

    fun setAvailableEfforts(efforts: List<String>, current: String? = null) {
        val options = efforts.map { ComposerOption(id = it, label = it) }
        setEffortOptions(options, current ?: _uiState.value.selectedEffortId)
    }

    fun setFastTier(hasFastTier: Boolean) {
        val options = if (hasFastTier) {
            listOf(ComposerOption(id = "fast", label = "Fast"))
        } else {
            emptyList()
        }
        setServiceTierOptions(options, if (hasFastTier) "fast" else null)
    }

    fun setModel(modelId: String) {
        _uiState.value = _uiState.value.copy(selectedModelId = modelId, showModelMenu = false)
        onModelChanged(modelId)
    }

    fun setReasoningEffort(effortId: String) {
        _uiState.value = _uiState.value.copy(selectedEffortId = effortId, showModelMenu = false)
        onReasoningEffortChanged(effortId)
    }

    fun setServiceTier(tierId: String) {
        _uiState.value = _uiState.value.copy(selectedServiceTierId = tierId)
        onServiceTierChanged(tierId)
    }

    fun selectPermission(modeId: String) {
        val option = _uiState.value.permissionOptions.firstOrNull { it.id == modeId }
        if (option == null || !option.enabled) return

        val isFullAccess = modeId.contains("full", ignoreCase = true)
        if (isFullAccess) {
            _uiState.value = _uiState.value.copy(
                pendingPermissionId = modeId,
                showFullAccessWarning = true,
                showPermissionMenu = false
            )
        } else {
            _uiState.value = _uiState.value.copy(
                selectedPermissionId = modeId,
                showPermissionMenu = false
            )
            onPermissionModeChanged(modeId)
        }
    }

    fun confirmFullAccess() {
        val id = _uiState.value.pendingPermissionId ?: return
        if (_uiState.value.permissionOptions.none { it.id == id && it.enabled }) { dismissFullAccessWarning(); return }
        _uiState.value = _uiState.value.copy(
            selectedPermissionId = id,
            pendingPermissionId = null,
            showFullAccessWarning = false
        )
        onPermissionModeChanged(id)
    }

    fun dismissFullAccessWarning() {
        _uiState.value = _uiState.value.copy(
            pendingPermissionId = null,
            showFullAccessWarning = false
        )
    }

    fun setPermissionMode(mode: String) {
        selectPermission(mode)
    }

    fun toggleAddMenu(show: Boolean? = null) {
        val next = show ?: !_uiState.value.showAddMenu
        _uiState.value = _uiState.value.copy(showAddMenu = next)
    }

    fun toggleModelMenu(show: Boolean? = null) {
        val next = show ?: !_uiState.value.showModelMenu
        _uiState.value = _uiState.value.copy(showModelMenu = next)
    }

    fun togglePermissionMenu(show: Boolean? = null) {
        val next = show ?: !_uiState.value.showPermissionMenu
        _uiState.value = _uiState.value.copy(showPermissionMenu = next)
    }

    fun toggleFullAccessWarning(show: Boolean? = null) {
        val next = show ?: !_uiState.value.showFullAccessWarning
        _uiState.value = _uiState.value.copy(showFullAccessWarning = next)
    }

    fun closeMenus(): Boolean {
        val s = _uiState.value
        if (s.showAddMenu || s.showModelMenu || s.showPermissionMenu || s.showFullAccessWarning) {
            _uiState.value = s.copy(
                showAddMenu = false,
                showModelMenu = false,
                showPermissionMenu = false,
                showFullAccessWarning = false,
                pendingPermissionId = null
            )
            return true
        }
        return false
    }

    fun clearDraft() {
        _uiState.value = _uiState.value.copy(
            textFieldValue = TextFieldValue(),
            attachments = emptyList(),
            isExpanded = false
        )
    }

    fun sendOrStop() {
        if (_uiState.value.isTurnRunning) {
            onStopTurn()
            return
        }
        if (_uiState.value.isAwaitingAttachments) {
            // Block send while attachments are awaiting upload
            return
        }
        val currentIdentity = _uiState.value.draftIdentity
        val currentText = _uiState.value.text
        val currentAtts = _uiState.value.attachments
        if (currentText.isNotBlank() || currentAtts.isNotEmpty()) {
            onSend(currentText, currentAtts) { success ->
                if (success) {
                    if (_uiState.value.draftIdentity == currentIdentity &&
                        _uiState.value.text == currentText &&
                        _uiState.value.attachments == currentAtts) {
                        clearDraft()
                    }
                }
            }
        }
    }

    fun pickFiles() {
        toggleAddMenu(false)
        onPickFiles()
    }

    fun pickPhotos() {
        toggleAddMenu(false)
        onPickPhotos()
    }

    fun openSkillsCatalog() {
        toggleAddMenu(false)
        onOpenSkillsCatalog()
    }
}
