package app.codexremote.android.presentation.tasktools

import androidx.compose.runtime.State
import androidx.compose.runtime.mutableStateOf
import org.json.JSONArray
import org.json.JSONObject

/**
 * Typed presentation boundary for task tools. The runtime supplies an RPC bound to the
 * current server/device; [setScope] and request generations prevent cross-scope results.
 * Confirmation dialogs belong to the UI: destructive methods execute when invoked.
 */
class TaskToolsController(
    private val rpc: (method: String, params: JSONObject, callback: (JSONObject?, String?) -> Unit) -> Unit,
    private val onTaskSelected: (String) -> Unit = {},
    private val onTaskChanged: (String) -> Unit = {},
    private val onTaskDeleted: (String) -> Unit = {},
    private val onInspectDescendant: (String) -> Unit = {},
) {
    private val _uiState = mutableStateOf(TaskToolsUiState())
    val uiState: State<TaskToolsUiState> = _uiState

    private var generation = 0L
    private var requestSequence = 0L
    private val activeRequests = mutableMapOf<String, RequestToken>()
    private var advertisedMethods: Set<String> = emptySet()
    private var retryAction: (() -> Unit)? = null

    fun setScope(scope: TaskToolsScope?, advertisedRpcMethods: Set<String>) {
        val previous = _uiState.value.scope
        val sameIdentity = previous?.serverId == scope?.serverId && previous?.deviceId == scope?.deviceId && previous?.taskId == scope?.taskId
        if (sameIdentity && advertisedMethods == advertisedRpcMethods) {
            if (previous != scope) _uiState.value = _uiState.value.copy(scope = scope, availability = availability(scope))
            return
        }
        generation++
        activeRequests.clear()
        advertisedMethods = advertisedRpcMethods.toSet()
        _uiState.value = TaskToolsUiState(scope = scope, availability = availability(scope))
        retryAction = null
    }

    fun reset() = setScope(null, emptySet())

    fun refreshAll() {
        if (_uiState.value.scope == null) return
        refreshGoal()
        refreshHistory()
        refreshDescendants()
        refreshAccountUsage()
        val query = _uiState.value.search.query
        if (query.isNotBlank()) search(query)
    }

    fun retry() { retryAction?.invoke() }

    fun clearActionStatus() {
        _uiState.value = _uiState.value.copy(action = TaskActionStatus())
    }

    /** Applies runtime notifications already routed for this controller's server/device. */
    fun consumeEvent(method: String, params: JSONObject): Boolean {
        val scope = _uiState.value.scope ?: return false
        return when (method) {
            "thread/goal/updated" -> {
                if (params.string("threadId") != scope.taskId) false else {
                    val goal = params.optJSONObject("goal")?.let(::parseGoal) ?: return false
                    activeRequests.remove("goal")
                    _uiState.value = _uiState.value.copy(goal = GoalState(goal))
                    true
                }
            }
            "thread/goal/cleared" -> {
                if (params.string("threadId") != scope.taskId) false else {
                    activeRequests.remove("goal")
                    _uiState.value = _uiState.value.copy(goal = GoalState())
                    true
                }
            }
            "account/rateLimits/updated" -> {
                val update = params.optJSONObject("rateLimits") ?: return false
                activeRequests.remove("account")
                val incoming = parseRateLimit(update, update.string("limitId"))
                val existing = _uiState.value.account.rateLimits
                val matchIndex = existing.indexOfFirst { current ->
                    incoming.id != null && current.id == incoming.id || incoming.id == null && existing.size == 1
                }
                val merged = if (matchIndex < 0) existing + incoming else existing.toMutableList().apply {
                    this[matchIndex] = mergeRateLimit(this[matchIndex], incoming)
                }
                _uiState.value = _uiState.value.copy(account = _uiState.value.account.copy(
                    rateLimits = merged,
                    loading = false,
                ))
                true
            }
            else -> false
        }
    }

    fun renameTask(name: String) {
        val trimmed = name.trim()
        if (trimmed.isEmpty()) return actionFailure(TaskAction.RENAME, "Enter a task name.")
        mutate(TaskAction.RENAME, "thread/name/set", JSONObject().put("name", trimmed), "Task renamed") {
            val scope = _uiState.value.scope ?: return@mutate
            _uiState.value = _uiState.value.copy(scope = scope.copy(taskName = trimmed))
            onTaskChanged(scope.taskId)
        }
    }

    fun forkTask() = mutate(TaskAction.FORK, "thread/fork", JSONObject(), "Task forked") { result ->
        result.optJSONObject("thread")?.string("id")?.let {
            onTaskChanged(it)
            onTaskSelected(it)
        }
    }

    fun archiveTask() = mutate(TaskAction.ARCHIVE, "thread/archive", JSONObject(), "Task archived") {
        updateArchived(true)
    }

    fun unarchiveTask() = mutate(TaskAction.UNARCHIVE, "thread/unarchive", JSONObject(), "Task restored") {
        updateArchived(false)
    }

    fun deleteTask() = mutate(TaskAction.DELETE, "thread/delete", JSONObject(), "Task deleted") {
        _uiState.value.scope?.taskId?.let(onTaskDeleted)
    }

    fun compactTask() = mutate(TaskAction.COMPACT, "thread/compact/start", JSONObject(), "Context compaction started")

    fun startReview(target: ReviewTarget, delivery: ReviewDelivery) {
        val targetJson = when (target) {
            ReviewTarget.UncommittedChanges -> JSONObject().put("type", "uncommittedChanges")
            is ReviewTarget.BaseBranch -> JSONObject().put("type", "baseBranch").put("branch", target.branch)
            is ReviewTarget.Commit -> JSONObject().put("type", "commit").put("sha", target.sha).put("title", target.title ?: JSONObject.NULL)
            is ReviewTarget.Custom -> JSONObject().put("type", "custom").put("instructions", target.instructions)
        }
        mutate(TaskAction.START_REVIEW, "review/start", JSONObject().put("target", targetJson).put("delivery", delivery.wireValue), "Review started") { result ->
            val reviewThreadId = result.string("reviewThreadId")
            _uiState.value = _uiState.value.copy(review = ReviewState(reviewThreadId, delivery))
            if (delivery == ReviewDelivery.DETACHED && reviewThreadId != null) onTaskChanged(reviewThreadId)
        }
    }

    fun updateSearchQuery(query: String) {
        if (query == _uiState.value.search.query) return
        activeRequests.remove("search")
        _uiState.value = _uiState.value.copy(search = TaskSearchState(query = query))
        if (query.isNotBlank()) search(query)
    }

    fun search(query: String = _uiState.value.search.query) = requestSearch(query.trim(), append = false)
    fun loadMoreSearch() = requestSearch(_uiState.value.search.query, append = true)

    private fun requestSearch(query: String, append: Boolean) {
        val scope = _uiState.value.scope ?: return
        if (query.isBlank()) {
            _uiState.value = _uiState.value.copy(search = TaskSearchState())
            return
        }
        val canThreads = supports("thread/search")
        val canOccurrences = supports("thread/searchOccurrences")
        if (!canThreads && !canOccurrences) return searchFailure(query, "Search is unavailable on this host.")
        val old = _uiState.value.search.takeIf { it.query == query } ?: TaskSearchState(query = query)
        if (append && old.nextCursor == null && old.nextOccurrenceCursor == null) return
        val request = token("search")
        _uiState.value = _uiState.value.copy(search = old.copy(
            loading = !append, loadingMore = append, error = null,
            matches = if (append) old.matches else emptyList(),
            occurrences = if (append) old.occurrences else emptyList(),
        ))
        var waiting = (if (canThreads && (!append || old.nextCursor != null)) 1 else 0) +
            (if (canOccurrences && (!append || old.nextOccurrenceCursor != null)) 1 else 0)
        var matches = if (append) old.matches else emptyList()
        var occurrences = if (append) old.occurrences else emptyList()
        var next = if (append) old.nextCursor else null
        var nextOccurrence = if (append) old.nextOccurrenceCursor else null
        val errors = mutableListOf<String>()
        fun finish() {
            waiting--
            if (waiting > 0 || !current(request)) return
            _uiState.value = _uiState.value.copy(search = TaskSearchState(
                query, matches.distinctBy { it.threadId }, occurrences.distinctBy { it.itemId },
                next, nextOccurrence, error = errors.takeIf { it.isNotEmpty() }?.joinToString("\n"),
            ))
            if (errors.isNotEmpty()) retryAction = { requestSearch(query, append) }
        }
        if (waiting == 0) return
        if (canThreads && (!append || old.nextCursor != null)) {
            val params = JSONObject().put("searchTerm", query).put("limit", PAGE_SIZE)
            if (append) params.put("cursor", old.nextCursor)
            rpc("thread/search", params) { result, error ->
                if (!current(request)) return@rpc
                if (error != null || result == null) errors += error ?: "Task search failed."
                else {
                    matches += result.array("data").objects().mapNotNull(::parseSearchMatch)
                    next = result.string("nextCursor")
                }
                finish()
            }
        }
        if (canOccurrences && (!append || old.nextOccurrenceCursor != null)) {
            val params = JSONObject().put("threadId", scope.taskId).put("searchTerm", query).put("limit", PAGE_SIZE)
            if (append) params.put("cursor", old.nextOccurrenceCursor)
            rpc("thread/searchOccurrences", params) { result, error ->
                if (!current(request)) return@rpc
                if (error != null || result == null) errors += error ?: "Message search failed."
                else {
                    occurrences += result.array("data").objects().mapNotNull(::parseOccurrence)
                    nextOccurrence = result.string("nextCursor")
                }
                finish()
            }
        }
    }

    fun refreshAccountUsage() {
        if (_uiState.value.scope == null || !supports("host/account/usage")) return accountFailure("Account usage is unavailable on this host.")
        val request = token("account")
        _uiState.value = _uiState.value.copy(account = AccountUsageState(loading = true))
        rpc("host/account/usage", JSONObject()) { result, error ->
            if (!current(request)) return@rpc
            if (error != null || result == null) return@rpc accountFailure(error ?: "Account usage request failed.")
            val rateSection = result.optJSONObject("rateLimits")
            val tokenSection = result.optJSONObject("tokenUsage")
            val errors = buildList {
                if (rateSection?.optBoolean("supported") != true) add(rateSection?.string("error") ?: "Rate limits are unavailable.")
                if (tokenSection?.optBoolean("supported") != true) add(tokenSection?.string("error") ?: "Token usage is unavailable.")
            }
            _uiState.value = _uiState.value.copy(account = AccountUsageState(
                rateLimits = rateSection?.optJSONObject("data")?.let(::parseRateLimits).orEmpty(),
                usage = tokenSection?.optJSONObject("data")?.let(::parseUsage),
                availableResetCredits = rateSection?.optJSONObject("data")?.optJSONObject("rateLimitResetCredits")?.longOrNull("availableCount"),
                resetCredits = rateSection?.optJSONObject("data")?.optJSONObject("rateLimitResetCredits")?.let(::parseResetCredits),
                error = errors.takeIf { it.isNotEmpty() }?.joinToString("\n"),
            ))
            if (errors.isNotEmpty()) retryAction = ::refreshAccountUsage
        }
    }

    fun refreshGoal() {
        val scope = _uiState.value.scope ?: return
        if (!supports("thread/goal/get")) return goalFailure("Goals are unavailable on this host.")
        val request = token("goal")
        _uiState.value = _uiState.value.copy(goal = _uiState.value.goal.copy(loading = true, error = null))
        rpc("thread/goal/get", JSONObject().put("threadId", scope.taskId)) { result, error ->
            if (!current(request)) return@rpc
            if (error != null || result == null) goalFailure(error ?: "Could not load the goal.")
            else _uiState.value = _uiState.value.copy(goal = GoalState(result.optJSONObject("goal")?.let(::parseGoal)))
        }
    }

    fun setGoal(objective: String, status: GoalStatus, tokenBudget: Long?) {
        if (objective.isBlank()) return actionFailure(TaskAction.SET_GOAL, "Enter a goal objective.")
        val values = JSONObject().put("objective", objective.trim()).put("status", status.wireValue)
            .put("tokenBudget", tokenBudget ?: JSONObject.NULL)
        mutate(TaskAction.SET_GOAL, "thread/goal/set", values, "Goal updated") { result ->
            _uiState.value = _uiState.value.copy(goal = GoalState(result.optJSONObject("goal")?.let(::parseGoal)))
        }
    }

    fun clearGoal() = mutate(TaskAction.CLEAR_GOAL, "thread/goal/clear", JSONObject(), "Goal cleared") {
        _uiState.value = _uiState.value.copy(goal = GoalState())
    }

    fun refreshHistory() = requestHistory(append = false)
    fun loadMoreHistory() = requestHistory(append = true)

    private fun requestHistory(append: Boolean) {
        val scope = _uiState.value.scope ?: return
        val method = when {
            supports("thread/turns/list") -> "thread/turns/list"
            supports("thread/items/list") -> "thread/items/list"
            else -> return historyFailure("Paged history is unavailable on this host.")
        }
        val old = _uiState.value.history
        if (append && old.nextCursor == null) return
        val request = token("history")
        _uiState.value = _uiState.value.copy(history = old.copy(
            entries = if (append) old.entries else emptyList(), loading = !append, loadingMore = append, error = null,
        ))
        val params = JSONObject().put("threadId", scope.taskId).put("limit", PAGE_SIZE)
        if (append) params.put("cursor", old.nextCursor)
        if (method == "thread/turns/list") params.put("sortDirection", "desc").put("itemsView", "summary")
        rpc(method, params) { result, error ->
            if (!current(request)) return@rpc
            if (error != null || result == null) return@rpc historyFailure(error ?: "Could not load task history.")
            val fresh = result.array("data").objects().flatMapIndexed { index, item -> parseHistory(method, item, index) }
            val entries = (if (append) old.entries + fresh else fresh).distinctBy { it.id }
            _uiState.value = _uiState.value.copy(history = HistoryState(entries, result.string("nextCursor")))
        }
    }

    fun refreshDescendants() = requestDescendants(append = false)
    fun loadMoreDescendants() = requestDescendants(append = true)

    private fun requestDescendants(append: Boolean) {
        val scope = _uiState.value.scope ?: return
        if (!supports("thread/list")) return descendantsFailure("Descendant discovery is unavailable on this host.")
        val old = _uiState.value.descendants
        if (append && old.nextCursor == null) return
        val request = token("descendants")
        _uiState.value = _uiState.value.copy(descendants = old.copy(
            tasks = if (append) old.tasks else emptyList(), loading = !append, loadingMore = append, error = null,
        ))
        val params = JSONObject().put("ancestorThreadId", scope.taskId).put("limit", PAGE_SIZE)
        if (append) params.put("cursor", old.nextCursor)
        rpc("thread/list", params) { result, error ->
            if (!current(request)) return@rpc
            if (error != null || result == null) return@rpc descendantsFailure(error ?: "Could not discover descendant tasks.")
            val fresh = result.array("data").objects().mapNotNull(::parseDescendant)
            _uiState.value = _uiState.value.copy(descendants = DescendantsState(
                tasks = (if (append) old.tasks + fresh else fresh).distinctBy { it.id },
                selectedTaskId = old.selectedTaskId,
                nextCursor = result.string("nextCursor"),
            ))
        }
    }

    fun selectDescendant(threadId: String) {
        if (_uiState.value.descendants.tasks.none { it.id == threadId }) return
        _uiState.value = _uiState.value.copy(descendants = _uiState.value.descendants.copy(selectedTaskId = threadId))
        onInspectDescendant(threadId)
    }

    private fun mutate(
        action: TaskAction,
        method: String,
        values: JSONObject,
        successMessage: String,
        success: (JSONObject) -> Unit = {},
    ) {
        val scope = _uiState.value.scope ?: return actionFailure(action, "No task is selected.")
        if (!supports(method)) return actionFailure(action, "This action is unavailable on this host.")
        if (_uiState.value.action.running != null) return
        val request = token("action")
        _uiState.value = _uiState.value.copy(action = TaskActionStatus(running = action))
        val params = JSONObject(values.toString()).put("threadId", scope.taskId)
        rpc(method, params) { result, error ->
            if (!current(request)) return@rpc
            if (error != null || result == null) {
                actionFailure(action, error ?: "The action failed.")
                retryAction = { mutate(action, method, values, successMessage, success) }
            } else {
                _uiState.value = _uiState.value.copy(action = TaskActionStatus(completed = action, message = successMessage))
                retryAction = null
                success(result)
            }
        }
    }

    private fun updateArchived(archived: Boolean) {
        val scope = _uiState.value.scope ?: return
        val updatedScope = scope.copy(archived = archived)
        _uiState.value = _uiState.value.copy(scope = updatedScope, availability = availability(updatedScope))
        onTaskChanged(scope.taskId)
    }

    private fun availability(scope: TaskToolsScope?): TaskToolsAvailability {
        val hasScope = scope != null
        fun read(vararg methods: String) = FeatureAvailability(readable = hasScope && methods.any(::supports), unavailableReason = reason(hasScope, methods.any(::supports)))
        fun write(vararg methods: String) = FeatureAvailability(writable = hasScope && methods.any(::supports), unavailableReason = reason(hasScope, methods.any(::supports)))
        val archived = scope?.archived == true
        return TaskToolsAvailability(
            rename = write("thread/name/set"), fork = write("thread/fork"),
            archive = write(if (archived) "thread/unarchive" else "thread/archive"), delete = write("thread/delete"),
            search = read("thread/search", "thread/searchOccurrences"),
            accountUsage = read("host/account/usage"), compact = write("thread/compact/start"),
            review = write("review/start"),
            goal = FeatureAvailability(hasScope && supports("thread/goal/get"), hasScope && (supports("thread/goal/set") || supports("thread/goal/clear")), reason(hasScope, supports("thread/goal/get") || supports("thread/goal/set") || supports("thread/goal/clear"))),
            history = read("thread/turns/list", "thread/items/list"), descendants = read("thread/list"),
        )
    }

    private fun reason(scope: Boolean, available: Boolean) = when { !scope -> "Select a task first."; available -> null; else -> "This host does not advertise this feature." }
    private fun supports(method: String) = method in advertisedMethods
    private fun token(channel: String) = RequestToken(generation, ++requestSequence, channel).also { activeRequests[channel] = it }
    private fun current(token: RequestToken) = token.generation == generation && activeRequests[token.channel] == token && _uiState.value.scope != null

    private fun actionFailure(action: TaskAction, message: String) {
        _uiState.value = _uiState.value.copy(action = TaskActionStatus(completed = action, error = message))
    }
    private fun searchFailure(query: String, message: String) {
        _uiState.value = _uiState.value.copy(search = TaskSearchState(query = query, error = message)); retryAction = { search(query) }
    }
    private fun accountFailure(message: String) {
        _uiState.value = _uiState.value.copy(account = _uiState.value.account.copy(loading = false, error = message)); retryAction = ::refreshAccountUsage
    }
    private fun goalFailure(message: String) {
        _uiState.value = _uiState.value.copy(goal = _uiState.value.goal.copy(loading = false, error = message)); retryAction = ::refreshGoal
    }
    private fun historyFailure(message: String) {
        _uiState.value = _uiState.value.copy(history = _uiState.value.history.copy(loading = false, loadingMore = false, error = message)); retryAction = ::refreshHistory
    }
    private fun descendantsFailure(message: String) {
        _uiState.value = _uiState.value.copy(descendants = _uiState.value.descendants.copy(loading = false, loadingMore = false, error = message)); retryAction = ::refreshDescendants
    }

    private data class RequestToken(val generation: Long, val sequence: Long, val channel: String)

    companion object {
        private const val PAGE_SIZE = 50

        private fun parseSearchMatch(value: JSONObject): TaskSearchMatch? {
            val thread = value.optJSONObject("thread") ?: return null
            val id = thread.string("id") ?: return null
            return TaskSearchMatch(id, thread.title(), value.optString("snippet"), thread.optBoolean("archived"), thread.longOrNull("updatedAt"))
        }

        private fun parseOccurrence(value: JSONObject): TaskSearchOccurrence? {
            val turnId = value.string("turnId") ?: return null
            val itemId = value.string("itemId") ?: return null
            val range = value.optJSONObject("snippetMatchRange")
            return TaskSearchOccurrence(turnId, itemId, value.optString("snippet"), range?.optInt("start") ?: 0, range?.optInt("end") ?: 0, value.optString("turnCursor"))
        }

        private fun parseRateLimits(result: JSONObject): List<AccountRateLimit> {
            val byId = result.optJSONObject("rateLimitsByLimitId")
            if (byId != null && byId.length() > 0) return byId.keys().asSequence().mapNotNull { key ->
                byId.optJSONObject(key)?.let { parseRateLimit(it, key) }
            }.toList()
            return result.optJSONObject("rateLimits")?.let { listOf(parseRateLimit(it, it.string("limitId"))) }.orEmpty()
        }

        private fun parseRateLimit(value: JSONObject, fallbackId: String?) = AccountRateLimit(
            value.string("limitId") ?: fallbackId, value.string("limitName"),
            value.optJSONObject("primary")?.let(::parseWindow), value.optJSONObject("secondary")?.let(::parseWindow),
            value.string("planType"), value.string("rateLimitReachedType"),
            value.optJSONObject("credits")?.let { AccountCredits(it.optBoolean("hasCredits"), it.optBoolean("unlimited"), it.string("balance")) },
            value.optJSONObject("individualLimit")?.let { SpendControl(it.optString("limit"), it.optString("used"), it.optDouble("remainingPercent"), it.optLong("resetsAt")) },
            value.opt("spendControlReached")?.takeIf { it != JSONObject.NULL } as? Boolean,
        )

        private fun mergeRateLimit(old: AccountRateLimit, update: AccountRateLimit) = AccountRateLimit(
            id = update.id ?: old.id,
            name = update.name ?: old.name,
            primary = mergeWindow(old.primary, update.primary),
            secondary = mergeWindow(old.secondary, update.secondary),
            planType = update.planType ?: old.planType,
            reachedType = update.reachedType ?: old.reachedType,
            credits = update.credits ?: old.credits,
            spendControl = update.spendControl ?: old.spendControl,
            spendControlReached = update.spendControlReached ?: old.spendControlReached,
        )

        private fun mergeWindow(old: RateLimitWindow?, update: RateLimitWindow?): RateLimitWindow? = when {
            update == null -> old
            old == null -> update
            else -> RateLimitWindow(
                usedPercent = update.usedPercent,
                windowDurationMinutes = update.windowDurationMinutes ?: old.windowDurationMinutes,
                resetsAtEpochSeconds = update.resetsAtEpochSeconds ?: old.resetsAtEpochSeconds,
            )
        }

        private fun parseResetCredits(value: JSONObject): List<RateLimitResetCredit>? {
            if (!value.has("credits") || value.isNull("credits")) return null
            return value.array("credits").objects().mapNotNull {
                val id = it.string("id") ?: return@mapNotNull null
                RateLimitResetCredit(id, it.optString("resetType"), it.optString("status"), it.optLong("grantedAt"), it.longOrNull("expiresAt"), it.string("title"), it.string("description"))
            }
        }

        private fun parseWindow(value: JSONObject) = RateLimitWindow(value.optDouble("usedPercent", 0.0), value.longOrNull("windowDurationMins"), value.longOrNull("resetsAt"))

        private fun parseUsage(result: JSONObject): AccountUsage {
            val summary = result.optJSONObject("summary") ?: JSONObject()
            return AccountUsage(
                summary.longOrNull("lifetimeTokens"), summary.longOrNull("peakDailyTokens"), summary.longOrNull("longestRunningTurnSec"),
                summary.longOrNull("currentStreakDays"), summary.longOrNull("longestStreakDays"),
                result.array("dailyUsageBuckets").objects().map { DailyTokenUsage(it.optString("startDate"), it.longOrNull("tokens") ?: 0L) },
            )
        }

        private fun parseGoal(value: JSONObject): TaskGoal? {
            val objective = value.string("objective") ?: return null
            val status = GoalStatus.fromWire(value.optString("status")) ?: return null
            return TaskGoal(objective, status, value.longOrNull("tokenBudget"), value.optLong("tokensUsed"), value.optLong("timeUsedSeconds"), value.longOrNull("createdAt"), value.longOrNull("updatedAt"))
        }

        private fun parseHistory(method: String, value: JSONObject, index: Int): List<HistoryEntry> {
            if (method == "thread/items/list") {
                val item = value.optJSONObject("item") ?: JSONObject()
                val id = item.string("id") ?: "item-$index"
                val type = item.optString("type", "item")
                return listOf(HistoryEntry(id, value.string("turnId"), type, historyTitle(type), historySummary(item)))
            }
            val id = value.string("id") ?: "turn-$index"
            val items = value.array("items").objects()
            if (items.isNotEmpty()) return items.mapIndexed { itemIndex, item ->
                val itemId = item.string("id") ?: "$id-item-$itemIndex"
                val type = item.optString("type", "item")
                HistoryEntry(itemId, id, type, historyTitle(type), historySummary(item))
            }
            val status = value.optString("status", "turn")
            return listOf(HistoryEntry(id, id, "turn", "Turn · ${status.replaceFirstChar { it.uppercase() }}", historySummary(value)))
        }

        private fun historyTitle(type: String) = Regex("([a-z])([A-Z])").replace(type) {
            "${it.groupValues[1]} ${it.groupValues[2]}"
        }.replaceFirstChar { it.uppercase() }

        private fun historySummary(value: JSONObject): String {
            val scalar = sequenceOf("text", "message", "name", "command")
                .mapNotNull { key -> value.string(key) }.firstOrNull()
            val content = value.optJSONArray("content")?.let { items ->
                (0 until items.length()).mapNotNull { index -> items.optJSONObject(index)?.string("text") }
                    .joinToString(" ").takeIf(String::isNotBlank)
            }
            val summary = when (val raw = value.opt("summary")) {
                is String -> raw.takeIf(String::isNotBlank)
                is JSONArray -> (0 until raw.length()).mapNotNull { index -> raw.optString(index).takeIf(String::isNotBlank) }
                    .joinToString(" ").takeIf(String::isNotBlank)
                else -> null
            }
            return sequenceOf(scalar, content, summary).filterNotNull().firstOrNull()
                ?.replace(Regex("\\s+"), " ")?.take(240).orEmpty()
        }

        private fun parseDescendant(value: JSONObject): DescendantTask? {
            val id = value.string("id") ?: return null
            val spawn = value.optJSONObject("source")?.optJSONObject("subAgent")?.optJSONObject("thread_spawn")
            val nickname = value.string("agentNickname") ?: spawn?.string("agent_nickname")
            val role = value.string("agentRole") ?: spawn?.string("agent_role")
            val title = sequenceOf(nickname, value.string("name"), role, value.string("preview")).firstOrNull { !it.isNullOrBlank() } ?: "Untitled agent"
            return DescendantTask(
                id, title, value.optJSONObject("status")?.optString("type") ?: value.optString("status"),
                value.string("parentThreadId") ?: spawn?.string("parent_thread_id"), value.longOrNull("updatedAt"),
                value.opt("canAcceptDirectInput")?.takeIf { it != JSONObject.NULL } as? Boolean, nickname, role,
            )
        }

        private fun JSONObject.title() = sequenceOf("name", "title", "preview").mapNotNull { key -> string(key) }.firstOrNull() ?: "Untitled task"
        private fun JSONObject.string(key: String): String? = optString(key).takeIf { has(key) && !isNull(key) && it.isNotBlank() && it != "null" }
        private fun JSONObject.longOrNull(key: String): Long? = if (!has(key) || isNull(key)) null else when (val value = opt(key)) {
            is Number -> value.toLong()
            is String -> value.toLongOrNull()
            else -> null
        }
        private fun JSONObject.array(key: String): JSONArray = optJSONArray(key) ?: JSONArray()
        private fun JSONArray.objects() = (0 until length()).mapNotNull(::optJSONObject)
    }
}
