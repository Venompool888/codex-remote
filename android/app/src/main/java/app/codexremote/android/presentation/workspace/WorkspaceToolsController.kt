package app.codexremote.android.presentation.workspace

import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.State
import org.json.JSONObject

data class WorkspaceToolsScope(val serverId: String, val deviceId: String, val cwd: String)
data class WorkspaceFile(val path: String, val name: String, val score: Double?)
data class WorkspaceDocument(val path: String, val text: String, val size: Long)
data class WorkspaceToolsUiState(
    val scope: WorkspaceToolsScope? = null,
    val canSearch: Boolean = false,
    val canRead: Boolean = false,
    val canDiff: Boolean = false,
    val query: String = "",
    val files: List<WorkspaceFile> = emptyList(),
    val searching: Boolean = false,
    val document: WorkspaceDocument? = null,
    val reading: Boolean = false,
    val diff: String? = null,
    val diffSha: String? = null,
    val loadingDiff: Boolean = false,
    val error: String? = null,
)

/** Workspace reads share the same server/device generation boundary as task requests. */
class WorkspaceToolsController(
    private val rpc: (String, JSONObject, (JSONObject?, String?) -> Unit) -> Unit,
    private val onReference: (String) -> Unit = {},
) {
    private val state = mutableStateOf(WorkspaceToolsUiState())
    val uiState: State<WorkspaceToolsUiState> = state
    private var generation = 0L
    private val sequences = mutableMapOf<String, Long>()
    fun setScope(scope: WorkspaceToolsScope?, methods: Set<String>) {
        val old = state.value
        val search = scope != null && "host/workspace/files/search" in methods
        val read = scope != null && "host/workspace/file/read" in methods
        val diff = scope != null && "host/git/diff" in methods
        if (scope == old.scope && search == old.canSearch && read == old.canRead && diff == old.canDiff) return
        generation++
        sequences.clear()
        state.value = WorkspaceToolsUiState(scope, search, read, diff)
    }
    fun reset() = setScope(null, emptySet())
    fun search(query: String) {
        val scope = state.value.scope ?: return
        val value = query.trim()
        val current = begin("search")
        state.value = state.value.copy(query = query, files = emptyList(), searching = false, error = null)
        if (value.isEmpty()) return
        if (!state.value.canSearch) return fail("File search is unavailable on this host")
        state.value = state.value.copy(searching = true)
        rpc("host/workspace/files/search", JSONObject().put("cwd", scope.cwd).put("query", value)) { result, error ->
            if (!current()) return@rpc
            val items = result?.optJSONArray("files")
            val files = if (items == null) emptyList() else (0 until items.length()).mapNotNull { index ->
                val item = items.optJSONObject(index) ?: return@mapNotNull null
                val path = item.optString("path").takeIf { it.isNotBlank() && it != "null" } ?: return@mapNotNull null
                WorkspaceFile(path, item.optString("fileName").takeUnless { it.isBlank() || it == "null" } ?: path.substringAfterLast('/'),
                    item.optDouble("score", Double.NaN).takeIf { it.isFinite() })
            }
            state.value = state.value.copy(files = files, searching = false, error = error ?: if (result == null) "File search failed" else null)
        }
    }
    fun openFile(path: String) {
        val scope = state.value.scope ?: return
        if (!state.value.canRead) return fail("File reading is unavailable on this host")
        val current = begin("file")
        state.value = state.value.copy(reading = true, document = null, error = null)
        rpc("host/workspace/file/read", JSONObject().put("cwd", scope.cwd).put("path", path)) { result, error ->
            if (!current()) return@rpc
            state.value = state.value.copy(reading = false,
                document = result?.let { WorkspaceDocument(it.optString("path", path), it.optString("text"), it.optLong("size")) },
                error = error ?: if (result == null) "File read failed" else null)
        }
    }
    fun closeFile() { begin("file"); state.value = state.value.copy(document = null, reading = false) }
    fun referenceFile(path: String) {
        if (state.value.files.none { it.path == path } && state.value.document?.path != path) return
        onReference(path)
    }
    fun refreshDiff() {
        val scope = state.value.scope ?: return
        if (!state.value.canDiff) return fail("Git diff is unavailable on this host")
        val current = begin("diff")
        state.value = state.value.copy(loadingDiff = true, error = null)
        rpc("host/git/diff", JSONObject().put("cwd", scope.cwd)) { result, error ->
            if (!current()) return@rpc
            state.value = state.value.copy(loadingDiff = false, diff = result?.optString("diff"),
                diffSha = result?.optString("sha")?.takeUnless { it == "null" || it.isBlank() },
                error = error ?: if (result == null) "Git diff failed" else null)
        }
    }
    private fun fail(message: String) { state.value = state.value.copy(error = message) }
    private fun begin(key: String): () -> Boolean {
        val epoch = generation
        val sequence = (sequences[key] ?: 0) + 1
        sequences[key] = sequence
        return { epoch == generation && sequences[key] == sequence }
    }
}
