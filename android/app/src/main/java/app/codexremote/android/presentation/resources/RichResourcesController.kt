package app.codexremote.android.presentation.resources

import androidx.compose.runtime.State
import androidx.compose.runtime.mutableStateOf
import org.json.JSONObject

data class RichResourceScope(val serverId: String, val deviceId: String, val threadId: String)
data class ResourceContent(val uri: String, val mimeType: String, val kind: String, val text: String?, val dataBase64: String?, val truncated: Boolean)
data class ReferencedFile(val path: String, val text: String, val startLine: Int?, val endLine: Int?)
data class GuardianDenial(val reviewId: String, val threadId: String, val rationale: String, val actionSummary: String, val riskLevel: String, val approvable: Boolean)
data class RichResourcesUiState(
    val scope: RichResourceScope? = null,
    val canReadResource: Boolean = false,
    val canReadFile: Boolean = false,
    val canApproveDenial: Boolean = false,
    val contents: List<ResourceContent> = emptyList(),
    val file: ReferencedFile? = null,
    val denials: List<GuardianDenial> = emptyList(),
    val pendingApproval: GuardianDenial? = null,
    val loading: Boolean = false,
    val approving: Boolean = false,
    val error: String? = null,
)

/** Explicit read/approval actions, never executes instructions embedded in tool content. */
class RichResourcesController(private val rpc: (String, JSONObject, (JSONObject?, String?) -> Unit) -> Unit) {
    private val state = mutableStateOf(RichResourcesUiState())
    val uiState: State<RichResourcesUiState> = state
    private var generation = 0L
    private var readSequence = 0L
    fun setScope(scope: RichResourceScope?, methods: Set<String>) {
        val old = state.value
        val resource = scope != null && "host/mcp/resource/read" in methods
        val file = scope != null && "host/file/readReference" in methods
        val guardian = scope != null && "host/guardian/approveDenied" in methods
        if (old.scope == scope && old.canReadResource == resource && old.canReadFile == file && old.canApproveDenial == guardian) return
        generation++
        readSequence++
        state.value = RichResourcesUiState(scope, resource, file, guardian)
    }
    fun reset() = setScope(null, emptySet())
    fun readResource(server: String, uri: String) {
        val scope = state.value.scope ?: return
        if (!state.value.canReadResource) return fail("This host cannot read MCP resources")
        val current = beginRead()
        rpc("host/mcp/resource/read", JSONObject().put("threadId", scope.threadId).put("server", server).put("uri", uri)) { result, error ->
            if (!current()) return@rpc
            val content = result?.optJSONArray("contents")
            val items = if (content == null) emptyList() else (0 until content.length()).mapNotNull { index ->
                val item = content.optJSONObject(index) ?: return@mapNotNull null
                ResourceContent(item.optString("uri"), item.optString("mimeType"), item.optString("kind"),
                    item.opt("text") as? String, item.opt("dataBase64") as? String, item.optBoolean("truncated"))
            }
            state.value = state.value.copy(loading = false, contents = items, error = error ?: if (result == null) "Resource unavailable" else null)
        }
    }
    fun readFile(reference: String) {
        val scope = state.value.scope ?: return
        if (!state.value.canReadFile) return fail("This host cannot open precise file references")
        val current = beginRead()
        rpc("host/file/readReference", JSONObject().put("threadId", scope.threadId).put("reference", reference)) { result, error ->
            if (!current()) return@rpc
            state.value = state.value.copy(loading = false,
                file = result?.let { ReferencedFile(it.optString("path"), it.optString("text"),
                    it.optInt("startLine").takeIf { line -> line > 0 }, it.optInt("endLine").takeIf { line -> line > 0 }) },
                error = error ?: if (result == null) "Referenced file unavailable" else null)
        }
    }
    fun dismissContent() { readSequence++; state.value = state.value.copy(contents = emptyList(), file = null, loading = false, error = null) }
    fun consumeEvent(params: JSONObject) {
        val scope = state.value.scope ?: return
        val denial = params.optJSONObject("guardianDenied") ?: return
        if (denial.optString("threadId") != scope.threadId) return
        val id = denial.optString("reviewId").takeIf { it.isNotBlank() && it != "null" } ?: return
        val item = GuardianDenial(id, scope.threadId, denial.optString("rationale"), denial.optString("actionSummary"),
            denial.optString("riskLevel"), denial.optBoolean("approvable"))
        state.value = state.value.copy(denials = (state.value.denials.filterNot { it.reviewId == id } + item).takeLast(50))
    }
    fun previewApproval(reviewId: String) {
        if (!state.value.canApproveDenial || state.value.approving) return
        val item = state.value.denials.firstOrNull { it.reviewId == reviewId && it.approvable } ?: return
        state.value = state.value.copy(pendingApproval = item, error = null)
    }
    fun dismissApproval() { if (!state.value.approving) state.value = state.value.copy(pendingApproval = null) }
    fun confirmApproval() {
        val scope = state.value.scope ?: return
        val item = state.value.pendingApproval ?: return
        if (!state.value.canApproveDenial || !item.approvable || state.value.approving) return
        val epoch = generation
        state.value = state.value.copy(approving = true, error = null)
        rpc("host/guardian/approveDenied", JSONObject().put("threadId", scope.threadId).put("reviewId", item.reviewId)
            .put("confirmation", JSONObject().put("action", "approve_guardian_denied").put("subject", item.reviewId))) { result, error ->
            if (epoch != generation) return@rpc
            state.value = state.value.copy(approving = false, error = error ?: if (result == null) "Approval was not confirmed" else null,
                pendingApproval = if (result != null && error == null) null else item,
                denials = if (result != null && error == null) state.value.denials.filterNot { it.reviewId == item.reviewId } else state.value.denials)
        }
    }
    private fun beginRead(): () -> Boolean {
        val epoch = generation
        val sequence = ++readSequence
        state.value = state.value.copy(loading = true, contents = emptyList(), file = null, error = null)
        return { generation == epoch && readSequence == sequence }
    }
    private fun fail(message: String) { state.value = state.value.copy(error = message) }
}
