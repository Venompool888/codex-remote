package app.codexremote.android.presentation.realtime

import androidx.compose.runtime.State
import androidx.compose.runtime.mutableStateOf
import app.codexremote.android.AndroidRealtimeAudioEngine
import app.codexremote.android.RealtimeAudioIo
import app.codexremote.android.RealtimePcmChunk
import org.json.JSONObject
import java.util.Base64

/** Thread-scoped realtime state. Starting a session never starts microphone capture. */
class RealtimeController(
    private val rpc: (method: String, params: JSONObject, callback: (JSONObject?, String?) -> Unit) -> Unit,
    private val audioEngineFactory: () -> RealtimeAudioIo = { AndroidRealtimeAudioEngine() },
    private val requestRecordPermission: (callback: (Boolean) -> Unit) -> Unit = { it(false) },
    private val dispatch: (() -> Unit) -> Unit = { it() },
    private val onSessionChanged: (active: Boolean) -> Unit = {},
) {
    private val _uiState = mutableStateOf(RealtimeUiState())
    val uiState: State<RealtimeUiState> = _uiState

    private var advertisedMethods: Set<String> = emptySet()
    private var generation = 0L
    private var requestSequence = 0L
    private val activeRequests = mutableMapOf<String, RequestToken>()
    private var audioEngine: RealtimeAudioIo? = null
    private var audioEpoch = 0L
    private var audioInFlight = false
    private val pendingAudio = ArrayDeque<RealtimePcmChunk>()
    private var nextTranscriptId = 1L
    private var retryAction: (() -> Unit)? = null

    fun setScope(scope: RealtimeScope?, advertisedRpcMethods: Set<String>) {
        val previous = _uiState.value.scope
        val sameIdentity = previous == scope
        if (sameIdentity && advertisedMethods == advertisedRpcMethods) return
        if (previous != null && isSessionOpen()) stopTransport(previous.taskId)
        generation++
        activeRequests.clear()
        releaseAudio()
        pendingAudio.clear()
        audioInFlight = false
        advertisedMethods = advertisedRpcMethods.toSet()
        val availability = availability(scope != null)
        _uiState.value = RealtimeUiState(
            scope = scope,
            availability = availability,
            phase = when { scope == null || !availability.start -> RealtimePhase.UNAVAILABLE; else -> RealtimePhase.IDLE },
        )
        retryAction = null
        onSessionChanged(false)
    }

    fun refreshVoices() {
        if (!available("thread/realtime/listVoices")) return fail("Voice selection is unavailable on this host.")
        val request = token("voices")
        _uiState.value = _uiState.value.copy(loadingVoices = true, error = null)
        rpc("thread/realtime/listVoices", JSONObject()) { result, error ->
            if (!current(request)) return@rpc
            if (error != null || result == null) {
                _uiState.value = _uiState.value.copy(loadingVoices = false, error = error ?: "Could not load realtime voices.")
                retryAction = ::refreshVoices
                return@rpc
            }
            val voices = result.optJSONObject("voices") ?: JSONObject()
            _uiState.value = _uiState.value.copy(
                loadingVoices = false,
                voices = RealtimeVoices(
                    v1 = voices.strings("v1"),
                    v2 = voices.strings("v2"),
                    defaultV1 = voices.string("defaultV1"),
                    defaultV2 = voices.string("defaultV2"),
                ),
            )
        }
    }

    fun startSession(options: RealtimeStartOptions = RealtimeStartOptions()) {
        val scope = _uiState.value.scope ?: return fail("Select a task first.")
        if (!available("thread/realtime/start")) return fail("Realtime conversations are unavailable on this host.")
        if (_uiState.value.phase in setOf(RealtimePhase.STARTING, RealtimePhase.ACTIVE, RealtimePhase.STOPPING)) return
        if (options.voice != null && !VOICE_PATTERN.matches(options.voice)) return fail("Choose a voice advertised by the host.")
        val request = token("session")
        audioEpoch++
        _uiState.value = _uiState.value.copy(
            phase = RealtimePhase.STARTING, realtimeSessionId = null, version = null,
            transcripts = emptyList(), lastItemSummary = null, error = null, closeReason = null,
        )
        val params = JSONObject()
            .put("threadId", scope.taskId)
            .put("outputModality", options.outputModality.wireValue)
            .put("transport", JSONObject().put("type", "websocket"))
        options.voice?.let { params.put("voice", it) }
        rpc("thread/realtime/start", params) { result, error ->
            if (!current(request)) return@rpc
            if (error != null || result == null) {
                _uiState.value = _uiState.value.copy(phase = RealtimePhase.ERROR, error = error ?: "Could not start realtime conversation.")
                retryAction = { startSession(options) }
            }
        }
    }

    fun startMicrophone() {
        val scope = _uiState.value.scope ?: return
        if (_uiState.value.phase != RealtimePhase.ACTIVE) return fail("Start a realtime conversation before using the microphone.")
        if (!available("thread/realtime/appendAudio")) return fail("Microphone input is unavailable on this host.")
        if (_uiState.value.recording) return
        val capturedGeneration = generation
        val capturedAudioEpoch = audioEpoch
        requestRecordPermission { granted -> dispatch {
            if (capturedGeneration != generation || capturedAudioEpoch != audioEpoch || _uiState.value.scope != scope || _uiState.value.phase != RealtimePhase.ACTIVE) return@dispatch
            if (!granted) return@dispatch fail("Microphone permission is required for voice input.")
            val engine = audioEngine ?: audioEngineFactory().also { audioEngine = it }
            val started = engine.startCapture(
                onChunk = { chunk -> dispatch { enqueueAudio(chunk, capturedGeneration, capturedAudioEpoch) } },
                onError = { message -> dispatch { audioFailure(message, capturedGeneration, capturedAudioEpoch) } },
            )
            _uiState.value = _uiState.value.copy(recording = started, error = if (started) null else _uiState.value.error)
        } }
    }

    fun stopMicrophone() {
        audioEngine?.stopCapture()
        pendingAudio.clear()
        _uiState.value = _uiState.value.copy(recording = false)
    }

    fun appendText(role: RealtimeTextRole, text: String) {
        if (role != RealtimeTextRole.USER) return fail("Remote realtime input must be a user message.")
        val value = text.trim()
        if (value.isEmpty()) return
        if (value.length > 16_000) return fail("Realtime messages must contain at most 16000 characters.")
        sendInput("thread/realtime/appendText", JSONObject().put("text", value).put("role", role.wireValue))
    }

    fun appendSpeech(text: String) {
        val value = text.trim()
        if (value.isEmpty()) return
        if (value.length > 16_000) return fail("Realtime speech must contain at most 16000 characters.")
        sendInput("thread/realtime/appendSpeech", JSONObject().put("text", value))
    }

    private fun sendInput(method: String, values: JSONObject) {
        val scope = _uiState.value.scope ?: return
        if (_uiState.value.phase != RealtimePhase.ACTIVE) return fail("The realtime conversation is not active.")
        if (!available(method)) return fail("This realtime input is unavailable on this host.")
        val request = token("input")
        values.put("threadId", scope.taskId)
        rpc(method, values) { result, error ->
            if (!current(request)) return@rpc
            if (error != null || result == null) {
                _uiState.value = _uiState.value.copy(error = error ?: "Could not send realtime input.")
                retryAction = { sendInput(method, JSONObject(values.toString()).apply { remove("threadId") }) }
            }
        }
    }

    fun stopSession() {
        val scope = _uiState.value.scope ?: return
        if (_uiState.value.phase == RealtimePhase.STOPPING) return
        if (!isSessionOpen()) {
            releaseAudio()
            return
        }
        releaseAudio()
        pendingAudio.clear()
        if (!available("thread/realtime/stop")) {
            _uiState.value = _uiState.value.copy(phase = RealtimePhase.CLOSED, error = "This host cannot stop realtime sessions remotely.")
            onSessionChanged(false)
            return
        }
        val request = token("session")
        _uiState.value = _uiState.value.copy(phase = RealtimePhase.STOPPING, recording = false, error = null)
        rpc("thread/realtime/stop", JSONObject().put("threadId", scope.taskId)) { result, error ->
            if (!current(request)) return@rpc
            if (error != null || result == null) {
                _uiState.value = _uiState.value.copy(phase = RealtimePhase.ERROR, error = error ?: "Could not stop realtime conversation.")
                retryAction = ::stopSession
            } else {
                _uiState.value = _uiState.value.copy(phase = RealtimePhase.CLOSED)
                onSessionChanged(false)
            }
        }
    }

    fun onPause() {
        if (isSessionOpen()) stopSession() else releaseAudio()
    }

    fun retry() { retryAction?.invoke() }

    fun consumeEvent(method: String, params: JSONObject): Boolean {
        val scope = _uiState.value.scope ?: return false
        if (params.string("threadId") != scope.taskId) return false
        return when (method) {
            "thread/realtime/started" -> {
                if (_uiState.value.phase != RealtimePhase.STARTING) return false
                val version = RealtimeVersion.entries.firstOrNull { it.wireValue == params.optString("version") }
                _uiState.value = _uiState.value.copy(
                    phase = RealtimePhase.ACTIVE,
                    realtimeSessionId = params.string("realtimeSessionId"),
                    version = version,
                    error = null,
                    closeReason = null,
                )
                retryAction = null
                onSessionChanged(true)
                true
            }
            "thread/realtime/transcript/delta" -> {
                addTranscript(params.optString("role"), params.optString("delta"), final = false)
                true
            }
            "thread/realtime/transcript/done" -> {
                addTranscript(params.optString("role"), params.optString("text"), final = true)
                true
            }
            "thread/realtime/outputAudio/delta" -> {
                if (_uiState.value.phase != RealtimePhase.ACTIVE) return false
                val audio = params.optJSONObject("audio") ?: return false
                val chunk = decodeAudio(audio) ?: return true
                val capturedGeneration = generation
                val capturedAudioEpoch = audioEpoch
                val engine = audioEngine ?: audioEngineFactory().also { audioEngine = it }
                engine.play(chunk) { message -> dispatch { audioFailure(message, capturedGeneration, capturedAudioEpoch, stopCapture = false) } }
                true
            }
            "thread/realtime/itemAdded" -> {
                val item = params.optJSONObject("item")
                _uiState.value = _uiState.value.copy(lastItemSummary = item?.let(::itemSummary) ?: "Realtime item received")
                true
            }
            "thread/realtime/error" -> {
                releaseAudio()
                _uiState.value = _uiState.value.copy(phase = RealtimePhase.ERROR, recording = false, error = params.optString("message").ifBlank { "Realtime conversation failed." })
                onSessionChanged(false)
                true
            }
            "thread/realtime/closed" -> {
                releaseAudio()
                _uiState.value = _uiState.value.copy(
                    phase = RealtimePhase.CLOSED, recording = false,
                    closeReason = params.string("reason"), realtimeSessionId = null,
                )
                onSessionChanged(false)
                true
            }
            "thread/realtime/sdp" -> {
                fail("The host attempted WebRTC, but this client requested websocket transport.")
                true
            }
            else -> false
        }
    }

    private fun enqueueAudio(chunk: RealtimePcmChunk, capturedGeneration: Long, capturedAudioEpoch: Long) {
        if (capturedGeneration != generation || capturedAudioEpoch != audioEpoch || !_uiState.value.recording || _uiState.value.phase != RealtimePhase.ACTIVE) return
        if (!validInputAudio(chunk)) return audioFailure("Microphone produced an unsupported audio chunk.", capturedGeneration, capturedAudioEpoch)
        if (audioInFlight) {
            if (pendingAudio.size == MAX_PENDING_AUDIO_CHUNKS) pendingAudio.removeFirst()
            pendingAudio.addLast(chunk.copy(data = chunk.data.copyOf()))
            return
        }
        sendAudio(chunk, capturedGeneration, capturedAudioEpoch)
    }

    private fun sendAudio(chunk: RealtimePcmChunk, capturedGeneration: Long, capturedAudioEpoch: Long) {
        val scope = _uiState.value.scope ?: return
        audioInFlight = true
        val audio = JSONObject()
            .put("data", Base64.getEncoder().encodeToString(chunk.data))
            .put("sampleRate", chunk.sampleRate)
            .put("numChannels", chunk.channelCount)
            .put("samplesPerChannel", chunk.samplesPerChannel)
            .put("itemId", chunk.itemId ?: JSONObject.NULL)
        rpc("thread/realtime/appendAudio", JSONObject().put("threadId", scope.taskId).put("audio", audio)) { result, error ->
            dispatch {
                if (capturedGeneration != generation || capturedAudioEpoch != audioEpoch || _uiState.value.scope != scope) return@dispatch
                audioInFlight = false
                if (error != null || result == null) return@dispatch audioFailure(error ?: "Could not send microphone audio.", capturedGeneration, capturedAudioEpoch)
                pendingAudio.removeFirstOrNull()?.let { sendAudio(it, capturedGeneration, capturedAudioEpoch) }
            }
        }
    }

    private fun decodeAudio(value: JSONObject): RealtimePcmChunk? {
        val encoded = value.optString("data")
        if (encoded.isBlank() || encoded.length > MAX_ENCODED_AUDIO_CHARS) return failAudio("The host sent an invalid audio chunk.")
        val bytes = runCatching { Base64.getDecoder().decode(encoded) }.getOrNull() ?: return failAudio("The host sent invalid audio data.")
        val channels = value.optInt("numChannels")
        val rate = value.optInt("sampleRate")
        val samples = if (value.has("samplesPerChannel") && !value.isNull("samplesPerChannel")) value.optInt("samplesPerChannel")
            else if (channels > 0) bytes.size / PCM_BYTES / channels else 0
        val chunk = RealtimePcmChunk(bytes, rate, channels, samples, value.string("itemId"))
        return if (validAudio(chunk)) chunk else failAudio("The host sent an unsupported audio format.")
    }

    private fun validAudio(chunk: RealtimePcmChunk): Boolean = chunk.data.isNotEmpty() &&
        chunk.data.size <= AndroidRealtimeAudioEngine.MAX_CHUNK_BYTES && chunk.sampleRate in 8_000..96_000 &&
        chunk.channelCount in 1..2 && chunk.samplesPerChannel > 0 &&
        chunk.samplesPerChannel * chunk.channelCount * PCM_BYTES == chunk.data.size

    private fun validInputAudio(chunk: RealtimePcmChunk): Boolean = validAudio(chunk) && chunk.channelCount == 1

    private fun failAudio(message: String): RealtimePcmChunk? {
        _uiState.value = _uiState.value.copy(error = message)
        return null
    }

    private fun audioFailure(message: String, capturedGeneration: Long, capturedAudioEpoch: Long, stopCapture: Boolean = true) {
        if (capturedGeneration != generation || capturedAudioEpoch != audioEpoch) return
        if (stopCapture) stopMicrophone()
        _uiState.value = _uiState.value.copy(error = message)
    }

    private fun addTranscript(role: String, text: String, final: Boolean) {
        if (text.isEmpty()) return
        val rows = _uiState.value.transcripts.toMutableList()
        val last = rows.lastOrNull()
        if (last != null && !last.final && last.role == role) {
            rows[rows.lastIndex] = last.copy(text = if (final) text else last.text + text, final = final)
        } else rows += RealtimeTranscript(nextTranscriptId++, role, text, final)
        _uiState.value = _uiState.value.copy(transcripts = rows.takeLast(MAX_TRANSCRIPTS))
    }

    private fun itemSummary(item: JSONObject): String {
        val type = item.optString("type").ifBlank { "item" }
        val text = sequenceOf("text", "transcript", "message").mapNotNull { item.string(it) }.firstOrNull()
        return if (text == null) type else "$type · ${text.replace(Regex("\\s+"), " ").take(160)}"
    }

    private fun stopTransport(taskId: String) {
        if (!available("thread/realtime/stop")) return
        rpc("thread/realtime/stop", JSONObject().put("threadId", taskId)) { _, _ -> }
    }

    private fun releaseAudio() {
        audioEpoch++
        audioEngine?.release()
        audioEngine = null
        audioInFlight = false
        pendingAudio.clear()
        _uiState.value = _uiState.value.copy(recording = false)
    }

    private fun isSessionOpen() = _uiState.value.phase in setOf(RealtimePhase.STARTING, RealtimePhase.ACTIVE, RealtimePhase.STOPPING)

    private fun availability(hasScope: Boolean): RealtimeAvailability {
        fun has(method: String) = hasScope && method in advertisedMethods
        val any = advertisedMethods.any { it.startsWith("thread/realtime/") }
        return RealtimeAvailability(
            listVoices = has("thread/realtime/listVoices"), start = has("thread/realtime/start"),
            appendAudio = has("thread/realtime/appendAudio"), appendText = has("thread/realtime/appendText"),
            appendSpeech = has("thread/realtime/appendSpeech"), stop = has("thread/realtime/stop"),
            reason = when { !hasScope -> "Select a task first."; !any -> "This host does not advertise realtime conversations."; else -> null },
        )
    }

    private fun available(method: String) = _uiState.value.scope != null && method in advertisedMethods
    private fun token(channel: String) = RequestToken(generation, ++requestSequence, channel).also { activeRequests[channel] = it }
    private fun current(token: RequestToken) = token.generation == generation && activeRequests[token.channel] == token && _uiState.value.scope != null
    private fun fail(message: String) { _uiState.value = _uiState.value.copy(error = message) }
    private data class RequestToken(val generation: Long, val sequence: Long, val channel: String)

    companion object {
        private const val MAX_PENDING_AUDIO_CHUNKS = 4
        private const val MAX_TRANSCRIPTS = 200
        private const val PCM_BYTES = 2
        private const val MAX_ENCODED_AUDIO_CHARS = 90_000
        private val VOICE_PATTERN = Regex("^[A-Za-z0-9_-]{1,40}$")

        private fun JSONObject.string(key: String): String? = optString(key).takeIf { has(key) && !isNull(key) && it.isNotBlank() && it != "null" }
        private fun JSONObject.strings(key: String): List<String> {
            val values = optJSONArray(key) ?: return emptyList()
            return (0 until values.length()).mapNotNull { values.optString(it).takeIf(String::isNotBlank) }
        }
    }
}
