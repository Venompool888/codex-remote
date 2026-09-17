package app.codexremote.android.presentation.artifacts

import android.graphics.Bitmap

data class ArtifactItem(
    val id: String,
    val name: String,
    val path: String,
    val sizeBytes: Long = 0L,
    val mimeType: String = "",
    val previewText: String = "",
    val isImage: Boolean = false
)

data class DownloadProgressState(
    val key: String,
    val name: String,
    val percent: Int = 0,
    val isCompleted: Boolean = false,
    val isFailed: Boolean = false,
    val open: (() -> Unit)? = null,
    val share: (() -> Unit)? = null,
    val retry: (() -> Unit)? = null
)

data class ArtifactsUiState(
    val isOpen: Boolean = false,
    val currentScope: String? = null,
    val artifacts: List<ArtifactItem> = emptyList(),
    val selectedArtifact: ArtifactItem? = null,
    val isLoading: Boolean = false,
    val errorMessage: String? = null,
    // Active download progress
    val downloadProgress: DownloadProgressState? = null,
    // Image viewer state
    val viewerImage: Bitmap? = null,
    val viewerDescription: String = "",
    val isViewerOpen: Boolean = false,
    val viewerScope: String? = null,
    val canShare: Boolean = false,
    val canSave: Boolean = false,
    val onShare: (() -> Unit)? = null,
    val onSave: (() -> Unit)? = null
)
