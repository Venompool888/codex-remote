package app.codexremote.android

/** Transient presentation events. Actions retain the original server/task ownership check. */
sealed interface ArtifactDownloadEvent {
    val key: String
    data class Started(override val key: String, val name: String, val cancel: () -> Unit) : ArtifactDownloadEvent
    data class Progress(override val key: String, val percent: Int) : ArtifactDownloadEvent
    data class Completed(override val key: String, val name: String, val open: () -> Unit, val share: () -> Unit) : ArtifactDownloadEvent
    data class Failed(override val key: String, val retry: () -> Unit) : ArtifactDownloadEvent
    data class Dismissed(override val key: String) : ArtifactDownloadEvent
}
