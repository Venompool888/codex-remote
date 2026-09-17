package app.codexremote.android.presentation.interactions

import androidx.compose.runtime.State
import androidx.compose.runtime.mutableStateOf
import app.codexremote.android.InteractionFormModel
import org.json.JSONObject

class InteractionsController(
    private val onSubmitReply: (String, JSONObject, (Boolean) -> Unit) -> Unit = { _, _, cb -> cb(true) },
    private val onCancelReply: (String, JSONObject, (Boolean) -> Unit) -> Unit = { _, _, cb -> cb(true) },
    private val onOpenUrl: (String) -> Unit = {}
) {
    private val _uiState = mutableStateOf<InteractionsUiState?>(null)
    val uiState: State<InteractionsUiState?> = _uiState

    fun showInteraction(key: String, method: String, params: JSONObject, savedDraft: JSONObject? = null) {
        val form = runCatching { InteractionFormModel(method, params, savedDraft) }.getOrNull()
        if (form == null) return
        _uiState.value = InteractionsUiState(
            activeKey = key,
            method = method,
            form = form
        )
    }

    fun dismissInteraction(key: String) {
        if (_uiState.value?.activeKey == key) {
            _uiState.value = null
        }
    }

    fun rejected(message: String) {
        val current = _uiState.value ?: return
        _uiState.value = current.copy(
            isBusy = false,
            isSubmitting = false,
            isRejected = true,
            statusMessage = message
        )
    }

    fun setText(id: String, value: String) {
        val current = _uiState.value ?: return
        if (current.isBusy) return
        current.form?.setText(id, value)
        _uiState.value = current.copy(
            errorMessage = null,
            revision = current.revision + 1
        )
    }

    fun setChecked(id: String, value: Boolean) {
        val current = _uiState.value ?: return
        if (current.isBusy) return
        current.form?.setChecked(id, value)
        _uiState.value = current.copy(
            errorMessage = null,
            revision = current.revision + 1
        )
    }

    fun select(id: String, value: String, selected: Boolean = true) {
        val current = _uiState.value ?: return
        if (current.isBusy) return
        current.form?.select(id, value, selected)
        _uiState.value = current.copy(
            errorMessage = null,
            revision = current.revision + 1
        )
    }

    fun submit() {
        val current = _uiState.value ?: return
        if (current.isBusy || current.isSubmitting) return
        val form = current.form ?: return
        val activeKey = current.activeKey ?: return

        val replyJson = try {
            form.reply()
        } catch (e: Exception) {
            _uiState.value = current.copy(errorMessage = e.message ?: "Please complete all required fields")
            return
        }

        _uiState.value = current.copy(isBusy = true, isSubmitting = true, errorMessage = null)
        onSubmitReply(activeKey, replyJson) { acknowledged ->
            val pending = _uiState.value
            if (pending != null && pending.activeKey == activeKey && pending.form === form) {
                _uiState.value = if (acknowledged) null else pending.copy(isBusy = false, isSubmitting = false)
            }
        }
    }

    fun cancel() {
        val current = _uiState.value ?: return
        if (current.isBusy || current.isSubmitting) return
        val form = current.form ?: return
        val activeKey = current.activeKey ?: return

        val cancelJson = form.cancelReply()
        _uiState.value = current.copy(isBusy = true, isSubmitting = true, errorMessage = null)
        onCancelReply(activeKey, cancelJson) { acknowledged ->
            val pending = _uiState.value
            if (pending != null && pending.activeKey == activeKey && pending.form === form) {
                _uiState.value = if (acknowledged) null else pending.copy(isBusy = false, isSubmitting = false)
            }
        }
    }

    fun openWebsiteUrl() {
        val url = _uiState.value?.form?.websiteUrl ?: return
        onOpenUrl(url)
    }

    fun saveDraft(): JSONObject? = _uiState.value?.form?.saveDraft()
}
