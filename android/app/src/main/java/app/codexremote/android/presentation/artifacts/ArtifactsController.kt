package app.codexremote.android.presentation.artifacts

import android.graphics.Bitmap
import androidx.compose.runtime.State
import androidx.compose.runtime.mutableStateOf
import app.codexremote.android.ArtifactDownloadEvent

class ArtifactsController(
    private val onFetchArtifacts: (String, (List<ArtifactItem>, String?) -> Unit) -> Unit = { _, _ -> },
    private val onCopyContent: (String) -> Unit = {},
    private val onDownloadArtifact: (ArtifactItem) -> Unit = {}
) {
    private val _uiState = mutableStateOf(ArtifactsUiState())
    val uiState: State<ArtifactsUiState> = _uiState

    private var activeScope: String? = null
    private var fetchGeneration = 0L
    private var downloadCancelAction: (() -> Unit)? = null

    fun openArtifacts(threadId: String, scope: String? = null) {
        val currentScope = scope ?: threadId
        activeScope = currentScope
        val generation = ++fetchGeneration
        _uiState.value = _uiState.value.copy(
            isOpen = true,
            currentScope = currentScope,
            isLoading = true,
            errorMessage = null,
            selectedArtifact = null,
            artifacts = emptyList()
        )
        onFetchArtifacts(threadId) { list, error ->
            if (activeScope == currentScope && fetchGeneration == generation) {
                _uiState.value = _uiState.value.copy(
                    artifacts = list,
                    isLoading = false,
                    errorMessage = error
                )
            }
        }
    }

    fun closeArtifacts() {
        activeScope = null
        fetchGeneration++
        _uiState.value = _uiState.value.copy(isOpen = false, selectedArtifact = null, currentScope = null)
    }

    fun selectArtifact(item: ArtifactItem?) {
        _uiState.value = _uiState.value.copy(selectedArtifact = item)
    }

    fun copyContent(artifact: ArtifactItem) {
        onCopyContent(artifact.previewText)
    }

    fun downloadArtifact(artifact: ArtifactItem) {
        onDownloadArtifact(artifact)
    }

    fun openImageViewer(
        bitmap: Bitmap,
        description: String,
        scope: String? = null,
        canShare: Boolean = false,
        canSave: Boolean = false,
        onShare: (() -> Unit)? = null,
        onSave: (() -> Unit)? = null
    ) {
        _uiState.value = _uiState.value.copy(
            isViewerOpen = true,
            viewerImage = bitmap,
            viewerDescription = description,
            viewerScope = scope,
            canShare = canShare && onShare != null,
            canSave = canSave && onSave != null,
            onShare = onShare,
            onSave = onSave
        )
    }

    fun closeImageViewer() {
        _uiState.value = _uiState.value.copy(
            isViewerOpen = false,
            viewerImage = null,
            viewerDescription = "",
            viewerScope = null,
            canShare = false,
            canSave = false,
            onShare = null,
            onSave = null
        )
    }

    fun shareImage() {
        _uiState.value.onShare?.invoke()
    }

    fun saveImage() {
        _uiState.value.onSave?.invoke()
    }

    fun onDownloadEvent(event: ArtifactDownloadEvent) {
        when (event) {
            is ArtifactDownloadEvent.Started -> {
                downloadCancelAction = event.cancel
                _uiState.value = _uiState.value.copy(
                    downloadProgress = DownloadProgressState(
                        key = event.key,
                        name = event.name,
                        percent = 0
                    )
                )
            }
            is ArtifactDownloadEvent.Progress -> {
                val current = _uiState.value.downloadProgress
                if (current != null && current.key == event.key) {
                    _uiState.value = _uiState.value.copy(
                        downloadProgress = current.copy(percent = event.percent)
                    )
                }
            }
            is ArtifactDownloadEvent.Completed -> {
                val current = _uiState.value.downloadProgress
                if (current != null && current.key == event.key) {
                    downloadCancelAction = null
                    _uiState.value = _uiState.value.copy(
                        downloadProgress = current.copy(
                            name = event.name,
                            isCompleted = true,
                            open = event.open,
                            share = event.share
                        )
                    )
                }
            }
            is ArtifactDownloadEvent.Failed -> {
                val current = _uiState.value.downloadProgress
                if (current != null && current.key == event.key) {
                    downloadCancelAction = null
                    _uiState.value = _uiState.value.copy(
                        downloadProgress = current.copy(
                            isFailed = true,
                            retry = event.retry
                        )
                    )
                }
            }
            is ArtifactDownloadEvent.Dismissed -> {
                val current = _uiState.value.downloadProgress
                if (current != null && current.key == event.key) {
                    downloadCancelAction = null
                    _uiState.value = _uiState.value.copy(downloadProgress = null)
                }
            }
        }
    }

    fun cancelDownload(key: String) {
        val current = _uiState.value.downloadProgress
        if (current != null && current.key == key) {
            downloadCancelAction?.invoke()
            downloadCancelAction = null
            _uiState.value = _uiState.value.copy(downloadProgress = null)
        }
    }

    fun dismissDownload(key: String) {
        val current = _uiState.value.downloadProgress
        if (current != null && current.key == key) {
            downloadCancelAction = null
            _uiState.value = _uiState.value.copy(downloadProgress = null)
        }
    }
}
