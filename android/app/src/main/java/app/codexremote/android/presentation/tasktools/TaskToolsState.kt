package app.codexremote.android.presentation.tasktools

import androidx.compose.runtime.Immutable

@Immutable
data class TaskToolsScope(
    val serverId: String,
    val deviceId: String,
    val taskId: String,
    val taskName: String = "",
    val archived: Boolean = false,
)

@Immutable
data class FeatureAvailability(
    val readable: Boolean = false,
    val writable: Boolean = false,
    val unavailableReason: String? = "This host does not advertise this feature.",
)

@Immutable
data class TaskToolsAvailability(
    val rename: FeatureAvailability = FeatureAvailability(),
    val fork: FeatureAvailability = FeatureAvailability(),
    val archive: FeatureAvailability = FeatureAvailability(),
    val delete: FeatureAvailability = FeatureAvailability(),
    val search: FeatureAvailability = FeatureAvailability(),
    val accountUsage: FeatureAvailability = FeatureAvailability(),
    val compact: FeatureAvailability = FeatureAvailability(),
    val review: FeatureAvailability = FeatureAvailability(),
    val goal: FeatureAvailability = FeatureAvailability(),
    val history: FeatureAvailability = FeatureAvailability(),
    val descendants: FeatureAvailability = FeatureAvailability(),
)

enum class TaskAction { RENAME, FORK, ARCHIVE, UNARCHIVE, DELETE, COMPACT, START_REVIEW, SET_GOAL, CLEAR_GOAL }

@Immutable
data class TaskActionStatus(
    val running: TaskAction? = null,
    val completed: TaskAction? = null,
    val message: String? = null,
    val error: String? = null,
)

@Immutable
data class TaskSearchMatch(
    val threadId: String,
    val title: String,
    val snippet: String,
    val archived: Boolean = false,
    val updatedAt: Long? = null,
)

@Immutable
data class TaskSearchOccurrence(
    val turnId: String,
    val itemId: String,
    val snippet: String,
    val matchStart: Int,
    val matchEnd: Int,
    val turnCursor: String,
)

@Immutable
data class TaskSearchState(
    val query: String = "",
    val matches: List<TaskSearchMatch> = emptyList(),
    val occurrences: List<TaskSearchOccurrence> = emptyList(),
    val nextCursor: String? = null,
    val nextOccurrenceCursor: String? = null,
    val loading: Boolean = false,
    val loadingMore: Boolean = false,
    val error: String? = null,
)

@Immutable
data class RateLimitWindow(
    val usedPercent: Double,
    val windowDurationMinutes: Long?,
    val resetsAtEpochSeconds: Long?,
)

@Immutable
data class AccountRateLimit(
    val id: String?,
    val name: String?,
    val primary: RateLimitWindow?,
    val secondary: RateLimitWindow?,
    val planType: String?,
    val reachedType: String?,
    val credits: AccountCredits? = null,
    val spendControl: SpendControl? = null,
    val spendControlReached: Boolean? = null,
)

@Immutable
data class AccountCredits(val hasCredits: Boolean, val unlimited: Boolean, val balance: String?)

@Immutable
data class SpendControl(
    val limit: String,
    val used: String,
    val remainingPercent: Double,
    val resetsAtEpochSeconds: Long,
)

@Immutable
data class RateLimitResetCredit(
    val id: String,
    val resetType: String,
    val status: String,
    val grantedAtEpochSeconds: Long,
    val expiresAtEpochSeconds: Long?,
    val title: String?,
    val description: String?,
)

@Immutable
data class DailyTokenUsage(val startDate: String, val tokens: Long)

@Immutable
data class AccountUsage(
    val lifetimeTokens: Long? = null,
    val peakDailyTokens: Long? = null,
    val longestRunningTurnSeconds: Long? = null,
    val currentStreakDays: Long? = null,
    val longestStreakDays: Long? = null,
    val daily: List<DailyTokenUsage> = emptyList(),
)

@Immutable
data class AccountUsageState(
    val rateLimits: List<AccountRateLimit> = emptyList(),
    val usage: AccountUsage? = null,
    val availableResetCredits: Long? = null,
    val resetCredits: List<RateLimitResetCredit>? = null,
    val loading: Boolean = false,
    val error: String? = null,
)

enum class GoalStatus(val wireValue: String) {
    ACTIVE("active"), PAUSED("paused"), BLOCKED("blocked"), USAGE_LIMITED("usageLimited"),
    BUDGET_LIMITED("budgetLimited"), COMPLETE("complete");

    companion object { fun fromWire(value: String) = entries.firstOrNull { it.wireValue == value } }
}

@Immutable
data class TaskGoal(
    val objective: String,
    val status: GoalStatus,
    val tokenBudget: Long?,
    val tokensUsed: Long,
    val timeUsedSeconds: Long,
    val createdAtEpochSeconds: Long?,
    val updatedAtEpochSeconds: Long?,
)

@Immutable
data class GoalState(val goal: TaskGoal? = null, val loading: Boolean = false, val error: String? = null)

sealed interface ReviewTarget {
    data object UncommittedChanges : ReviewTarget
    data class BaseBranch(val branch: String) : ReviewTarget
    data class Commit(val sha: String, val title: String? = null) : ReviewTarget
    data class Custom(val instructions: String) : ReviewTarget
}

enum class ReviewDelivery(val wireValue: String) { INLINE("inline"), DETACHED("detached") }

@Immutable
data class ReviewState(
    val reviewThreadId: String? = null,
    val delivery: ReviewDelivery? = null,
    val error: String? = null,
)

@Immutable
data class HistoryEntry(
    val id: String,
    val turnId: String? = null,
    val type: String,
    val title: String,
    val summary: String = "",
)

@Immutable
data class HistoryState(
    val entries: List<HistoryEntry> = emptyList(),
    val nextCursor: String? = null,
    val loading: Boolean = false,
    val loadingMore: Boolean = false,
    val error: String? = null,
)

@Immutable
data class DescendantTask(
    val id: String,
    val title: String,
    val status: String,
    val parentThreadId: String?,
    val updatedAt: Long?,
    val canAcceptDirectInput: Boolean?,
    val agentNickname: String? = null,
    val agentRole: String? = null,
)

@Immutable
data class DescendantsState(
    val tasks: List<DescendantTask> = emptyList(),
    val selectedTaskId: String? = null,
    val nextCursor: String? = null,
    val loading: Boolean = false,
    val loadingMore: Boolean = false,
    val error: String? = null,
)

@Immutable
data class TaskToolsUiState(
    val scope: TaskToolsScope? = null,
    val availability: TaskToolsAvailability = TaskToolsAvailability(),
    val action: TaskActionStatus = TaskActionStatus(),
    val search: TaskSearchState = TaskSearchState(),
    val account: AccountUsageState = AccountUsageState(),
    val goal: GoalState = GoalState(),
    val review: ReviewState = ReviewState(),
    val history: HistoryState = HistoryState(),
    val descendants: DescendantsState = DescendantsState(),
)
