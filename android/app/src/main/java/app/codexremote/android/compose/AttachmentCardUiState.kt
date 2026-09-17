package app.codexremote.android.compose

import android.graphics.Bitmap

sealed interface AttachmentUploadState {
    object Preparing : AttachmentUploadState
    object Waiting : AttachmentUploadState
    data class Uploading(val progress: Int) : AttachmentUploadState
    object Ready : AttachmentUploadState
    data class Failed(val message: String = "Failed · retry") : AttachmentUploadState
    object Cancelled : AttachmentUploadState
    object Expired : AttachmentUploadState

    val displayText: String
        get() = when (this) {
            is Preparing -> "Preparing…"
            is Waiting -> "Waiting…"
            is Uploading -> "$progress%"
            is Ready -> ""
            is Failed -> message
            is Cancelled -> "Cancelled"
            is Expired -> "Expired"
        }
}

sealed interface AttachmentItemUiState {
    val localId: String
    val name: String
    val contentDescription: String
}

data class ImageAttachmentUiState(
    override val localId: String,
    override val name: String,
    val previewBitmap: Bitmap? = null,
    val localFilePath: String? = null,
    val uploadState: AttachmentUploadState = AttachmentUploadState.Ready,
    val isLoadingPreview: Boolean = false,
    override val contentDescription: String = name
) : AttachmentItemUiState

data class FileAttachmentUiState(
    override val localId: String,
    override val name: String,
    val sizeBytes: Long = 0L,
    val metadataText: String = "",
    val uploadState: AttachmentUploadState = AttachmentUploadState.Ready,
    override val contentDescription: String = name
) : AttachmentItemUiState

data class CapabilityTagUiState(
    override val localId: String,
    override val name: String,
    val description: String = "",
    override val contentDescription: String = name
) : AttachmentItemUiState

fun mergeAttachmentUiState(
    existing: AttachmentItemUiState,
    incoming: AttachmentItemUiState
): AttachmentItemUiState {
    return if (incoming is ImageAttachmentUiState && existing is ImageAttachmentUiState) {
        incoming.copy(
            previewBitmap = existing.previewBitmap ?: incoming.previewBitmap,
            isLoadingPreview = existing.isLoadingPreview
        )
    } else {
        incoming
    }
}

class DraftImageOpenGate {
    data class Lease(val key: String, val token: Long)

    private var nextToken = 1L
    private val activeTokens = mutableMapOf<String, Long>()

    fun tryAcquire(scope: String?, localId: String): Lease? {
        val key = "${scope.orEmpty()}\u0000$localId"
        synchronized(activeTokens) {
            if (activeTokens.containsKey(key)) return null
            val token = nextToken++
            activeTokens[key] = token
            return Lease(key, token)
        }
    }

    fun release(lease: Lease?): Boolean {
        if (lease == null) return false
        synchronized(activeTokens) {
            if (activeTokens[lease.key] == lease.token) {
                activeTokens.remove(lease.key)
                return true
            }
            return false
        }
    }

    fun cancel(scope: String?, localId: String) {
        val key = "${scope.orEmpty()}\u0000$localId"
        synchronized(activeTokens) {
            activeTokens.remove(key)
        }
    }

    fun isOpening(scope: String?, localId: String): Boolean {
        val key = "${scope.orEmpty()}\u0000$localId"
        synchronized(activeTokens) {
            return activeTokens.containsKey(key)
        }
    }

    fun clear() {
        synchronized(activeTokens) {
            activeTokens.clear()
        }
    }
}

fun MutableList<AttachmentItemUiState>.updateImagePreviewLoading(
    localId: String,
    isLoading: Boolean
): ImageAttachmentUiState? {
    val idx = indexOfFirst { it.localId == localId }
    if (idx >= 0) {
        val current = get(idx)
        if (current is ImageAttachmentUiState) {
            val updated = current.copy(isLoadingPreview = isLoading)
            set(idx, updated)
            return updated
        }
    }
    return null
}
