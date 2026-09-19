package app.codexremote.android

import org.json.JSONObject

/**
 * Keeps the canonical item lifecycle stream that app-server emits while a turn is running.
 *
 * Some legacy histories omit command and tool items from thread/read. Keeping the live item
 * lifecycle lets the UI preserve the same alternating transcript the user saw during execution.
 * This cache is intentionally memory-only so command output is not persisted on the phone.
 */
class LiveTimelineStore {
    private data class TurnBuffer(
        var status: String,
        val startedAtEpochMs: Long,
        val items: LinkedHashMap<String, TimelineItem> = linkedMapOf(),
        val completedItems: MutableSet<String> = mutableSetOf(),
        var turnDiff: TurnDiffSummary? = null,
    )

    private val threads = object : LinkedHashMap<String, LinkedHashMap<String, TurnBuffer>>(8, 0.75f, true) {
        override fun removeEldestEntry(
            eldest: MutableMap.MutableEntry<String, LinkedHashMap<String, TurnBuffer>>?,
        ): Boolean = size > MAX_THREADS
    }
    private val cancellationRequests = mutableSetOf<Pair<String, String>>()

    fun record(method: String, params: JSONObject, scopedThreadId: String? = null): Boolean {
        val threadId = params.optString("threadId").takeUnless { it == "null" }.orEmpty()
            .ifBlank { scopedThreadId.orEmpty() }
        if (threadId.isBlank()) return false
        val turnObject = params.optJSONObject("turn")
        val explicitTurnId = params.optString("turnId").takeUnless { it == "null" }.orEmpty()
            .ifBlank { turnObject?.optString("id")?.takeUnless { it == "null" }.orEmpty() }
        val allowsMissingTurn = method in setOf(
            "warning",
            "guardianWarning",
            "configWarning",
            "thread/settings/updated",
            "hook/started",
            "hook/completed",
        )
        if (explicitTurnId.isBlank() && !allowsMissingTurn) return false
        val turnId = explicitTurnId.ifBlank {
            activeTurnId(threadId).orEmpty().ifBlank { threadNoticeTurnId(threadId) }
        }

        return when (method) {
            "turn/started" -> {
                val buffer = turn(threadId, turnId)
                // A delayed start must not resurrect a turn already settled by a
                // completion event or an authoritative thread/read snapshot.
                if (buffer.status.equals("inProgress", ignoreCase = true)) {
                    buffer.status = turnObject?.optString("status").orEmpty().ifBlank { "inProgress" }
                }
                recordTurnItems(threadId, turnId, turnObject, completed = false)
                true
            }
            "turn/completed", "turn/failed", "turn/cancelled" -> {
                val buffer = turn(threadId, turnId)
                buffer.status = if (cancellationRequests.remove(threadId to turnId)) {
                    "cancelled"
                } else turnObject?.optString("status").orEmpty().ifBlank { method.substringAfter('/') }
                recordTurnItems(threadId, turnId, turnObject, completed = true)
                finishItems(buffer)
                true
            }
            "item/started", "item/completed" -> recordItem(
                threadId = threadId,
                turnId = turnId,
                item = params.optJSONObject("item"),
                active = method == "item/started",
                completed = method == "item/completed",
            )
            "item/agentMessage/delta" -> appendText(
                threadId,
                turnId,
                params.optString("itemId"),
                params.optString("delta"),
                TimelineItem.Kind.ASSISTANT,
                "Codex",
            )
            "item/plan/delta" -> appendText(
                threadId,
                turnId,
                params.optString("itemId"),
                params.optString("delta"),
                TimelineItem.Kind.PLAN,
                "Planning",
            )
            "item/reasoning/summaryTextDelta", "item/reasoning/textDelta" -> appendText(
                threadId,
                turnId,
                params.optString("itemId"),
                params.optString("delta"),
                TimelineItem.Kind.REASONING,
                "Thinking",
            )
            "item/reasoning/summaryPartAdded" -> appendReasoningSection(threadId, turnId, params.optString("itemId"))
            "item/commandExecution/outputDelta" -> appendText(
                threadId,
                turnId,
                params.optString("itemId"),
                params.optString("delta"),
                TimelineItem.Kind.COMMAND,
                "Running command",
                monospaceOutput = true,
            )
            "item/fileChange/patchUpdated" -> updateFileChangePatch(
                threadId,
                turnId,
                params.optString("itemId"),
                params.optJSONArray("changes"),
            )
            "turn/plan/updated" -> updatePlan(threadId, turnId, params)
            "model/rerouted" -> upsertNotice(
                threadId,
                turnId,
                TimelineItem(
                    id = "model-rerouted-$turnId",
                    label = "Model rerouted",
                    text = buildString {
                        append(params.optString("fromModel")).append(" → ").append(params.optString("toModel"))
                        params.optString("reason").takeIf(String::isNotBlank)?.let { append("\nReason: ").append(humanize(it)) }
                    },
                    kind = TimelineItem.Kind.TOOL,
                    phase = "completed",
                ),
            )
            "warning", "guardianWarning", "configWarning" -> recordWarning(threadId, turnId, method, params)
            "thread/settings/updated" -> upsertNotice(
                threadId,
                turnId,
                TimelineItem(
                    id = "thread-settings-$turnId",
                    label = "Task settings updated",
                    text = params.optJSONObject("threadSettings")?.toString(2).orEmpty(),
                    kind = TimelineItem.Kind.TOOL,
                    phase = "completed",
                ),
            )
            "item/autoApprovalReview/started", "item/autoApprovalReview/completed" ->
                updateApprovalReview(threadId, turnId, params, method.endsWith("/completed"))
            "hook/started", "hook/completed" -> updateHook(threadId, turnId, params, method.endsWith("/completed"))
            "item/mcpToolCall/progress" -> appendMcpProgress(
                threadId,
                turnId,
                params.optString("itemId"),
                params.optString("message"),
            )
            "turn/diff/updated" -> {
                val summary = ThreadProjection.turnDiffSummary(params.optString("diff")) ?: return false
                turn(threadId, turnId).turnDiff = summary
                true
            }
            else -> false
        }
    }

    fun snapshots(threadId: String): Map<String, LiveTurnSnapshot> = threads[threadId]
        ?.mapValues { (_, turn) ->
            LiveTurnSnapshot(turn.status, turn.items.values.toList(), turn.startedAtEpochMs, turn.turnDiff)
        }
        .orEmpty()

    fun activeTurnId(threadId: String): String? = threads[threadId]
        ?.entries
        ?.lastOrNull { (_, turn) -> turn.status.equals("inProgress", ignoreCase = true) }
        ?.key

    fun isTurnTerminal(threadId: String, turnId: String): Boolean = threads[threadId]?.get(turnId)
        ?.status?.equals("inProgress", ignoreCase = true) == false

    fun isItemCompleted(threadId: String, turnId: String, itemId: String): Boolean =
        itemId in threads[threadId]?.get(turnId)?.completedItems.orEmpty()

    /** Merge server lifecycle facts without discarding live items absent from legacy history. */
    fun reconcileThreadSnapshot(threadId: String, thread: JSONObject, capturedRevision: Long, currentRevision: Long): Boolean {
        if (capturedRevision != currentRevision) return false
        val terminal = setOf("completed", "failed", "cancelled", "canceled", "interrupted")
        val turns = thread.optJSONArray("turns")
        if (turns != null) for (index in 0 until turns.length()) {
            val turn = turns.optJSONObject(index) ?: continue
            val id = turn.optString("id")
            val status = turn.optString("status")
            if (id.isNotBlank() && status.lowercase() in terminal) {
                threads[threadId]?.get(id)?.let { it.status = status; finishItems(it) }
                cancellationRequests -= threadId to id
            }
        }
        val status = when (val value = thread.opt("status")) {
            is JSONObject -> value.optString("type")
            else -> value?.toString().orEmpty()
        }
        if (status.lowercase() in setOf("idle", "completed", "failed", "cancelled", "canceled", "interrupted")) {
            settleActiveTurn(threadId)
        }
        return true
    }

    /** The host confirmed that no turn can be interrupted; the outcome remains unknown until refresh. */
    fun settleActiveTurn(threadId: String, turnId: String? = null) {
        val entries = threads[threadId]?.entries?.filter { (id, turn) ->
            (turnId == null || id == turnId) && (turn.status.equals("inProgress", ignoreCase = true) ||
                (threadId to id) in cancellationRequests)
        }.orEmpty()
        entries.forEach { (id, turn) ->
            turn.status = "unknown"
            finishItems(turn)
            cancellationRequests -= threadId to id
        }
    }

    fun requestCancellation(threadId: String, turnId: String) {
        cancellationRequests += threadId to turnId
        threads[threadId]?.get(turnId)?.status = "cancelled"
    }

    fun clearCancellationRequest(threadId: String, turnId: String, restoreRunning: Boolean = true) {
        val pending = cancellationRequests.remove(threadId to turnId)
        if (pending && restoreRunning && threads[threadId]?.get(turnId)?.status == "cancelled") {
            threads[threadId]?.get(turnId)?.status = "inProgress"
        }
    }

    fun failActiveTurn(threadId: String, reason: String): Boolean {
        val entry = threads[threadId]?.entries
            ?.lastOrNull { (_, turn) -> turn.status.equals("inProgress", ignoreCase = true) }
            ?: return false
        entry.value.status = "failed"
        finishItems(entry.value)
        entry.value.items["connection-failure-${entry.key}"] = TimelineItem(
            id = "connection-failure-${entry.key}",
            label = "Connection failed",
            text = reason,
            kind = TimelineItem.Kind.TOOL,
            phase = "failed",
            toolStyle = TimelineItem.ToolStyle.GENERIC,
        )
        return true
    }

    private fun updatePlan(threadId: String, turnId: String, params: JSONObject): Boolean {
        val steps = buildList {
            val array = params.optJSONArray("plan")
            if (array != null) for (index in 0 until array.length()) {
                val step = array.optJSONObject(index) ?: continue
                val text = step.optString("step")
                if (text.isNotBlank()) add(PlanStep(text, step.optString("status").ifBlank { "pending" }))
            }
        }
        val explanation = params.optString("explanation").takeUnless { it == "null" }.orEmpty()
        val text = buildList {
            explanation.takeIf(String::isNotBlank)?.let(::add)
            steps.forEach { step ->
                val marker = when (step.status.lowercase().replace("_", "")) {
                    "completed" -> "✓"
                    "inprogress" -> "→"
                    else -> "○"
                }
                add("$marker ${step.text}")
            }
        }.joinToString("\n")
        val active = steps.any { it.status.lowercase().replace("_", "") == "inprogress" }
        return upsertNotice(
            threadId,
            turnId,
            TimelineItem(
                id = "turn-plan-$turnId",
                label = if (active) "Plan in progress" else "Plan updated",
                text = text,
                kind = TimelineItem.Kind.PLAN,
                phase = if (active) "inprogress" else "completed",
                active = active,
                stepCurrent = steps.count { it.status.equals("completed", ignoreCase = true) },
                stepTotal = steps.size,
                planSteps = steps,
            ),
        )
    }

    private fun recordWarning(
        threadId: String,
        turnId: String,
        method: String,
        params: JSONObject,
    ): Boolean {
        val label = when (method) {
            "guardianWarning" -> "Guardian warning"
            "configWarning" -> "Configuration warning"
            else -> "Warning"
        }
        val summary = params.optString("message").ifBlank { params.optString("summary") }
        val text = buildList {
            summary.takeIf(String::isNotBlank)?.let(::add)
            params.optString("details").takeUnless { it == "null" }?.takeIf(String::isNotBlank)?.let(::add)
            params.optString("path").takeIf(String::isNotBlank)?.let { add("Config: $it") }
        }.joinToString("\n")
        val identity = listOf(method, summary, params.optString("path")).joinToString("|").hashCode()
        return upsertNotice(
            threadId,
            turnId,
            TimelineItem("notice-$identity", label, text, TimelineItem.Kind.ERROR, phase = "warning"),
        )
    }

    private fun updateApprovalReview(
        threadId: String,
        turnId: String,
        params: JSONObject,
        completed: Boolean,
    ): Boolean {
        val reviewId = params.optString("reviewId")
        if (reviewId.isBlank()) return false
        val review = params.optJSONObject("review") ?: JSONObject()
        val action = params.optJSONObject("action") ?: JSONObject()
        val actionType = action.optString("type").ifBlank { "action" }
        val actionSummary = approvalActionSummary(action)
        val details = ApprovalReviewDetails(
            reviewId = reviewId,
            targetItemId = params.optString("targetItemId").takeUnless { it.isBlank() || it == "null" },
            status = review.optString("status").ifBlank { if (completed) "completed" else "inProgress" },
            riskLevel = review.optString("riskLevel").takeUnless { it.isBlank() || it == "null" },
            userAuthorization = review.optString("userAuthorization").takeUnless { it.isBlank() || it == "null" },
            rationale = review.optString("rationale").takeUnless { it.isBlank() || it == "null" },
            actionType = actionType,
            actionSummary = actionSummary,
            decisionSource = params.optString("decisionSource").takeUnless { it.isBlank() || it == "null" },
            startedAtEpochMs = (params.opt("startedAtMs") as? Number)?.toLong(),
            completedAtEpochMs = (params.opt("completedAtMs") as? Number)?.toLong(),
        )
        val text = buildList {
            actionSummary.takeIf(String::isNotBlank)?.let(::add)
            details.rationale?.let(::add)
            details.riskLevel?.let { add("Risk: $it") }
            details.userAuthorization?.let { add("User authorization: $it") }
        }.joinToString("\n")
        return upsertNotice(
            threadId,
            turnId,
            TimelineItem(
                id = "auto-review-$reviewId",
                label = if (completed) "Auto-approval review: ${humanize(details.status)}" else "Reviewing approval",
                text = text,
                kind = TimelineItem.Kind.TOOL,
                phase = details.status,
                active = !completed,
                startedAtEpochMs = details.startedAtEpochMs,
                durationMs = if (details.startedAtEpochMs != null && details.completedAtEpochMs != null) {
                    (details.completedAtEpochMs - details.startedAtEpochMs).coerceAtLeast(0L)
                } else null,
                approvalReview = details,
            ),
        )
    }

    private fun approvalActionSummary(action: JSONObject): String = when (action.optString("type")) {
        "command" -> action.optString("command")
        "execve" -> buildList {
            action.optString("program").takeIf(String::isNotBlank)?.let(::add)
            val argv = action.optJSONArray("argv")
            if (argv != null) for (index in 0 until argv.length()) argv.optString(index).takeIf(String::isNotBlank)?.let(::add)
        }.joinToString(" ")
        "applyPatch" -> "Apply changes to ${action.optJSONArray("files")?.length() ?: 0} file(s)"
        "networkAccess" -> buildString {
            append(action.optString("protocol").ifBlank { "network" }).append(" ")
            append(action.optString("host").ifBlank { action.optString("target") })
            val port = action.optInt("port", -1)
            if (port >= 0) append(":").append(port)
        }.trim()
        "mcpToolCall" -> listOf(action.optString("connectorName"), action.optString("server"), action.optString("toolTitle"), action.optString("toolName"))
            .firstOrNull(String::isNotBlank).orEmpty()
        "requestPermissions" -> action.optString("reason").takeUnless { it == "null" }.orEmpty()
        else -> ""
    }

    private fun updateHook(
        threadId: String,
        turnId: String,
        params: JSONObject,
        completed: Boolean,
    ): Boolean {
        val run = params.optJSONObject("run") ?: return false
        val id = run.optString("id")
        if (id.isBlank()) return false
        val status = run.optString("status").ifBlank { if (completed) "completed" else "running" }
        val entries = buildList {
            val values = run.optJSONArray("entries")
            if (values != null) for (index in 0 until values.length()) {
                val entry = values.optJSONObject(index) ?: continue
                val value = entry.optString("text")
                if (value.isNotBlank()) add("${humanize(entry.optString("kind"))}: $value")
            }
        }
        val text = buildList {
            run.optString("statusMessage").takeUnless { it == "null" }?.takeIf(String::isNotBlank)?.let(::add)
            addAll(entries)
        }.joinToString("\n")
        return upsertNotice(
            threadId,
            turnId,
            TimelineItem(
                id = "hook-$id",
                label = "${humanize(run.optString("eventName"))} hook",
                text = text,
                kind = TimelineItem.Kind.TOOL,
                phase = status,
                active = !completed && status.equals("running", ignoreCase = true),
                startedAtEpochMs = (run.opt("startedAt") as? Number)?.toLong(),
                durationMs = (run.opt("durationMs") as? Number)?.toLong(),
            ),
        )
    }

    private fun appendMcpProgress(
        threadId: String,
        turnId: String,
        itemId: String,
        message: String,
    ): Boolean {
        if (itemId.isBlank() || message.isBlank()) return false
        val buffer = turn(threadId, turnId)
        if (itemId in buffer.completedItems || !buffer.status.equals("inProgress", ignoreCase = true)) return false
        val current = buffer.items[itemId]
        val previousText = current?.text.orEmpty()
        val text = if (previousText.lineSequence().lastOrNull() == message) previousText else {
            listOf(previousText, message).filter(String::isNotBlank).joinToString("\n").takeLast(MAX_ITEM_TEXT)
        }
        buffer.items[itemId] = (current ?: TimelineItem(
            id = itemId,
            label = "Using integration",
            text = "",
            kind = TimelineItem.Kind.TOOL,
            toolStyle = TimelineItem.ToolStyle.INTEGRATION,
        )).copy(text = text, active = true, phase = "inprogress")
        trimItems(buffer)
        return true
    }

    private fun upsertNotice(threadId: String, turnId: String, item: TimelineItem): Boolean {
        val buffer = turn(threadId, turnId)
        if (turnId == threadNoticeTurnId(threadId)) buffer.status = "completed"
        buffer.items[item.id] = item
        if (!item.active) buffer.completedItems += item.id
        trimItems(buffer)
        return true
    }

    private fun threadNoticeTurnId(threadId: String) = "thread-notices-$threadId"

    private fun humanize(value: String): String = value
        .replace(Regex("([a-z])([A-Z])"), "$1 $2")
        .replace('_', ' ')
        .trim()
        .replaceFirstChar { it.uppercase() }

    private fun recordTurnItems(threadId: String, turnId: String, turnObject: JSONObject?, completed: Boolean): Boolean {
        val items = turnObject?.optJSONArray("items") ?: return false
        var changed = false
        for (index in 0 until items.length()) {
            changed = recordItem(threadId, turnId, items.optJSONObject(index), active = false, completed = completed) || changed
        }
        return changed
    }

    private fun recordItem(
        threadId: String,
        turnId: String,
        item: JSONObject?,
        active: Boolean,
        completed: Boolean,
    ): Boolean {
        val projected = item?.let(ThreadProjection::projectItem) ?: return false
        val buffer = turn(threadId, turnId)
        if (active && (projected.id in buffer.completedItems || !buffer.status.equals("inProgress", ignoreCase = true))) return false
        if (completed) buffer.completedItems += projected.id
        val previous = buffer.items[projected.id]
        val projectedHasDiff = projected.additions > 0 || projected.deletions > 0
        val mergedPaths = previous?.changedFiles.orEmpty() + projected.changedFiles
        val mergedFileDiffs = mergeFileDiffs(previous?.fileDiffs.orEmpty(), projected.fileDiffs)
        val finalItem = projected.copy(
            label = if (active && item.optString("type") == "webSearch") {
                projected.label.replaceFirst("Searched the web", "Searching the web")
            } else projected.label,
            phase = if (active) projected.phase ?: "inprogress" else projected.phase,
            active = active,
            text = projected.text.ifBlank { previous?.text.orEmpty() },
            changedFiles = mergedPaths,
            fileDiffs = mergedFileDiffs,
            fileChanges = mergeFileChanges(previous?.fileChanges.orEmpty(), projected.fileChanges),
            filesChanged = mergedPaths.size,
            additions = if (projectedHasDiff || previous == null) projected.additions else previous.additions,
            deletions = if (projectedHasDiff || previous == null) projected.deletions else previous.deletions,
            planSteps = projected.planSteps.ifEmpty { previous?.planSteps.orEmpty() },
            richOutputReferences = (previous?.richOutputReferences.orEmpty() + projected.richOutputReferences).distinct(),
            approvalReview = projected.approvalReview ?: previous?.approvalReview,
        )
        buffer.items[projected.id] = finalItem
        trimItems(buffer)
        return true
    }

    private fun updateFileChangePatch(
        threadId: String,
        turnId: String,
        itemId: String,
        changes: org.json.JSONArray?,
    ): Boolean {
        if (itemId.isBlank() || changes == null) return false
        val buffer = turn(threadId, turnId)
        if (itemId in buffer.completedItems || !buffer.status.equals("inProgress", ignoreCase = true)) return false
        val current = buffer.items[itemId]
        val projected = ThreadProjection.projectItem(JSONObject()
            .put("id", itemId)
            .put("type", "fileChange")
            .put("status", current?.phase ?: "inProgress")
            .put("changes", changes)) ?: return false
        val mergedPaths = current?.changedFiles.orEmpty() + projected.changedFiles
        buffer.items[itemId] = projected.copy(
            label = current?.label ?: projected.label,
            active = buffer.status.equals("inProgress", ignoreCase = true) && itemId !in buffer.completedItems &&
                (current?.active ?: true),
            phase = current?.phase ?: projected.phase,
            changedFiles = mergedPaths,
            fileDiffs = mergeFileDiffs(current?.fileDiffs.orEmpty(), projected.fileDiffs),
            fileChanges = mergeFileChanges(current?.fileChanges.orEmpty(), projected.fileChanges),
            filesChanged = mergedPaths.size,
        )
        trimItems(buffer)
        return true
    }

    private fun appendText(
        threadId: String,
        turnId: String,
        itemId: String,
        delta: String,
        fallbackKind: TimelineItem.Kind,
        fallbackLabel: String,
        monospaceOutput: Boolean = false,
    ): Boolean {
        if (delta.isEmpty()) return false
        val id = itemId.ifBlank { "live-${fallbackKind.name.lowercase()}-$turnId" }
        val buffer = turn(threadId, turnId)
        if (id in buffer.completedItems || !buffer.status.equals("inProgress", ignoreCase = true)) return false
        val current = buffer.items[id] ?: TimelineItem(
            id = id,
            label = fallbackLabel,
            text = "",
            kind = fallbackKind,
            active = true,
        )
        val appended = (current.text + delta).takeLast(MAX_ITEM_TEXT)
        buffer.items[id] = current.copy(
            text = appended,
            active = true,
            phase = current.phase ?: if (monospaceOutput) "inprogress" else null,
        )
        trimItems(buffer)
        return true
    }

    private fun appendReasoningSection(threadId: String, turnId: String, itemId: String): Boolean {
        if (itemId.isBlank()) return false
        val buffer = turn(threadId, turnId)
        val current = buffer.items[itemId] ?: return false
        if (itemId in buffer.completedItems || !buffer.status.equals("inProgress", ignoreCase = true)) return false
        if (current.text.isBlank() || current.text.endsWith('\n')) return false
        buffer.items[itemId] = current.copy(text = current.text + "\n", active = true)
        return true
    }

    private fun turn(threadId: String, turnId: String): TurnBuffer {
        val thread = threads.getOrPut(threadId) { linkedMapOf() }
        val buffer = thread.getOrPut(turnId) { TurnBuffer("inProgress", System.currentTimeMillis()) }
        while (thread.size > MAX_TURNS_PER_THREAD) thread.remove(thread.keys.first())
        return buffer
    }

    private fun trimItems(buffer: TurnBuffer) {
        while (buffer.items.size > MAX_ITEMS_PER_TURN) {
            val oldest = buffer.items.keys.first()
            buffer.items.remove(oldest)
            buffer.completedItems.remove(oldest)
        }
    }

    private fun finishItems(buffer: TurnBuffer) {
        buffer.items.replaceAll { _, item ->
            val wasRunning = item.active || item.phase?.lowercase() in
                setOf("inprogress", "in_progress", "running", "started")
            item.copy(
                active = false,
                phase = if (wasRunning) buffer.status else item.phase,
                label = if (wasRunning && item.kind == TimelineItem.Kind.COMMAND &&
                    buffer.status.equals("cancelled", ignoreCase = true))
                    item.label.replaceFirst(Regex("^Running\\b"), "Command cancelled")
                else if (wasRunning && item.kind == TimelineItem.Kind.COMMAND &&
                    buffer.status.equals("failed", ignoreCase = true))
                    item.label.replaceFirst(Regex("^Running\\b"), "Command failed")
                else item.label,
            )
        }
    }

    private fun mergeFileDiffs(
        previous: Map<String, FileDiffStat>,
        incoming: Map<String, FileDiffStat>,
    ): Map<String, FileDiffStat> = (previous.keys + incoming.keys).associateWith { path ->
        incoming[path] ?: previous.getValue(path)
    }

    private fun mergeFileChanges(
        previous: List<FileDiffDetail>,
        incoming: List<FileDiffDetail>,
    ): List<FileDiffDetail> = (previous.map(FileDiffDetail::path) + incoming.map(FileDiffDetail::path))
        .distinct()
        .mapNotNull { path ->
            val old = previous.lastOrNull { it.path == path }
            val fresh = incoming.lastOrNull { it.path == path }
            when {
                fresh == null -> old
                fresh.patch.isNotBlank() -> fresh
                old != null -> fresh.copy(patch = old.patch)
                else -> fresh
            }
        }

    private companion object {
        const val MAX_THREADS = 8
        const val MAX_TURNS_PER_THREAD = 20
        const val MAX_ITEMS_PER_TURN = 500
        const val MAX_ITEM_TEXT = 64 * 1024
    }
}
