package app.codexremote.android

import org.json.JSONArray
import org.json.JSONObject

data class RemoteThread(
    val id: String,
    val title: String,
    val cwd: String,
    val status: String,
    val updatedAtEpochSeconds: Long?,
    val isRunning: Boolean,
    val cwdName: String = "",
)

internal fun workspaceDisplayName(path: String, name: String = ""): String {
    if (path.isBlank()) return "Workspace"
    if (!path.startsWith("remote-workspace://")) return path.trimEnd('/').substringAfterLast('/').ifBlank { "root" }
    return name.takeIf { it.isNotBlank() && it.length <= 255 && it.none { c -> c == '/' || c == '\\' || c < ' ' } }
        ?: "Workspace"
}

data class FileDiffStat(val additions: Int = 0, val deletions: Int = 0) {
    operator fun plus(other: FileDiffStat) = FileDiffStat(additions + other.additions, deletions + other.deletions)
}

data class FileDiffDetail(
    val path: String,
    val patch: String,
    val kind: String,
    val additions: Int = 0,
    val deletions: Int = 0,
    val displayName: String = path,
)

internal fun JSONObject.displayPath(field: String = "path"): String {
    val path = opt(field) as? String ?: return ""
    if (!path.startsWith("remote-path://")) return path
    return optString("${field}Name").takeIf { name ->
        name.isNotBlank() && name.length <= 255 && name.none { it == '/' || it == '\\' || it < ' ' }
    } ?: "File"
}

data class MessageAttachment(val id: String, val name: String, val kind: String)

data class TimelineItem(
    val id: String,
    val label: String,
    val text: String,
    val kind: Kind,
    val children: List<TimelineItem> = emptyList(),
    val phase: String? = null,
    val active: Boolean = false,
    val toolStyle: ToolStyle = ToolStyle.GENERIC,
    val rawCommand: String? = null,
    val commandWrapper: Boolean = false,
    val startedAtEpochMs: Long? = null,
    val durationMs: Long? = null,
    val stepCurrent: Int = 0,
    val stepTotal: Int = 0,
    val changedFiles: Set<String> = emptySet(),
    val fileDiffs: Map<String, FileDiffStat> = emptyMap(),
    val fileChanges: List<FileDiffDetail> = emptyList(),
    val filesChanged: Int = 0,
    val additions: Int = 0,
    val deletions: Int = 0,
    val imagePath: String? = null,
    val attachments: List<MessageAttachment> = emptyList(),
) {
    enum class ToolStyle { GENERIC, WEB, INTEGRATION, READ, SEARCH, SKILL }

    enum class Kind {
        USER,
        ASSISTANT,
        ACTIVITY_GROUP,
        ACTION_GROUP,
        TURN_SEPARATOR,
        COMMENTARY,
        REASONING,
        PLAN,
        COMMAND,
        FILE_CHANGE,
        TOOL,
        ERROR,
    }
}

data class LiveTurnSnapshot(
    val status: String,
    val items: List<TimelineItem>,
    val startedAtEpochMs: Long? = null,
)

object ThreadProjection {
    /**
     * The desktop transcript renders each protocol item in-place.  Do not
     * collapse adjacent calls into category summaries such as "Ran commands":
     * doing so loses both the one-call-per-row structure and the command title
     * that the canonical session log already provides.
     */
    internal fun activitySections(children: List<TimelineItem>): List<TimelineItem> = children.filterNot { item ->
        item.kind in setOf(
            TimelineItem.Kind.COMMENTARY,
            TimelineItem.Kind.REASONING,
            TimelineItem.Kind.PLAN,
        ) && item.text.isBlank()
    }

    internal fun activityGroupCanCollapse(item: TimelineItem): Boolean =
        item.kind == TimelineItem.Kind.ACTIVITY_GROUP && !item.active && item.children.isNotEmpty()

    internal fun threadTitle(thread: JSONObject): String {
        val explicit = jsonText(thread, "name")
        if (explicit.isNotBlank()) return explicit
        val preview = jsonText(thread, "preview")
        if (preview.isNotBlank()) return preview
        val latestUserText = thread.optJSONArray("turns").objects().asReversed()
            .firstNotNullOfOrNull { turn ->
                turn.optJSONArray("items").objects().asReversed()
                    .firstOrNull { it.optString("type") == "userMessage" }
                    ?.let { userContent(it.optJSONArray("content")) }
                    ?.trim()
                    ?.takeIf(String::isNotBlank)
            }
        return latestUserText?.lineSequence()?.firstOrNull().orEmpty().trim().take(96)
            .ifBlank { "New chat" }
    }

    fun threads(result: JSONObject): List<RemoteThread> = result.optJSONArray("data").objects().mapNotNull { item ->
        val id = item.optString("id")
        if (id.isBlank()) return@mapNotNull null
        val status = statusText(item.opt("status"))
        RemoteThread(
            id = id,
            title = threadTitle(item),
            cwd = item.optString("cwd"),
            status = status,
            updatedAtEpochSeconds = epochSeconds(item.opt("updatedAt"))
                ?: epochSeconds(item.opt("updated_at")),
            isRunning = normalizedStatus(status) in RUNNING_STATUSES,
            cwdName = workspaceDisplayName(item.optString("cwd"), item.optString("cwdName")),
        )
    }

    internal fun relativeAge(updatedAtEpochSeconds: Long?, nowEpochSeconds: Long): String {
        val updatedAt = updatedAtEpochSeconds ?: return "—"
        val elapsed = (nowEpochSeconds - updatedAt).coerceAtLeast(0L)
        return when {
            elapsed < 60L -> "now"
            elapsed < 3_600L -> "${elapsed / 60L}m"
            elapsed < 86_400L -> "${elapsed / 3_600L}h"
            elapsed < 604_800L -> "${elapsed / 86_400L}d"
            elapsed < 2_592_000L -> "${elapsed / 604_800L}w"
            elapsed < 31_536_000L -> "${elapsed / 2_592_000L}mo"
            else -> "${elapsed / 31_536_000L}y"
        }
    }

    fun timeline(
        thread: JSONObject,
        liveTurns: Map<String, LiveTurnSnapshot> = emptyMap(),
    ): List<TimelineItem> {
        val result = mutableListOf<TimelineItem>()
        val snapshotTurnIds = mutableSetOf<String>()
        thread.optJSONArray("turns").objects().forEach { turn ->
            val turnId = turn.optString("id").ifBlank { turn.hashCode().toString() }
            snapshotTurnIds += turnId
            val liveTurn = liveTurns[turnId]
            appendTurn(
                result = result,
                turnId = turnId,
                status = liveTurn?.status?.takeIf(String::isNotBlank) ?: turn.optString("status"),
                durationMs = turnDurationMs(turn),
                startedAtEpochMs = liveTurn?.startedAtEpochMs ?: turnStartedAtMs(turn),
                snapshotItems = turn.optJSONArray("items").objects().mapNotNull(::projectItem),
                liveItems = liveTurn?.items.orEmpty(),
            )
            if (turn.optString("status") == "failed") {
                result += TimelineItem(
                    id = "turn-error-${turn.optString("id")}",
                    label = "Turn failed",
                    text = AgentErrorMessage.text(turn.opt("error")),
                    kind = TimelineItem.Kind.ERROR,
                )
            }
        }
        liveTurns.forEach { (turnId, liveTurn) ->
            if (turnId !in snapshotTurnIds) appendTurn(
                result = result,
                turnId = turnId,
                status = liveTurn.status,
                durationMs = null,
                startedAtEpochMs = liveTurn.startedAtEpochMs ?: uuidV7Timestamp(turnId),
                snapshotItems = emptyList(),
                liveItems = liveTurn.items,
            )
        }
        return result
    }

    internal fun projectItem(item: JSONObject): TimelineItem? {
        val id = item.optString("id").ifBlank { item.hashCode().toString() }
        return when (item.optString("type")) {
            "userMessage" -> {
                val content = item.optJSONArray("content")
                TimelineItem(id, "You", content.objects().filter { messageAttachment(it) == null }
                     .mapNotNull {
                        if (it.optString("type") == "localImage") "Image attachment · preview requires a newer host"
                        else it.optString("text").ifBlank { it.optString("url") }.trim().takeIf(String::isNotBlank)
                    }.joinToString("\n"),
                    TimelineItem.Kind.USER, attachments = content.objects().mapNotNull(::messageAttachment))
            }
            "agentMessage" -> TimelineItem(
                id = id,
                label = "Codex",
                text = item.optString("text"),
                kind = TimelineItem.Kind.ASSISTANT,
                phase = jsonText(item, "phase").ifBlank { null },
            )
            "reasoning" -> TimelineItem(id, "Thinking", stringArray(item.optJSONArray("summary")).ifBlank {
                stringArray(item.optJSONArray("content"))
            }, TimelineItem.Kind.REASONING)
            "plan" -> TimelineItem(id, "Planning", item.optString("text"), TimelineItem.Kind.PLAN)
            "commandExecution" -> {
                val originalCommand = item.optString("command")
                val command = canonicalCommand(originalCommand)
                TimelineItem(
                    id,
                    commandLabel(item.optString("status"), command),
                    item.optString("aggregatedOutput"),
                    TimelineItem.Kind.COMMAND,
                    phase = normalizedStatus(item.optString("status")),
                    toolStyle = semanticCommandStyle(command),
                    rawCommand = command.takeIf(String::isNotBlank),
                    commandWrapper = command != originalCommand.trim(),
                )
            }
            "fileChange" -> {
                val changes = item.optJSONArray("changes").objects()
                val paths = changes.map { it.optString("path") }.filter(String::isNotBlank).toSet()
                val diff = changes.fold(DiffStats()) { total, change -> total + diffStats(change) }
                val fileDiffs = changes.mapNotNull { change ->
                    val path = change.optString("path").takeIf(String::isNotBlank) ?: return@mapNotNull null
                    val stats = diffStats(change)
                    path to FileDiffStat(stats.additions, stats.deletions)
                }.groupBy({ it.first }, { it.second }).mapValues { (_, values) -> values.reduce(FileDiffStat::plus) }
                val fileChanges = changes.mapNotNull { change ->
                    val path = change.optString("path").takeIf(String::isNotBlank) ?: return@mapNotNull null
                    val stats = diffStats(change)
                    val kind = change.optJSONObject("kind")?.optString("type").orEmpty()
                    val rawPatch = sequenceOf("diff", "patch", "content")
                        .map(change::optString)
                        .firstOrNull(String::isNotBlank)
                        .orEmpty()
                    FileDiffDetail(path, displayPatch(rawPatch, kind), kind, stats.additions, stats.deletions, change.displayPath())
                }
                TimelineItem(
                    id,
                    fileChangeLabel(item.optString("status"), changes),
                changes.joinToString("\n") { change ->
                    change.displayPath().ifBlank { "File" }
                },
                TimelineItem.Kind.FILE_CHANGE,
                phase = normalizedStatus(item.optString("status")),
                changedFiles = paths,
                fileDiffs = fileDiffs,
                fileChanges = fileChanges,
                filesChanged = paths.size,
                additions = diff.additions,
                deletions = diff.deletions,
            )
            }
            "mcpToolCall" -> {
                val label = mcpToolLabel(item)
                TimelineItem(
                    id,
                    label,
                    toolCallDetail(item),
                    TimelineItem.Kind.TOOL,
                    phase = normalizedStatus(item.optString("status")),
                    toolStyle = semanticToolStyle(item.optString("tool"), label, TimelineItem.ToolStyle.INTEGRATION),
                )
            }
            "dynamicToolCall", "collabToolCall", "collabAgentToolCall" -> {
                val tool = item.optString("tool", item.optString("type"))
                if (isCommandTool(tool)) {
                    val command = canonicalCommand(dynamicCommand(item.optJSONObject("arguments")).orEmpty())
                    TimelineItem(
                        id,
                        commandLabel(item.optString("status"), command),
                        toolCallDetail(item),
                        TimelineItem.Kind.COMMAND,
                        phase = normalizedStatus(item.optString("status")),
                        toolStyle = semanticCommandStyle(command),
                        rawCommand = command.takeIf(String::isNotBlank),
                    )
                } else {
                    val label = toolLabel(tool, item.optString("status"))
                    TimelineItem(
                        id,
                        label,
                        toolCallDetail(item),
                        TimelineItem.Kind.TOOL,
                        phase = normalizedStatus(item.optString("status")),
                        toolStyle = semanticToolStyle(tool, label),
                    )
                }
            }
            "customToolCall" -> {
                val tool = item.optString("name", item.optString("tool", "tool"))
                val command = item.optString("command").ifBlank {
                    dynamicCommand(item.optJSONObject("arguments")).orEmpty()
                }
                val canonicalCommand = canonicalCommand(command)
                if (isCommandTool(tool) && canonicalCommand.isNotBlank()) TimelineItem(
                    id = id,
                    label = commandLabel(item.optString("status"), canonicalCommand),
                    text = toolCallDetail(item),
                    kind = TimelineItem.Kind.COMMAND,
                    phase = normalizedStatus(item.optString("status")),
                    toolStyle = semanticCommandStyle(canonicalCommand),
                    rawCommand = canonicalCommand,
                    commandWrapper = canonicalCommand != command.trim(),
                ) else TimelineItem(
                    id = id,
                    label = toolLabel(tool, item.optString("status")),
                    text = toolCallDetail(item),
                    kind = TimelineItem.Kind.TOOL,
                    phase = normalizedStatus(item.optString("status")),
                    toolStyle = semanticToolStyle(tool, tool),
                )
            }
            "webSearch" -> TimelineItem(
                id,
                webSearchLabel(item),
                "",
                TimelineItem.Kind.TOOL,
                phase = "completed",
                toolStyle = TimelineItem.ToolStyle.WEB,
            )
            "imageView" -> TimelineItem(id, "Viewed image", item.displayPath(), TimelineItem.Kind.TOOL, phase = "completed")
            "imageGeneration" -> TimelineItem(
                id,
                "Generated image",
                "",
                TimelineItem.Kind.TOOL,
                phase = item.optString("status").ifBlank { "completed" },
                imagePath = item.optString("path").ifBlank { item.optString("savedPath") }.ifBlank { null },
            )
            "subAgentActivity" -> TimelineItem(id, "Investigating", item.optString("agentPath"), TimelineItem.Kind.TOOL, phase = "completed")
            "contextCompaction" -> TimelineItem(id, "Compacted context", "", TimelineItem.Kind.TOOL, phase = "completed")
            else -> null
        }
    }

    private fun appendTurn(
        result: MutableList<TimelineItem>,
        turnId: String,
        status: String,
        durationMs: Long?,
        startedAtEpochMs: Long?,
        snapshotItems: List<TimelineItem>,
        liveItems: List<TimelineItem>,
    ) {
        val projected = compactEquivalentCommandWrappers(
            classifyAgentMessages(mergeTurnItems(snapshotItems, liveItems)),
        )
        val isRunning = normalizedStatus(status) in RUNNING_STATUSES
        val explicitFinalIndex = projected.indexOfLast {
            it.kind == TimelineItem.Kind.ASSISTANT && it.phase == "final_answer"
        }
        val hasConcreteWork = projected.any { it.kind.isConcreteWork() }
        val inferredFinalIndex = if (!isRunning && explicitFinalIndex < 0 && hasConcreteWork) {
            projected.indexOfLast { it.kind == TimelineItem.Kind.ASSISTANT }
        } else -1
        val finalAnswerIndex = maxOf(explicitFinalIndex, inferredFinalIndex)
        if (!isRunning && finalAnswerIndex < 0 && !hasConcreteWork) {
            result += projected.map { it.copy(active = false) }
            return
        }
        val users = projected.filter { it.kind == TimelineItem.Kind.USER }
        val finalAnswer = projected.getOrNull(finalAnswerIndex)
        // The final-answer phase is the protocol boundary between execution and
        // delivery.  The server may keep the turn itself inProgress briefly, but
        // the execution card must already be settled when final text starts.
        val executionIsRunning = isRunning && finalAnswerIndex < 0
        val executionDurationMs = durationMs ?: if (!executionIsRunning) {
            val finalStartedAt = finalAnswer?.id?.let(::uuidV7Timestamp)
            if (finalStartedAt != null && startedAtEpochMs != null) {
                (finalStartedAt - startedAtEpochMs).coerceAtLeast(0L)
            } else null
        } else null
        val work = projected.mapIndexedNotNull { index, item ->
            if (item.kind == TimelineItem.Kind.USER || index == finalAnswerIndex) return@mapIndexedNotNull null
            if (item.kind == TimelineItem.Kind.ASSISTANT) item.copy(kind = TimelineItem.Kind.COMMENTARY) else item
        }
        val shouldGroup = work.any { it.kind.isWorkDetail() }

        result += users.map { it.copy(active = false) }
        if (shouldGroup) {
            result += activityGroup(
                turnId = turnId,
                status = status,
                durationMs = executionDurationMs,
                startedAtEpochMs = startedAtEpochMs,
                work = work,
                isRunning = executionIsRunning,
                assumeLatestActive = executionIsRunning && liveItems.isEmpty(),
            )
        } else {
            result += work.map { it.copy(active = false) }
        }
        finalAnswer?.let { result += it.copy(active = false) }
    }

    private fun activityGroup(
        turnId: String,
        status: String,
        durationMs: Long?,
        startedAtEpochMs: Long?,
        work: List<TimelineItem>,
        isRunning: Boolean,
        assumeLatestActive: Boolean,
    ): TimelineItem {
        val runningConcreteIndex = if (isRunning) {
            work.indexOfLast { it.kind.isConcreteWork() && (it.active || normalizedStatus(it.phase.orEmpty()) in RUNNING_STATUSES) }
        } else -1
        val tickerIndex = if (isRunning) {
            // A transient reasoning/commentary delta must never replace a real
            // command/tool that is currently running. Once concrete work exists,
            // keep the ticker semantic by showing the latest concrete action.
            runningConcreteIndex.takeIf { it >= 0 }
                ?: work.indexOfLast { it.kind.isConcreteWork() }.takeIf { it >= 0 }
                ?: work.indexOfLast { it.active || normalizedStatus(it.phase.orEmpty()) in RUNNING_STATUSES }
                    .let { if (it < 0 && assumeLatestActive) work.lastIndex else it }
        } else -1
        val selectedTickerIsRunning = tickerIndex >= 0 && (
            tickerIndex == runningConcreteIndex || work[tickerIndex].active ||
                normalizedStatus(work[tickerIndex].phase.orEmpty()) in RUNNING_STATUSES ||
                (assumeLatestActive && tickerIndex == work.lastIndex)
        )
        val turnWasCancelled = !isRunning && normalizedStatus(status) in setOf("cancelled", "canceled")
        val turnFailed = !isRunning && normalizedStatus(status) == "failed"
        val children = work.mapIndexed { index, child ->
            val childWasInterrupted = turnWasCancelled && (
                child.active || normalizedStatus(child.phase.orEmpty()) in RUNNING_STATUSES
            )
            val childFailedWithTurn = turnFailed && (
                child.active || normalizedStatus(child.phase.orEmpty()) in RUNNING_STATUSES
            )
            child.copy(
                label = if (childWasInterrupted && child.kind == TimelineItem.Kind.COMMAND) {
                    child.label.replaceFirst(Regex("^Running\\b"), "Command cancelled")
                } else if (childFailedWithTurn && child.kind == TimelineItem.Kind.COMMAND) {
                    child.label.replaceFirst(Regex("^Running\\b"), "Command failed")
                } else child.label,
                phase = when {
                    childWasInterrupted -> "cancelled"
                    childFailedWithTurn -> "failed"
                    else -> child.phase
                },
                active = index == tickerIndex && selectedTickerIsRunning,
            )
        }
        val concreteActions = children.filter { it.kind.isConcreteWork() }
        val completedActions = concreteActions.count {
            !it.active && normalizedStatus(it.phase.orEmpty()) !in RUNNING_STATUSES
        }
        val changedFiles = children.flatMap { it.changedFiles }.toSet()
        val fileDiffs = children.flatMap { it.fileDiffs.entries }.groupBy({ it.key }, { it.value })
            .mapValues { (_, values) -> values.reduce(FileDiffStat::plus) }
        val fileChanges = children.flatMap { it.fileChanges }.groupBy(FileDiffDetail::path).map { (path, values) ->
            val patches = values.map(FileDiffDetail::patch).filter(String::isNotBlank).distinct()
            FileDiffDetail(
                path = path,
                patch = patches.joinToString("\n"),
                kind = values.lastOrNull { it.kind.isNotBlank() }?.kind.orEmpty(),
                additions = values.sumOf(FileDiffDetail::additions),
                deletions = values.sumOf(FileDiffDetail::deletions),
                displayName = values.last().displayName,
            )
        }
        return TimelineItem(
            id = "turn-activity-$turnId",
            label = if (isRunning) "Working" else when (normalizedStatus(status)) {
                "failed" -> "Failed"
                "cancelled", "canceled" -> "Cancelled"
                else -> "Completed"
            },
            text = children.getOrNull(tickerIndex)?.let(::activeStepText).orEmpty(),
            kind = TimelineItem.Kind.ACTIVITY_GROUP,
            children = children,
            phase = if (!isRunning && normalizedStatus(status) in RUNNING_STATUSES) "completed" else normalizedStatus(status),
            active = isRunning,
            startedAtEpochMs = startedAtEpochMs,
            durationMs = durationMs,
            // The protocol does not provide a stable total. Reporting X/X while
            // events are still arriving is false progress, so expose only the
            // number of concrete actions observed/completed.
            stepCurrent = if (isRunning) concreteActions.size else completedActions.coerceAtLeast(concreteActions.size),
            stepTotal = 0,
            changedFiles = changedFiles,
            fileDiffs = fileDiffs,
            fileChanges = fileChanges,
            filesChanged = changedFiles.size,
            additions = children.sumOf { it.additions },
            deletions = children.sumOf { it.deletions },
        )
    }

    private fun activeStepText(item: TimelineItem): String = when (item.kind) {
        TimelineItem.Kind.COMMENTARY,
        TimelineItem.Kind.REASONING,
        TimelineItem.Kind.PLAN -> item.text.lineSequence().firstOrNull().orEmpty().trim().ifBlank { item.label }
        else -> item.label
    }

    private fun mergeTurnItems(
        snapshotItems: List<TimelineItem>,
        liveItems: List<TimelineItem>,
    ): List<TimelineItem> {
        if (liveItems.isEmpty()) return snapshotItems
        val merged = liveItems.toMutableList()
        snapshotItems.forEach { snapshotItem ->
            if (merged.any { liveItem -> sameProtocolItem(liveItem, snapshotItem) }) return@forEach
            when (snapshotItem.kind) {
                TimelineItem.Kind.USER -> merged.add(0, snapshotItem)
                else -> merged += snapshotItem
            }
        }
        return merged
    }

    private fun sameProtocolItem(first: TimelineItem, second: TimelineItem): Boolean {
        if (first.id == second.id) return true
        if (first.kind != second.kind && setOf(first.kind, second.kind) != setOf(
                TimelineItem.Kind.ASSISTANT,
                TimelineItem.Kind.COMMENTARY,
            )
        ) return false
        return first.phase == second.phase && first.text == second.text && first.text.isNotBlank()
    }

    private fun classifyAgentMessages(items: List<TimelineItem>): List<TimelineItem> {
        return items.map { item ->
            if (item.kind == TimelineItem.Kind.ASSISTANT && item.phase == "commentary") {
                item.copy(kind = TimelineItem.Kind.COMMENTARY)
            } else {
                item
            }
        }
    }

    /**
     * Some app-server versions expose a code-mode exec twice: once as the real
     * shell command and once as the JavaScript `tools.exec_command(...)`
     * transport wrapper. Keep the real action and hide only its wrapper twin;
     * repeated real commands remain visible because they may be intentional.
     */
    private fun compactEquivalentCommandWrappers(items: List<TimelineItem>): List<TimelineItem> {
        val realCommands = items.asSequence()
            .filter { it.kind == TimelineItem.Kind.COMMAND && !it.commandWrapper }
            .mapNotNull { it.rawCommand?.let(::commandIdentity)?.takeIf(String::isNotBlank) }
            .toSet()
        return items.filterNot { item ->
            item.kind == TimelineItem.Kind.COMMAND && (
                (item.rawCommand.isNullOrBlank() && item.text.isBlank()) ||
                    (item.commandWrapper && item.rawCommand?.let(::commandIdentity) in realCommands)
            )
        }
    }

    private fun commandIdentity(command: String): String = displayCommand(command)
        .trim()
        .replace(Regex("\\s+"), " ")

    private fun TimelineItem.Kind.isWorkDetail(): Boolean = this in setOf(
        TimelineItem.Kind.COMMENTARY,
        TimelineItem.Kind.REASONING,
        TimelineItem.Kind.PLAN,
        TimelineItem.Kind.COMMAND,
        TimelineItem.Kind.FILE_CHANGE,
        TimelineItem.Kind.TOOL,
    )

    private fun TimelineItem.Kind.isConcreteWork(): Boolean = this in setOf(
        TimelineItem.Kind.COMMAND,
        TimelineItem.Kind.FILE_CHANGE,
        TimelineItem.Kind.TOOL,
    )

    private fun turnDurationMs(turn: JSONObject): Long? {
        val direct = (turn.opt("durationMs") as? Number)?.toLong()?.takeIf { it >= 0L }
        if (direct != null) return direct
        val startedAt = (turn.opt("startedAt") as? Number)?.toLong()
        val completedAt = (turn.opt("completedAt") as? Number)?.toLong()
        if (startedAt != null && completedAt != null) {
            return ((completedAt - startedAt) * 1_000L).takeIf { it >= 0L }
        }
        val uuidStartedAt = uuidV7Timestamp(turn.optString("id")) ?: return null
        val uuidCompletedAt = turn.optJSONArray("items").objects()
            .mapNotNull { uuidV7Timestamp(it.optString("id")) }
            .maxOrNull()
            ?: return null
        return (uuidCompletedAt - uuidStartedAt).takeIf { it in 0L..604_800_000L }
    }

    private fun turnStartedAtMs(turn: JSONObject): Long? {
        val raw = (turn.opt("startedAt") as? Number)?.toLong()
        if (raw != null) return if (raw < 10_000_000_000L) raw * 1_000L else raw
        return uuidV7Timestamp(turn.optString("id"))
    }

    private fun uuidV7Timestamp(value: String): Long? {
        val compact = value.replace("-", "")
        if (compact.length != 32 || compact.getOrNull(12) != '7') return null
        return compact.take(12).toLongOrNull(16)
    }

    private fun formatDuration(durationMs: Long): String {
        val seconds = durationMs / 1_000L
        if (seconds < 1L) return "<1s"
        val hours = seconds / 3_600L
        val minutes = (seconds % 3_600L) / 60L
        val remainder = seconds % 60L
        return buildList {
            if (hours > 0L) add("${hours}h")
            if (minutes > 0L) add("${minutes}m")
            if (remainder > 0L || isEmpty()) add("${remainder}s")
        }.joinToString(" ")
    }

    private data class DiffStats(val additions: Int = 0, val deletions: Int = 0) {
        operator fun plus(other: DiffStats) = DiffStats(additions + other.additions, deletions + other.deletions)
    }

    private fun diffStats(change: JSONObject): DiffStats {
        val explicitAdditions = change.optInt("additions", -1)
        val explicitDeletions = change.optInt("deletions", -1)
        if (explicitAdditions >= 0 || explicitDeletions >= 0) {
            return DiffStats(explicitAdditions.coerceAtLeast(0), explicitDeletions.coerceAtLeast(0))
        }
        val patch = sequenceOf("diff", "patch", "content")
            .map(change::optString)
            .firstOrNull(String::isNotBlank)
            .orEmpty()
        val kind = change.optJSONObject("kind")?.optString("type").orEmpty()
        val contentLines = patch.lineSequence().count(String::isNotEmpty)
        if (kind == "add") return DiffStats(additions = contentLines)
        if (kind == "delete") return DiffStats(deletions = contentLines)
        return DiffStats(
            additions = patch.lineSequence().count { it.startsWith('+') && !it.startsWith("+++") },
            deletions = patch.lineSequence().count { it.startsWith('-') && !it.startsWith("---") },
        )
    }

    private fun displayPatch(patch: String, kind: String): String = when (kind.lowercase()) {
        "add" -> patch.trimEnd('\n', '\r').lineSequence().joinToString("\n") { line -> if (line.startsWith('+')) line else "+$line" }
        "delete" -> patch.trimEnd('\n', '\r').lineSequence().joinToString("\n") { line -> if (line.startsWith('-')) line else "-$line" }
        else -> patch
    }

    private fun commandLabel(status: String, command: String): String {
        val display = displayCommand(command)
        skillReadLabel(display)?.let { target ->
            return when (normalizedStatus(status)) {
                in RUNNING_STATUSES -> "Reading $target"
                "failed", "declined" -> "Read $target failed"
                else -> "Read $target"
            }
        }
        // Code-mode orchestration is an implementation detail, not a useful collapsed title.
        // Preserve rawCommand for explicit inspection, and keep ordinary shell labels intact.
        val orchestration = Regex("^(?:(?://[^\\n]*\\n)\\s*)*(?:const |let |var |await |text\\(|return )")
            .containsMatchIn(command.trim()) &&
            Regex("\\b(?:ALL_TOOLS|tools(?:\\.|\\[))").containsMatchIn(command)
        if (orchestration) return when (normalizedStatus(status)) {
            in RUNNING_STATUSES -> "Running tools"
            "failed", "declined" -> "Tools failed"
            else -> "Ran tools"
        }
        val firstLine = display.ifBlank { "commands" }
        return when (normalizedStatus(status)) {
            in RUNNING_STATUSES -> "Running $firstLine"
            "failed", "declined" -> if (firstLine == "commands") "Commands failed" else "Command failed $firstLine"
            else -> "Ran $firstLine"
        }
    }

    private fun semanticToolStyle(
        tool: String,
        label: String,
        fallback: TimelineItem.ToolStyle = TimelineItem.ToolStyle.GENERIC,
    ): TimelineItem.ToolStyle {
        val semantic = "$tool $label".lowercase()
        return when {
            semantic.contains("search") || semantic.contains("find") || semantic.contains("grep") -> TimelineItem.ToolStyle.SEARCH
            semantic.contains("read") || semantic.contains("open file") || semantic.contains("view file") -> TimelineItem.ToolStyle.READ
            semantic.contains("skill") || semantic.contains("loaded a tool") || semantic.contains("load tool") -> TimelineItem.ToolStyle.SKILL
            else -> fallback
        }
    }

    private fun semanticCommandStyle(command: String): TimelineItem.ToolStyle {
        val value = displayCommand(command).trimStart().lowercase()
        return when {
            skillReadLabel(value) != null -> TimelineItem.ToolStyle.SKILL
            value.startsWith("rg ") || value.startsWith("grep ") || value.startsWith("find ") -> TimelineItem.ToolStyle.SEARCH
            value.startsWith("cat ") || value.startsWith("sed ") || value.startsWith("head ") || value.startsWith("tail ") -> TimelineItem.ToolStyle.READ
            else -> TimelineItem.ToolStyle.GENERIC
        }
    }

    internal fun displayCommand(command: String): String {
        var value = canonicalCommand(command).trim()
        val wrappers = listOf(
            Regex("^(?:/usr/bin/env\\s+)?(?:/bin/|/usr/bin/)?(?:ba|z|fi)?sh\\s+-[a-zA-Z]*c\\s+"),
            Regex("^(?:cmd(?:\\.exe)?)\\s+/[cC]\\s+"),
            Regex("^(?:powershell(?:\\.exe)?|pwsh(?:\\.exe)?)\\s+-(?:Command|c)\\s+", RegexOption.IGNORE_CASE),
        )
        val shellWrapper = wrappers.firstOrNull { it.containsMatchIn(value) }
        shellWrapper?.let { value = value.replaceFirst(it, "").trim() }
        if (value.isNotEmpty() && value.first() in setOf('\'', '"')) {
            val quote = value.first()
            if (shellWrapper != null) {
                // Session logs can preserve a shell's mixed-quote transport form,
                // where the opening `bash -lc "` has no matching final quote in
                // the serialized command. It is transport syntax, not part of
                // the desktop activity title.
                value = value.substring(1)
                if (value.lastOrNull() == quote) value = value.dropLast(1)
            } else if (value.lastOrNull() == quote) {
                value = value.substring(1, value.lastIndex)
            }
        }
        return value.lineSequence().firstOrNull().orEmpty().trim().ifBlank { command.trim() }
    }

    internal fun canonicalCommand(command: String): String {
        val trimmed = command.trim()
        val marker = "tools.exec_command("
        val markerStart = trimmed.indexOf(marker)
        if (markerStart < 0) return trimmed
        val objectStart = trimmed.indexOf('{', markerStart + marker.length)
        if (objectStart < 0) return trimmed
        val objectEnd = balancedObjectEnd(trimmed, objectStart)
        if (objectEnd < 0) return trimmed
        val arguments = runCatching { JSONObject(trimmed.substring(objectStart, objectEnd + 1)) }.getOrNull()
            ?: return trimmed
        val nested = sequenceOf("cmd", "command")
            .map(arguments::optString)
            .firstOrNull(String::isNotBlank)
            ?.trim()
            .orEmpty()
        return nested.ifBlank { trimmed }
    }

    private fun balancedObjectEnd(value: String, start: Int): Int {
        var depth = 0
        var quote: Char? = null
        var escaped = false
        for (index in start until value.length) {
            val character = value[index]
            if (quote != null) {
                if (character == quote && !escaped) quote = null
                escaped = character == '\\' && !escaped
                if (character != '\\') escaped = false
                continue
            }
            if (character == '\'' || character == '"') {
                quote = character
                continue
            }
            when (character) {
                '{' -> depth += 1
                '}' -> {
                    depth -= 1
                    if (depth == 0) return index
                }
            }
        }
        return -1
    }

    private fun skillReadLabel(command: String): String? {
        val match = Regex("(?:^|\\s)(?:['\"])?([^\\s'\"]*/skills/([^/\\s'\"]+)/SKILL\\.md)(?:['\"])?", RegexOption.IGNORE_CASE)
            .find(command)
            ?: return null
        val skillName = match.groupValues[2]
            .split('-', '_')
            .filter(String::isNotBlank)
            .joinToString(" ") { part -> part.replaceFirstChar { it.uppercase() } }
        return "$skillName skill"
    }

    private fun fileChangeLabel(status: String, changes: List<JSONObject>): String {
        val paths = changes.map { it.optString("path") }.filter(String::isNotBlank).distinct()
        val target = when (paths.size) {
            0 -> ""
            1 -> changes.first().displayPath().trimEnd('/').substringAfterLast('/').ifBlank { "File" }
            else -> "${paths.size} files"
        }
        val kinds = changes.mapNotNull { change ->
            change.optJSONObject("kind")?.optString("type")?.takeIf(String::isNotBlank)
        }.distinct()
        val kind = kinds.singleOrNull()
        val running = normalizedStatus(status) in RUNNING_STATUSES
        val failed = normalizedStatus(status) in setOf("failed", "declined")
        val verb = when (kind) {
            "add" -> if (running) "Creating" else "Created"
            "delete" -> if (running) "Deleting" else "Deleted"
            "update" -> if (running) "Modifying" else "Modified"
            else -> if (running) "Modifying" else "Modified"
        }
        if (failed) {
            if (target.isBlank() || kind == null) return "File change failed"
            val failedAction = when (kind) {
                "add" -> "Create"
                "delete" -> "Delete"
                else -> "Modify"
            }
            return "$failedAction $target failed"
        }
        return if (target.isBlank()) "$verb files" else "$verb $target"
    }

    private fun toolLabel(tool: String, status: String): String = when (normalizedStatus(status)) {
        in RUNNING_STATUSES -> "Investigating"
        "failed" -> "${humanize(tool)} failed"
        else -> "Used ${humanize(tool)}"
    }

    private fun mcpToolLabel(item: JSONObject): String {
        val status = normalizedStatus(item.optString("status"))
        val appContext = item.optJSONObject("appContext")
        val appName = appContext?.let { jsonText(it, "appName") }.orEmpty()
        val actionName = appContext?.let { jsonText(it, "actionName") }.orEmpty()
        val argumentHint = listOf("query", "q", "pattern", "path").firstNotNullOfOrNull { key ->
            item.optJSONObject("arguments")?.let { arguments -> jsonText(arguments, key).takeIf(String::isNotBlank) }
        }
        if (appName.isNotBlank()) return when (status) {
            in RUNNING_STATUSES -> actionProgressLabel(actionName, argumentHint)
                ?: "Using $appName integration"
            "failed" -> "$appName integration failed"
            else -> if (actionName.contains("load", ignoreCase = true)) {
                "Used $appName integration, loaded a tool"
            } else {
                "Used $appName integration"
            }
        }
        val invocation = listOf(item.optString("server"), item.optString("tool"))
            .filter(String::isNotBlank)
            .joinToString(".")
            .ifBlank { "tool" }
        return when (status) {
            in RUNNING_STATUSES -> "Calling $invocation"
            "failed" -> "$invocation failed"
            else -> "Called $invocation"
        }
    }

    private fun actionProgressLabel(actionName: String, hint: String?): String? {
        if (actionName.isBlank()) return null
        val action = actionName.trim()
        val progress = when {
            action.startsWith("search", true) -> "Searching" + action.drop(6)
            action.startsWith("find", true) -> "Finding" + action.drop(4)
            action.startsWith("get", true) -> "Getting" + action.drop(3)
            action.startsWith("read", true) -> "Reading" + action.drop(4)
            action.startsWith("open", true) -> "Opening" + action.drop(4)
            action.startsWith("load", true) -> "Loading" + action.drop(4)
            else -> return null
        }
        return if (hint.isNullOrBlank()) progress else "$progress \"$hint\""
    }

    private fun webSearchLabel(item: JSONObject): String {
        val query = item.optString("query").ifBlank {
            item.optJSONObject("action")?.optString("query").orEmpty()
        }
        val domain = buildList {
            item.optJSONObject("action")?.optString("url")?.let(::add)
            item.optJSONArray("results").objects().mapNotNullTo(this) { result ->
                jsonText(result, "url").takeIf(String::isNotBlank)
            }
        }.firstNotNullOfOrNull(::urlHost)
        return buildString {
            append("Searched the web")
            if (query.isNotBlank()) append(" for ").append(query)
            if (domain != null) append(" | ").append(domain)
        }
    }

    private fun urlHost(value: String): String? = Regex("^https?://([^/]+)", RegexOption.IGNORE_CASE)
        .find(value)
        ?.groupValues
        ?.getOrNull(1)
        ?.removePrefix("www.")

    private fun isCommandTool(tool: String): Boolean {
        val normalized = tool.substringAfterLast('.').lowercase()
        return normalized in setOf("exec", "exec_command", "write_stdin", "shell", "terminal")
    }

    private fun dynamicCommandLabel(
        status: String,
        tool: String,
        arguments: JSONObject?,
    ): String {
        val command = dynamicCommand(arguments) ?: humanize(tool)
        return commandLabel(status, command)
    }

    private fun dynamicCommand(arguments: JSONObject?): String? = arguments
        ?.optString("cmd")
        .orEmpty()
        .ifBlank { arguments?.optString("command").orEmpty() }
        .takeIf(String::isNotBlank)

    private fun toolCallDetail(item: JSONObject): String {
        val error = item.opt("error")?.takeUnless { it == JSONObject.NULL }
        if (error != null) return when (error) {
            is JSONObject -> jsonText(error, "message").ifBlank { error.toString(2) }
            else -> error.toString()
        }
        val result = item.optJSONObject("result")
        if (result != null) {
            val contentText = result.optJSONArray("content").objects().mapNotNull { block ->
                jsonText(block, "text").ifBlank {
                    block.optJSONObject("resource")?.let { resource -> jsonText(resource, "uri") }.orEmpty()
                }.takeIf(String::isNotBlank)
            }.joinToString("\n")
            if (contentText.isNotBlank()) return contentText
            val structured = result.opt("structuredContent")?.takeUnless { it == JSONObject.NULL }
            if (structured != null) return structured.toString()
            if (result.length() > 0) return result.toString(2)
        }
        val contentItems = item.optJSONArray("contentItems").objects().mapNotNull { block ->
            jsonText(block, "text").takeIf(String::isNotBlank)
        }.joinToString("\n")
        if (contentItems.isNotBlank()) return contentItems
        return item.opt("arguments")?.takeUnless { it == JSONObject.NULL }?.toString().orEmpty()
    }

    private fun humanize(value: String): String = value
        .substringAfterLast('.')
        .replace(Regex("([a-z])([A-Z])"), "$1 $2")
        .replace('_', ' ')
        .trim()
        .ifBlank { "tool" }

    private fun normalizedStatus(value: String): String = value.lowercase().replace("_", "").replace("-", "")

    private fun messageAttachment(item: JSONObject): MessageAttachment? {
        val metadata = item.optJSONObject("remoteAttachment") ?: run {
            if (item.optString("type") != "localImage") return null
            val path = item.optString("path")
            val match = Regex("remote-attachment://([a-f0-9-]{36})-(.+)").matchEntire(path) ?: return null
            JSONObject().put("id", match.groupValues[1]).put("name", match.groupValues[2]).put("kind", "image")
        }
        val id = metadata.optString("id")
        val name = metadata.optString("name")
        if (!Regex("[a-f0-9-]{36}").matches(id) || name.isBlank() || name.length > 255 || name.any { it == '/' || it == '\\' || it < ' ' }) return null
        return MessageAttachment(id, name, metadata.optString("kind"))
    }

    private fun userContent(content: JSONArray?): String = content.objects()
        .mapNotNull { item ->
            item.optString("text")
                .ifBlank { item.optString("url") }
                .trim()
                .takeIf(String::isNotBlank)
        }
        .joinToString("\n")

    private fun stringArray(array: JSONArray?): String = buildList {
        if (array != null) for (index in 0 until array.length()) add(array.optString(index))
    }.filter(String::isNotBlank).joinToString("\n")

    private fun statusText(value: Any?): String = when (value) {
        is String -> value
        is JSONObject -> value.optString("type").ifBlank { value.keys().asSequence().firstOrNull().orEmpty() }
        else -> ""
    }

    private fun epochSeconds(value: Any?): Long? = when (value) {
        is Number -> value.toLong().takeIf { it > 0L }
        is String -> value.toLongOrNull()?.takeIf { it > 0L }
        else -> null
    }

    private fun jsonText(value: JSONObject, key: String): String =
        if (value.isNull(key)) "" else value.optString(key).takeUnless { it == "null" }.orEmpty()

    private fun JSONArray?.objects(): List<JSONObject> = buildList {
        if (this@objects != null) for (index in 0 until length()) optJSONObject(index)?.let(::add)
    }

    private val RUNNING_STATUSES = setOf("active", "running", "inprogress", "started", "pending")
}
