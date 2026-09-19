package app.codexremote.android.ui.conversation

import java.util.Locale

/** Filters only the state reported by the host; idle is not proof of completion. */
internal enum class SubagentStatusFilter(val label: String) {
    ALL("All"),
    RUNNING("Running"),
    NEEDS_ATTENTION("Needs attention"),
    COMPLETED("Completed");

    fun matches(rawStatus: String): Boolean {
        val status = rawStatus.trim().lowercase(Locale.US)
        return when (this) {
            ALL -> true
            RUNNING -> status in setOf("active", "running", "pendinginit", "inprogress")
            NEEDS_ATTENTION -> status in setOf(
                "systemerror", "errored", "failed", "interrupted", "notfound",
                "cancelled", "canceled", "waitingforapproval", "waitingforinput",
            )
            COMPLETED -> status == "completed"
        }
    }
}
