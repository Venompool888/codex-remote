package app.codexremote.android.presentation.workspace

import androidx.compose.runtime.Immutable
import androidx.compose.runtime.State
import androidx.compose.runtime.mutableStateOf
import org.json.JSONObject
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.util.UUID

@Immutable
data class WorkspaceMutationScope(
    val serverId: String,
    val deviceId: String,
    val cwd: String,
    val threadId: String? = null,
)

@Immutable
data class EditableWorkspaceText(val path: String, val text: String, val size: Long, val sha256: String)

@Immutable
data class BackgroundTerminalState(
    val itemId: String,
    val processId: String,
    val command: String,
    val cwdName: String,
    val osPid: Long? = null,
    val cpuPercent: Double? = null,
    val rssKb: Long? = null,
)

enum class WorkspaceMutationKind { SAVE_TEXT, CREATE_TEXT, RESET_MEMORY, TERMINATE_BACKGROUND_TERMINAL, CLEAN_BACKGROUND_TERMINALS }

@Immutable
data class WorkspaceMutationPreview(
    val id: String,
    val kind: WorkspaceMutationKind,
    val title: String,
    val subject: String,
    val consequence: String,
)

@Immutable
data class WorkspaceMutationProgress(
    val id: String,
    val kind: WorkspaceMutationKind,
    val running: Boolean = false,
    val completed: Boolean = false,
    val error: String? = null,
)

@Immutable
data class WorkspaceMutationUiState(
    val scope: WorkspaceMutationScope? = null,
    val supportedMethods: Set<String> = emptySet(),
    val document: EditableWorkspaceText? = null,
    val loadingDocument: Boolean = false,
    val backgroundTerminals: List<BackgroundTerminalState> = emptyList(),
    val terminalsLoading: Boolean = false,
    val terminalsNextCursor: String? = null,
    val preview: WorkspaceMutationPreview? = null,
    val actions: Map<String, WorkspaceMutationProgress> = emptyMap(),
    val error: String? = null,
)

/** Typed mutation state. Creating a preview never sends an RPC. */
class WorkspaceMutationController(
    private val rpc: (serverId: String, method: String, params: JSONObject, done: (JSONObject?, String?) -> Unit) -> Unit,
) {
    private data class Pending(
        val preview: WorkspaceMutationPreview,
        val generation: Long,
        val method: String,
        val params: JSONObject,
    )

    private val state = mutableStateOf(WorkspaceMutationUiState())
    val uiState: State<WorkspaceMutationUiState> = state
    private var generation = 0L
    private var readSequence = 0L
    private var terminalSequence = 0L
    private var pending: Pending? = null

    fun setScope(scope: WorkspaceMutationScope?, supportedMethods: Set<String>) {
        val exact = supportedMethods.intersect(EXACT_METHODS)
        if (state.value.scope == scope && state.value.supportedMethods == exact) return
        generation++
        readSequence++
        terminalSequence++
        pending = null
        state.value = WorkspaceMutationUiState(scope = scope, supportedMethods = exact)
    }

    fun loadText(path: String) {
        val scope = state.value.scope ?: return
        if (!supports(READ_TEXT)) return fail("Workspace text reading is unavailable on this host")
        val normalized = normalizeRelative(path)
        val epoch = generation
        val sequence = ++readSequence
        pending = null
        state.value = state.value.copy(loadingDocument = true, document = null, preview = null, error = null)
        rpc(scope.serverId, READ_TEXT, JSONObject().put("cwd", scope.cwd).put("path", normalized)) { result, error ->
            if (epoch != generation || sequence != readSequence || state.value.scope != scope) return@rpc
            if (error != null || result == null) {
                state.value = state.value.copy(loadingDocument = false, error = error ?: "Workspace text read failed")
                return@rpc
            }
            val text = result.optString("text")
            val responsePath = result.optString("path", normalized).takeIf { it.isNotBlank() && it != "null" } ?: normalized
            state.value = state.value.copy(
                loadingDocument = false,
                document = EditableWorkspaceText(responsePath, text, result.optLong("size"), sha256(text)),
                error = null,
            )
        }
    }

    fun closeText() {
        readSequence++
        if (pending?.preview?.kind == WorkspaceMutationKind.SAVE_TEXT) pending = null
        state.value = state.value.copy(
            document = null,
            loadingDocument = false,
            preview = state.value.preview?.takeUnless { it.kind == WorkspaceMutationKind.SAVE_TEXT },
        )
    }

    fun previewSaveText(text: String) {
        val document = state.value.document ?: throw IllegalStateException("Open a workspace text file first")
        requireText(text)
        val params = JSONObject()
            .put("cwd", state.value.scope?.cwd ?: throw IllegalStateException("Select a workspace"))
            .put("path", document.path)
            .put("text", text)
            .put("expectedSha256", document.sha256)
        previewConfirmed(
            WorkspaceMutationKind.SAVE_TEXT, "Save workspace text", document.path,
            "Replaces this file only if it has not changed since it was opened",
            SAVE_TEXT, params, "save_workspace_text", document.path,
        )
    }

    fun previewCreateText(path: String, text: String) {
        val normalized = normalizeRelative(path)
        requireText(text)
        val params = JSONObject()
            .put("cwd", state.value.scope?.cwd ?: throw IllegalStateException("Select a workspace"))
            .put("path", normalized)
            .put("text", text)
        previewConfirmed(
            WorkspaceMutationKind.CREATE_TEXT, "Create workspace text", normalized,
            "Creates this new file without replacing an existing file",
            CREATE_TEXT, params, "create_workspace_text", normalized,
        )
    }

    fun previewResetMemory() = previewConfirmed(
        WorkspaceMutationKind.RESET_MEMORY, "Reset Codex memory", "Codex memory",
        "Permanently clears host memory used across tasks",
        RESET_MEMORY, JSONObject(), "reset_memory", "Codex memory",
    )

    fun refreshBackgroundTerminals(limit: Int = 50, cursor: String? = null) {
        val scope = state.value.scope ?: return
        val threadId = scope.threadId ?: return fail("Select a task")
        if (!supports(LIST_TERMINALS)) return fail("Background terminal listing is unavailable on this host")
        require(limit in 1..100)
        cursor?.let { require(it.isNotEmpty() && it.length <= 4096 && it.none { char -> char in "\u0000\r\n" }) }
        val epoch = generation
        val sequence = ++terminalSequence
        state.value = state.value.copy(terminalsLoading = true, error = null)
        val params = JSONObject().put("threadId", threadId).put("limit", limit).apply { cursor?.let { put("cursor", it) } }
        rpc(scope.serverId, LIST_TERMINALS, params) { result, error ->
            if (epoch != generation || sequence != terminalSequence || state.value.scope != scope) return@rpc
            if (error != null || result == null) {
                state.value = state.value.copy(terminalsLoading = false, error = error ?: "Background terminal request failed")
                return@rpc
            }
            val data = result.optJSONArray("data")
            val terminals = if (data == null) emptyList() else (0 until data.length()).mapNotNull { index ->
                val item = data.optJSONObject(index) ?: return@mapNotNull null
                val processId = item.optString("processId").takeIf(::validId) ?: return@mapNotNull null
                BackgroundTerminalState(
                    itemId = item.optString("itemId"),
                    processId = processId,
                    command = item.optString("command"),
                    cwdName = item.optString("cwdName"),
                    osPid = item.longOrNull("osPid"),
                    cpuPercent = item.doubleOrNull("cpuPercent"),
                    rssKb = item.longOrNull("rssKb"),
                )
            }
            state.value = state.value.copy(
                terminalsLoading = false,
                backgroundTerminals = terminals,
                terminalsNextCursor = result.stringOrNull("nextCursor"),
                error = null,
            )
        }
    }

    fun previewTerminateBackgroundTerminal(processId: String) {
        val scope = state.value.scope ?: throw IllegalStateException("Select a task")
        val threadId = scope.threadId ?: throw IllegalStateException("Select a task")
        require(state.value.backgroundTerminals.any { it.processId == processId }) { "Unknown background terminal" }
        previewConfirmed(
            WorkspaceMutationKind.TERMINATE_BACKGROUND_TERMINAL, "Stop background terminal", processId,
            "Terminates this background process for the selected task",
            TERMINATE_TERMINAL, JSONObject().put("threadId", threadId).put("processId", processId),
            "terminate_background_terminal", "$threadId:$processId",
        )
    }

    fun previewCleanBackgroundTerminals() {
        val threadId = state.value.scope?.threadId ?: throw IllegalStateException("Select a task")
        previewConfirmed(
            WorkspaceMutationKind.CLEAN_BACKGROUND_TERMINALS, "Clean background terminals", threadId,
            "Removes completed background terminal records for this task",
            CLEAN_TERMINALS, JSONObject().put("threadId", threadId), "clean_background_terminals", threadId,
        )
    }

    fun dismissPreview(id: String) {
        if (pending?.preview?.id != id) return
        pending = null
        state.value = state.value.copy(preview = null)
    }

    fun confirmAction(id: String) {
        val action = pending?.takeIf { it.preview.id == id && it.generation == generation } ?: return
        val scope = state.value.scope ?: return
        if (!supports(action.method)) return actionError(action, "This action is unavailable on this host")
        pending = null
        val progress = WorkspaceMutationProgress(id, action.preview.kind, running = true)
        state.value = state.value.copy(
            preview = null,
            actions = (state.value.actions + (id to progress)).takeLastEntries(MAX_ACTIONS),
            error = null,
        )
        rpc(scope.serverId, action.method, JSONObject(action.params.toString())) { result, error ->
            if (action.generation != generation || state.value.scope != scope) return@rpc
            if (error != null || result == null) return@rpc actionError(action, error ?: "Workspace action failed")
            applyResult(action, result)
            state.value = state.value.copy(actions = state.value.actions + (id to progress.copy(running = false, completed = true)))
        }
    }

    private fun applyResult(action: Pending, result: JSONObject) {
        when (action.preview.kind) {
            WorkspaceMutationKind.SAVE_TEXT, WorkspaceMutationKind.CREATE_TEXT -> {
                val text = action.params.optString("text")
                val path = result.optString("path", action.params.optString("path"))
                state.value = state.value.copy(document = EditableWorkspaceText(
                    path, text, result.optLong("size", text.toByteArray(StandardCharsets.UTF_8).size.toLong()),
                    result.optString("sha256", sha256(text)),
                ))
            }
            WorkspaceMutationKind.TERMINATE_BACKGROUND_TERMINAL -> {
                val processId = action.params.optString("processId")
                state.value = state.value.copy(backgroundTerminals = state.value.backgroundTerminals.filterNot { it.processId == processId })
            }
            WorkspaceMutationKind.CLEAN_BACKGROUND_TERMINALS -> state.value = state.value.copy(backgroundTerminals = emptyList(), terminalsNextCursor = null)
            WorkspaceMutationKind.RESET_MEMORY -> Unit
        }
    }

    private fun previewConfirmed(
        kind: WorkspaceMutationKind,
        title: String,
        subject: String,
        consequence: String,
        method: String,
        params: JSONObject,
        confirmationAction: String,
        confirmationSubject: String,
    ) {
        check(state.value.scope != null) { "Select a workspace" }
        require(method in EXACT_METHODS)
        val item = WorkspaceMutationPreview(UUID.randomUUID().toString(), kind, title, subject, consequence)
        params.put("confirmation", JSONObject().put("action", confirmationAction).put("subject", confirmationSubject))
        pending = Pending(item, generation, method, JSONObject(params.toString()))
        state.value = state.value.copy(preview = item, error = null)
    }

    private fun actionError(action: Pending, message: String) {
        val old = state.value.actions[action.preview.id] ?: WorkspaceMutationProgress(action.preview.id, action.preview.kind)
        state.value = state.value.copy(
            actions = (state.value.actions + (action.preview.id to old.copy(running = false, error = message))).takeLastEntries(MAX_ACTIONS),
            error = message,
        )
    }

    private fun supports(method: String) = method in state.value.supportedMethods
    private fun fail(message: String) { state.value = state.value.copy(error = message) }

    companion object {
        const val READ_TEXT = "host/workspace/file/read"
        const val SAVE_TEXT = "host/workspace/text/save"
        const val CREATE_TEXT = "host/workspace/text/create"
        const val RESET_MEMORY = "host/memory/reset"
        const val LIST_TERMINALS = "host/thread/backgroundTerminals/list"
        const val TERMINATE_TERMINAL = "host/thread/backgroundTerminals/terminate"
        const val CLEAN_TERMINALS = "host/thread/backgroundTerminals/clean"
        val EXACT_METHODS = setOf(READ_TEXT, SAVE_TEXT, CREATE_TEXT, RESET_MEMORY, LIST_TERMINALS, TERMINATE_TERMINAL, CLEAN_TERMINALS)
        private const val MAX_TEXT_BYTES = 512 * 1024
        private const val MAX_ACTIONS = 30
    }
}

private fun normalizeRelative(value: String): String {
    require(value.isNotBlank() && value.length <= 4096 && !value.startsWith('/') && value.none { it in "\u0000\r\n" })
    val parts = mutableListOf<String>()
    value.split('/').forEach { part ->
        when (part) {
            "", "." -> Unit
            ".." -> {
                require(parts.isNotEmpty()) { "Workspace path escapes the selected project" }
                parts.removeAt(parts.lastIndex)
            }
            else -> parts += part
        }
    }
    require(parts.isNotEmpty())
    return parts.joinToString("/")
}

private fun requireText(value: String) {
    require(!value.contains('\u0000') && value.toByteArray(StandardCharsets.UTF_8).size <= 512 * 1024) { "Workspace text exceeds 512 KiB" }
}

private fun sha256(value: String): String = MessageDigest.getInstance("SHA-256")
    .digest(value.toByteArray(StandardCharsets.UTF_8)).joinToString("") { (it.toInt() and 0xff).toString(16).padStart(2, '0') }

private fun validId(value: String): Boolean = value.length in 1..256 && value.all { it.isLetterOrDigit() || it in "._:-" }
private fun JSONObject.stringOrNull(key: String): String? = optString(key).takeIf { has(key) && !isNull(key) && it.isNotBlank() && it != "null" }
private fun JSONObject.longOrNull(key: String): Long? = optLong(key).takeIf { has(key) && !isNull(key) }
private fun JSONObject.doubleOrNull(key: String): Double? = optDouble(key, Double.NaN).takeIf { has(key) && !isNull(key) && it.isFinite() }
private fun <K, V> Map<K, V>.takeLastEntries(limit: Int): Map<K, V> = entries.toList().takeLast(limit).associate { it.toPair() }
