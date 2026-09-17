package app.codexremote.android.compose

import app.codexremote.android.R

data class ComposerActionBarUiState(
    val canSend: Boolean = false,
    val isTurnRunning: Boolean = false,
    val isAwaitingAttachments: Boolean = false,
    val isExpanded: Boolean = false,
    val modelLabel: String = "Model",
    val hasFastTier: Boolean = false,
    val sendButtonContentDescription: String = "Send",
    val modelContentDescription: String = "Model and reasoning effort",
    val plusButtonContentDescription: String = "Composer options",
    val hasPermissionOptions: Boolean = false,
    val permissionLabel: String = "",
    val permissionContentDescription: String = "Permissions",
    val permissionIconRes: Int = R.drawable.ic_warning_amber
)

fun formatConciseModelLabel(model: String, effort: String = ""): String {
    val effortClean = if (effort.isNotBlank() && !effort.equals("Standard", ignoreCase = true)) {
        effort.replace("(", " ").replace(")", " ").trim()
    } else {
        ""
    }
    val combined = if (effortClean.isNotBlank()) {
        if (model.isNotBlank()) "$model $effortClean" else effortClean
    } else {
        model
    }
    if (combined.isBlank()) return ""

    // Remove a leading GPT prefix without changing the casing supplied by host metadata.
    var result = combined.trim().replace(Regex("""(?i)^gpt\s*[-\s]+"""), "")
    // Remove parentheses around effort or any parentheses
    result = result.replace("(", " ").replace(")", " ")
    // Normalize hyphens to spaces
    result = result.replace('-', ' ')
    // Normalize multiple whitespaces to single space and trim
    result = result.replace(Regex("""\s+"""), " ").trim()
    return result
}

fun calculateComposerActionBarUiState(
    turnRunning: Boolean,
    draftText: String,
    attachmentsCount: Int,
    awaitingAttachments: Boolean,
    isExpanded: Boolean,
    modelLabel: String,
    hasFastTier: Boolean,
    hasPermissionOptions: Boolean = false,
    permissionLabel: String = "",
    permissionIconRes: Int = R.drawable.ic_warning_amber,
    modelContentDescription: String? = null
): ComposerActionBarUiState {
    val canSend = turnRunning || (!awaitingAttachments && (draftText.isNotBlank() || attachmentsCount > 0))
    val sendDescription = if (turnRunning) {
        "Stop response"
    } else if (awaitingAttachments) {
        "Send unavailable until attachments are ready"
    } else {
        "Send"
    }
    val suppliedModel = modelLabel.trim()
    val rawModel = suppliedModel.ifBlank { "Model" }
    val conciseModel = formatConciseModelLabel(rawModel).ifBlank { rawModel }
    val modelDescription = if (!modelContentDescription.isNullOrBlank()) {
        modelContentDescription
    } else if (suppliedModel.isNotBlank()) {
        "Model and reasoning effort, $suppliedModel"
    } else {
        "Model and reasoning effort"
    }
    val permissionDescription = if (permissionLabel.isNotBlank()) {
        "Permissions, $permissionLabel"
    } else {
        "Permissions"
    }
    return ComposerActionBarUiState(
        canSend = canSend,
        isTurnRunning = turnRunning,
        isAwaitingAttachments = awaitingAttachments,
        isExpanded = isExpanded,
        modelLabel = conciseModel,
        hasFastTier = hasFastTier,
        sendButtonContentDescription = sendDescription,
        modelContentDescription = modelDescription,
        plusButtonContentDescription = "Composer options",
        hasPermissionOptions = hasPermissionOptions,
        permissionLabel = permissionLabel,
        permissionContentDescription = permissionDescription,
        permissionIconRes = permissionIconRes
    )
}
