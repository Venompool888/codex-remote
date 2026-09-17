package app.codexremote.android.presentation.interactions

import app.codexremote.android.InteractionFormModel

data class InteractionsUiState(
    val activeKey: String? = null,
    val method: String = "",
    val form: InteractionFormModel? = null,
    val isBusy: Boolean = false,
    val isSubmitting: Boolean = false,
    val errorMessage: String? = null,
    val isRejected: Boolean = false,
    val statusMessage: String? = null,
    val revision: Long = 0L
)
