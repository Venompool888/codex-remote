package app.codexremote.android.presentation.realtime

import app.codexremote.android.RealtimeAudioIo
import app.codexremote.android.RealtimePcmChunk
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.util.Base64

class RealtimeControllerTest {
    private data class Call(val method: String, val params: JSONObject, val done: (JSONObject?, String?) -> Unit)
    private val calls = mutableListOf<Call>()
    private val audio = FakeAudio()
    private var permissionRequest: ((Boolean) -> Unit)? = null
    private val sessionChanges = mutableListOf<Boolean>()
    private fun controller() = RealtimeController(
        rpc = { method, params, done -> calls += Call(method, params, done) },
        audioEngineFactory = { audio },
        requestRecordPermission = { permissionRequest = it },
        onSessionChanged = sessionChanges::add,
    )
    private val scope = RealtimeScope("server-a", "device-a", "task-a")
    private val methods = setOf(
        "thread/realtime/listVoices", "thread/realtime/start", "thread/realtime/appendAudio",
        "thread/realtime/appendText", "thread/realtime/appendSpeech", "thread/realtime/stop",
    )

    @Test fun availabilityIsStrictlyAdvertised() {
        val controller = controller()
        controller.setScope(scope, setOf("thread/realtime/start", "thread/realtime/stop"))
        assertTrue(controller.uiState.value.availability.start)
        assertFalse(controller.uiState.value.availability.appendAudio)
        controller.startMicrophone()
        assertNull(permissionRequest)
        assertNotNull(controller.uiState.value.error)
    }

    @Test fun startUsesWebsocketAndNeverStartsMicrophone() {
        val controller = controller()
        controller.setScope(scope, methods)
        controller.startSession(RealtimeStartOptions(
            outputModality = RealtimeOutputModality.AUDIO,
            voice = "marin",
        ))
        val call = calls.single()
        assertEquals("thread/realtime/start", call.method)
        assertEquals("websocket", call.params.getJSONObject("transport").getString("type"))
        assertEquals("audio", call.params.getString("outputModality"))
        assertEquals(setOf("threadId", "outputModality", "transport", "voice"), call.params.keys().asSequence().toSet())
        assertFalse(audio.isCapturing)
        assertFalse(controller.uiState.value.recording)
    }

    @Test fun explicitPermissionGrantStartsCaptureOnlyAfterStartedEvent() {
        val controller = controller()
        controller.setScope(scope, methods)
        controller.startSession()
        calls.removeAt(0).done(JSONObject(), null)
        controller.consumeEvent("thread/realtime/started", JSONObject()
            .put("threadId", "task-a").put("realtimeSessionId", "session-a").put("version", "v2"))
        controller.startMicrophone()
        assertFalse(audio.isCapturing)
        permissionRequest!!(true)
        assertTrue(audio.isCapturing)
        assertTrue(controller.uiState.value.recording)
        assertEquals(listOf(true), sessionChanges.drop(1))
    }

    @Test fun deniedPermissionDoesNotTouchAudioEngine() {
        val controller = activeController()
        controller.startMicrophone()
        permissionRequest!!(false)
        assertFalse(audio.isCapturing)
        assertFalse(controller.uiState.value.recording)
        assertEquals("Microphone permission is required for voice input.", controller.uiState.value.error)
    }

    @Test fun microphonePcmIsBoundedAndEncodedWithProtocolMetadata() {
        val controller = activeController()
        controller.startMicrophone()
        permissionRequest!!(true)
        val pcm = byteArrayOf(1, 2, 3, 4)
        audio.emit(RealtimePcmChunk(pcm, 24_000, 1, 2))
        val call = calls.last()
        assertEquals("thread/realtime/appendAudio", call.method)
        val sent = call.params.getJSONObject("audio")
        assertArrayEquals(pcm, Base64.getDecoder().decode(sent.getString("data")))
        assertEquals(24_000, sent.getInt("sampleRate"))
        assertEquals(1, sent.getInt("numChannels"))
        assertEquals(2, sent.getInt("samplesPerChannel"))
    }

    @Test fun pendingMicrophoneQueueIsBoundedAndDropsOldest() {
        val controller = activeController()
        controller.startMicrophone()
        permissionRequest!!(true)
        repeat(7) { value -> audio.emit(RealtimePcmChunk(byteArrayOf(value.toByte(), 0), 24_000, 1, 1)) }
        assertEquals(1, calls.count { it.method == "thread/realtime/appendAudio" })
        val first = calls.first { it.method == "thread/realtime/appendAudio" }
        first.done(JSONObject(), null)
        val second = calls.last { it.method == "thread/realtime/appendAudio" }
        val byte = Base64.getDecoder().decode(second.params.getJSONObject("audio").getString("data"))[0]
        assertEquals("Oldest queued chunks are discarded", 3, byte.toInt())
    }

    @Test fun outputAudioIsValidatedAndPlayedWithoutPersistence() {
        val controller = activeController()
        val pcm = byteArrayOf(1, 0, 2, 0)
        assertTrue(controller.consumeEvent("thread/realtime/outputAudio/delta", JSONObject()
            .put("threadId", "task-a").put("audio", JSONObject()
                .put("data", Base64.getEncoder().encodeToString(pcm)).put("sampleRate", 24_000)
                .put("numChannels", 1).put("samplesPerChannel", 2).put("itemId", "audio-1"))))
        assertArrayEquals(pcm, audio.played.single().data)
        assertEquals("audio-1", audio.played.single().itemId)
    }

    @Test fun transcriptEventsAreScopedAndFinalizePartialText() {
        val controller = activeController()
        assertFalse(controller.consumeEvent("thread/realtime/transcript/delta", JSONObject()
            .put("threadId", "other").put("role", "assistant").put("delta", "Private")))
        controller.consumeEvent("thread/realtime/transcript/delta", JSONObject()
            .put("threadId", "task-a").put("role", "assistant").put("delta", "Hel"))
        controller.consumeEvent("thread/realtime/transcript/delta", JSONObject()
            .put("threadId", "task-a").put("role", "assistant").put("delta", "lo"))
        controller.consumeEvent("thread/realtime/transcript/done", JSONObject()
            .put("threadId", "task-a").put("role", "assistant").put("text", "Hello"))
        val row = controller.uiState.value.transcripts.single()
        assertEquals("Hello", row.text)
        assertTrue(row.final)
    }

    @Test fun scopeChangeStopsOldSessionAndReleasesAudio() {
        val controller = activeController()
        controller.startMicrophone()
        permissionRequest!!(true)
        controller.setScope(RealtimeScope("server-b", "device-b", "task-b"), methods)
        assertEquals("thread/realtime/stop", calls.last().method)
        assertEquals("task-a", calls.last().params.getString("threadId"))
        assertTrue(audio.released)
    }

    @Test fun scopeChangeRejectsLatePermissionGrant() {
        val controller = activeController()
        controller.startMicrophone()
        val permission = permissionRequest!!
        controller.setScope(RealtimeScope("server-b", "device-b", "task-b"), methods)
        permission(true)
        assertFalse(controller.uiState.value.recording)
    }

    @Test fun pauseStopsRemoteSessionAndReleasesAudio() {
        val controller = activeController()
        controller.startMicrophone()
        permissionRequest!!(true)
        controller.onPause()
        assertEquals("thread/realtime/stop", calls.last().method)
        assertEquals(RealtimePhase.STOPPING, controller.uiState.value.phase)
        assertTrue(audio.released)
        calls.last().done(JSONObject(), null)
        assertEquals(RealtimePhase.CLOSED, controller.uiState.value.phase)
    }

    @Test fun repeatedStopWhileStoppingSendsOnlyOneRpc() {
        val controller = activeController()
        controller.stopSession()
        controller.stopSession()
        assertEquals(1, calls.count { it.method == "thread/realtime/stop" })
        assertEquals(RealtimePhase.STOPPING, controller.uiState.value.phase)
    }

    @Test fun lateStartedCannotReactivateStoppedSession() {
        val controller = controller()
        controller.setScope(scope, methods)
        controller.startSession()
        controller.stopSession()
        assertFalse(controller.consumeEvent("thread/realtime/started", JSONObject()
            .put("threadId", "task-a").put("realtimeSessionId", "late").put("version", "v2")))
        assertEquals(RealtimePhase.STOPPING, controller.uiState.value.phase)
        assertTrue(sessionChanges.none { it })
    }

    @Test fun outputAudioAfterStopIsDroppedAndCannotRecreatePlayer() {
        val controller = activeController()
        controller.stopSession()
        val pcm = byteArrayOf(1, 0, 2, 0)
        assertFalse(controller.consumeEvent("thread/realtime/outputAudio/delta", JSONObject()
            .put("threadId", "task-a").put("audio", JSONObject()
                .put("data", Base64.getEncoder().encodeToString(pcm)).put("sampleRate", 24_000)
                .put("numChannels", 1).put("samplesPerChannel", 2))))
        assertTrue(audio.played.isEmpty())
    }

    @Test fun voicesAndTypedTextInputsUseGeneratedSchema() {
        val controller = activeController()
        controller.refreshVoices()
        calls.last().done(JSONObject().put("voices", JSONObject()
            .put("v1", JSONArray().put("alloy")).put("v2", JSONArray().put("marin"))
            .put("defaultV1", "alloy").put("defaultV2", "marin")), null)
        assertEquals("marin", controller.uiState.value.voices.defaultV2)
        controller.appendText(RealtimeTextRole.USER, " hello ")
        assertEquals("user", calls.last().params.getString("role"))
        assertEquals("hello", calls.last().params.getString("text"))
    }

    private fun activeController(): RealtimeController {
        val controller = controller()
        controller.setScope(scope, methods)
        controller.startSession()
        calls.removeAt(0).done(JSONObject(), null)
        controller.consumeEvent("thread/realtime/started", JSONObject().put("threadId", "task-a").put("version", "v2"))
        return controller
    }

    private class FakeAudio : RealtimeAudioIo {
        override var isCapturing = false
        var released = false
        val played = mutableListOf<RealtimePcmChunk>()
        private var onChunk: ((RealtimePcmChunk) -> Unit)? = null
        override fun startCapture(onChunk: (RealtimePcmChunk) -> Unit, onError: (String) -> Unit): Boolean {
            if (released) return false
            isCapturing = true
            this.onChunk = onChunk
            return true
        }
        fun emit(chunk: RealtimePcmChunk) = onChunk!!.invoke(chunk)
        override fun stopCapture() { isCapturing = false }
        override fun play(chunk: RealtimePcmChunk, onError: (String) -> Unit) { played += chunk }
        override fun stopPlayback() = Unit
        override fun release() { released = true; isCapturing = false }
    }
}
