package app.codexremote.android.presentation.realtime

import androidx.compose.runtime.Immutable

@Immutable
data class RealtimeScope(val serverId: String, val deviceId: String, val taskId: String)

@Immutable
data class RealtimeAvailability(
    val listVoices: Boolean = false,
    val start: Boolean = false,
    val appendAudio: Boolean = false,
    val appendText: Boolean = false,
    val appendSpeech: Boolean = false,
    val stop: Boolean = false,
    val reason: String? = "This host does not advertise realtime conversations.",
)

enum class RealtimePhase { UNAVAILABLE, IDLE, STARTING, ACTIVE, STOPPING, CLOSED, ERROR }
enum class RealtimeVersion(val wireValue: String) { V1("v1"), V2("v2"), V3("v3") }
enum class RealtimeOutputModality(val wireValue: String) { TEXT("text"), AUDIO("audio") }
enum class RealtimeTextRole(val wireValue: String) { USER("user"), DEVELOPER("developer"), ASSISTANT("assistant") }

@Immutable
data class RealtimeVoices(
    val v1: List<String> = emptyList(),
    val v2: List<String> = emptyList(),
    val defaultV1: String? = null,
    val defaultV2: String? = null,
)

@Immutable
data class RealtimeStartOptions(
    val outputModality: RealtimeOutputModality = RealtimeOutputModality.AUDIO,
    val voice: String? = null,
)

@Immutable
data class RealtimeTranscript(
    val id: Long,
    val role: String,
    val text: String,
    val final: Boolean,
)

@Immutable
data class RealtimeUiState(
    val scope: RealtimeScope? = null,
    val availability: RealtimeAvailability = RealtimeAvailability(),
    val phase: RealtimePhase = RealtimePhase.UNAVAILABLE,
    val voices: RealtimeVoices = RealtimeVoices(),
    val loadingVoices: Boolean = false,
    val realtimeSessionId: String? = null,
    val version: RealtimeVersion? = null,
    val recording: Boolean = false,
    val transcripts: List<RealtimeTranscript> = emptyList(),
    val lastItemSummary: String? = null,
    val error: String? = null,
    val closeReason: String? = null,
)
