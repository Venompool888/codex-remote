package app.codexremote.android.ui.projects

data class ProjectHost(val serverUrl: String, val name: String)
data class ProjectSubmission(
    val name: String,
    val serverUrl: String,
    val connectionName: String,
    val pairingCode: String,
    val workspace: String,
)
data class ProjectFolder(val name: String, val path: String)
