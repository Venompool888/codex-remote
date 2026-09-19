package app.codexremote.android

import android.annotation.SuppressLint
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.AudioTrack
import android.media.MediaRecorder
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread

/** PCM16 little-endian audio matching the app-server realtime audio contract. */
data class RealtimePcmChunk(
    val data: ByteArray,
    val sampleRate: Int,
    val channelCount: Int,
    val samplesPerChannel: Int,
    val itemId: String? = null,
)

interface RealtimeAudioIo {
    val isCapturing: Boolean
    fun startCapture(onChunk: (RealtimePcmChunk) -> Unit, onError: (String) -> Unit): Boolean
    fun stopCapture()
    fun play(chunk: RealtimePcmChunk, onError: (String) -> Unit)
    fun stopPlayback()
    fun release()
}

/**
 * Ephemeral microphone/speaker transport. It never writes audio to disk and keeps only a small
 * bounded playback queue. RECORD_AUDIO permission must be granted before [startCapture].
 */
class AndroidRealtimeAudioEngine(
    private val inputSampleRate: Int = INPUT_SAMPLE_RATE,
    private val inputChannelCount: Int = 1,
) : RealtimeAudioIo {
    private val capturing = AtomicBoolean(false)
    private val released = AtomicBoolean(false)
    private val playbackQueue = ArrayBlockingQueue<PlaybackRequest>(MAX_PLAYBACK_QUEUE)
    private val playbackRunning = AtomicBoolean(false)
    @Volatile private var recorder: AudioRecord? = null
    @Volatile private var player: AudioTrack? = null
    @Volatile private var playerFormat: Pair<Int, Int>? = null

    override val isCapturing: Boolean get() = capturing.get()

    @SuppressLint("MissingPermission")
    override fun startCapture(onChunk: (RealtimePcmChunk) -> Unit, onError: (String) -> Unit): Boolean {
        if (released.get() || !capturing.compareAndSet(false, true)) return false
        val channelMask = if (inputChannelCount == 1) AudioFormat.CHANNEL_IN_MONO else AudioFormat.CHANNEL_IN_STEREO
        val chunkBytes = ((inputSampleRate * inputChannelCount * BYTES_PER_SAMPLE * CHUNK_MILLIS) / 1000)
            .coerceIn(BYTES_PER_SAMPLE, MAX_CHUNK_BYTES)
        val minimum = AudioRecord.getMinBufferSize(inputSampleRate, channelMask, AudioFormat.ENCODING_PCM_16BIT)
        if (minimum <= 0) {
            capturing.set(false)
            onError("Microphone audio format is unavailable on this device.")
            return false
        }
        val audioRecord = runCatching {
            AudioRecord(
                MediaRecorder.AudioSource.VOICE_RECOGNITION,
                inputSampleRate,
                channelMask,
                AudioFormat.ENCODING_PCM_16BIT,
                maxOf(minimum, chunkBytes * 2),
            )
        }.getOrElse {
            capturing.set(false)
            onError(it.message ?: "Could not open the microphone.")
            return false
        }
        if (audioRecord.state != AudioRecord.STATE_INITIALIZED) {
            capturing.set(false)
            audioRecord.release()
            onError("Could not initialize the microphone.")
            return false
        }
        recorder = audioRecord
        runCatching { audioRecord.startRecording() }.onFailure {
            capturing.set(false)
            recorder = null
            audioRecord.release()
            onError(it.message ?: "Could not start the microphone.")
            return false
        }
        thread(name = "CodexRealtimeCapture", isDaemon = true) {
            val buffer = ByteArray(chunkBytes)
            while (capturing.get() && recorder === audioRecord) {
                val count = audioRecord.read(buffer, 0, buffer.size, AudioRecord.READ_BLOCKING)
                if (count > 0) {
                    val evenCount = count - count % (BYTES_PER_SAMPLE * inputChannelCount)
                    if (evenCount > 0) onChunk(RealtimePcmChunk(
                        data = buffer.copyOf(evenCount),
                        sampleRate = inputSampleRate,
                        channelCount = inputChannelCount,
                        samplesPerChannel = evenCount / BYTES_PER_SAMPLE / inputChannelCount,
                    ))
                } else if (count < 0 && capturing.get()) {
                    capturing.set(false)
                    onError("Microphone capture failed ($count).")
                }
            }
        }
        return true
    }

    override fun stopCapture() {
        capturing.set(false)
        val current = recorder
        recorder = null
        runCatching { current?.stop() }
        current?.release()
    }

    override fun play(chunk: RealtimePcmChunk, onError: (String) -> Unit) {
        if (released.get() || chunk.data.isEmpty() || chunk.data.size > MAX_CHUNK_BYTES) return
        val request = PlaybackRequest(chunk.copy(data = chunk.data.copyOf()), onError)
        if (!playbackQueue.offer(request)) {
            playbackQueue.poll()
            playbackQueue.offer(request)
        }
        startPlaybackWorker()
    }

    private fun startPlaybackWorker() {
        if (!playbackRunning.compareAndSet(false, true)) return
        thread(name = "CodexRealtimePlayback", isDaemon = true) {
            try {
                while (!released.get()) {
                    val request = playbackQueue.poll() ?: break
                    val track = ensurePlayer(request.chunk, request.onError) ?: continue
                    runCatching {
                        writePcmFully(request.chunk.data) { data, offset, length ->
                            track.write(data, offset, length, AudioTrack.WRITE_BLOCKING)
                        }
                    }
                        .onSuccess { written -> when {
                            written < 0 -> request.onError("Audio playback failed ($written).")
                            written < request.chunk.data.size -> request.onError("Audio playback stopped before the chunk completed.")
                        } }
                        .onFailure { request.onError(it.message ?: "Audio playback failed.") }
                }
            } finally {
                playbackRunning.set(false)
                if (playbackQueue.isNotEmpty()) startPlaybackWorker()
            }
        }
    }

    private fun ensurePlayer(chunk: RealtimePcmChunk, onError: (String) -> Unit): AudioTrack? {
        if (chunk.sampleRate <= 0 || chunk.channelCount !in 1..2) {
            onError("The host sent an unsupported audio format.")
            return null
        }
        val format = chunk.sampleRate to chunk.channelCount
        player?.takeIf { playerFormat == format }?.let { return it }
        stopAndReleasePlayer()
        val channelMask = if (chunk.channelCount == 1) AudioFormat.CHANNEL_OUT_MONO else AudioFormat.CHANNEL_OUT_STEREO
        val minimum = AudioTrack.getMinBufferSize(chunk.sampleRate, channelMask, AudioFormat.ENCODING_PCM_16BIT)
        if (minimum <= 0) {
            onError("The host audio format cannot be played on this device.")
            return null
        }
        val track = runCatching {
            AudioTrack.Builder()
                .setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build())
                .setAudioFormat(AudioFormat.Builder().setEncoding(AudioFormat.ENCODING_PCM_16BIT).setSampleRate(chunk.sampleRate).setChannelMask(channelMask).build())
                .setBufferSizeInBytes(maxOf(minimum, chunk.data.size * 2))
                .setTransferMode(AudioTrack.MODE_STREAM)
                .build()
        }.getOrElse {
            onError(it.message ?: "Could not initialize audio playback.")
            return null
        }
        if (track.state != AudioTrack.STATE_INITIALIZED) {
            track.release()
            onError("Could not initialize audio playback.")
            return null
        }
        track.play()
        player = track
        playerFormat = format
        return track
    }

    override fun stopPlayback() {
        playbackQueue.clear()
        stopAndReleasePlayer()
    }

    private fun stopAndReleasePlayer() {
        val current = player
        player = null
        playerFormat = null
        runCatching { current?.stop() }
        current?.release()
    }

    override fun release() {
        if (!released.compareAndSet(false, true)) return
        stopCapture()
        stopPlayback()
    }

    private data class PlaybackRequest(val chunk: RealtimePcmChunk, val onError: (String) -> Unit)

    companion object {
        const val INPUT_SAMPLE_RATE = 24_000
        const val CHUNK_MILLIS = 100
        const val MAX_CHUNK_BYTES = 64 * 1024
        private const val MAX_PLAYBACK_QUEUE = 8
        private const val BYTES_PER_SAMPLE = 2
    }
}

/** Returns bytes consumed, or the negative writer error. A zero write stops without spinning. */
internal fun writePcmFully(
    data: ByteArray,
    writer: (data: ByteArray, offset: Int, length: Int) -> Int,
): Int {
    var offset = 0
    while (offset < data.size) {
        val remaining = data.size - offset
        val written = writer(data, offset, remaining)
        if (written <= 0) return if (written < 0) written else offset
        if (written > remaining) return -1
        offset += written
    }
    return offset
}
