package com.mhlotto.dicto.speech

import com.mhlotto.dicto.audio.PcmAudioRecorder
import com.mhlotto.dicto.whisper.WhisperNative
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.ClosedSendChannelException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.util.concurrent.atomic.AtomicInteger
import kotlin.math.abs
import kotlin.math.min

// Replaceable local DictationEngine implementation. This keeps Whisper-specific capture,
// chunking, JNI, and de-duplication out of ViewModels so another local STT engine can swap in.
class WhisperDictationEngine(
    private val modelPath: String,
    private val recorder: PcmAudioRecorder = PcmAudioRecorder(),
    private val native: WhisperNative = WhisperNative(),
) : DictationEngine {
    private val engineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val _state = MutableStateFlow(DictationState(engineLabel = ENGINE_LABEL))
    override val state: StateFlow<DictationState> = _state

    private var captureJob: Job? = null
    private var transcriptionJob: Job? = null
    private var chunks: Channel<FloatArray>? = null
    private var chunker: OverlapChunker? = null
    private var activeProjectId: Long? = null
    @Volatile
    private var stopping = false
    private val queuedChunks = AtomicInteger(0)
    private val committedSegments = mutableListOf<String>()

    override fun start(projectId: Long) {
        if (_state.value.isRecording && activeProjectId == projectId) return
        cancel()
        stopping = false
        queuedChunks.set(0)
        activeProjectId = projectId

        if (!WhisperNative.isAvailable) {
            publishError(projectId, "Whisper native library is not available; use SpeechRecognizer or build native support")
            return
        }
        if (!File(modelPath).isFile) {
            publishError(projectId, "Whisper model not found at $modelPath")
            return
        }

        val chunkChannel = Channel<FloatArray>(capacity = MAX_QUEUED_CHUNKS)
        chunks = chunkChannel
        _state.value = DictationState(
            isRecording = true,
            activeProjectId = projectId,
            engineLabel = "$ENGINE_LABEL (initializing)",
        )

        transcriptionJob = engineScope.launch {
            runCatching {
                withContext(Dispatchers.IO) { native.initialize(modelPath) }
                _state.update { it.copy(engineLabel = "$ENGINE_LABEL (capturing)") }
                for (chunk in chunkChannel) {
                    val remaining = queuedChunks.decrementAndGet().coerceAtLeast(0)
                    publishProgress(isTranscribing = true, queued = remaining)
                    val text = withContext(Dispatchers.Default) { native.transcribePcm(chunk) }
                    appendCommitted(text)
                    publishProgress(isTranscribing = false, queued = queuedChunks.get())
                }
            }.onFailure { error ->
                _state.update {
                    it.copy(isRecording = false, error = error.message ?: "Whisper transcription failed")
                }
            }
        }

        captureJob = engineScope.launch {
            val activeChunker = OverlapChunker(
                chunkSamples = CHUNK_SAMPLES,
                overlapSamples = OVERLAP_SAMPLES,
            )
            chunker = activeChunker
            recorder.audioChunks(this)
                .catch { error ->
                    if (!stopping) {
                        _state.update {
                            it.copy(isRecording = false, error = error.message ?: "Microphone capture failed")
                        }
                    }
                }
                .collect { samples ->
                    activeChunker.append(samples).forEach { chunk ->
                        enqueueChunk(chunkChannel, chunk)
                    }
                }
            activeChunker.flush()?.let {
                enqueueChunk(chunkChannel, it, force = true)
            }
            chunkChannel.close()
        }
    }

    override fun stop() {
        val projectId = activeProjectId
        _state.update {
            it.copy(
                isRecording = false,
                activeProjectId = projectId,
                engineLabel = "$ENGINE_LABEL (finalizing)",
            )
        }
        engineScope.launch {
            stopping = true
            val chunkChannel = chunks
            recorder.stop()
            captureJob?.join()
            captureJob = null
            chunker?.flush()?.let { chunkChannel?.sendSafely(it) }
            chunker = null
            chunkChannel?.close()
            chunks = null
            transcriptionJob?.join()
            transcriptionJob = null
            native.release()
            stopping = false
            _state.update { it.copy(isRecording = false, engineLabel = ENGINE_LABEL) }
        }
    }

    override fun cancel() {
        stopping = false
        captureJob?.cancel()
        transcriptionJob?.cancel()
        captureJob = null
        transcriptionJob = null
        chunks?.cancel()
        chunks = null
        chunker = null
        native.release()
        committedSegments.clear()
        queuedChunks.set(0)
        _state.value = DictationState(engineLabel = ENGINE_LABEL)
    }

    private fun publishProgress(isTranscribing: Boolean, queued: Int) {
        val phase = when {
            stopping && isTranscribing -> "finalizing, transcribing"
            stopping -> "finalizing"
            isTranscribing -> "capturing + transcribing"
            queued > 0 -> "capturing, queued $queued"
            else -> "capturing"
        }
        _state.update { it.copy(engineLabel = "$ENGINE_LABEL ($phase)") }
    }

    private suspend fun Channel<FloatArray>.sendSafely(samples: FloatArray) {
        try {
            send(samples)
        } catch (_: ClosedSendChannelException) {
            // stop() closes the channel to drain transcription; cancel() discards it.
        }
    }

    private suspend fun enqueueChunk(
        channel: Channel<FloatArray>,
        samples: FloatArray,
        force: Boolean = false,
    ) {
        if (!force && shouldSkipForSilence(samples)) {
            _state.update { it.copy(engineLabel = "$ENGINE_LABEL (capturing, silence)") }
            return
        }
        queuedChunks.incrementAndGet()
        publishProgress(isTranscribing = false, queued = queuedChunks.get())
        channel.sendSafely(samples)
    }

    private fun shouldSkipForSilence(samples: FloatArray): Boolean {
        if (samples.isEmpty()) return true
        var sum = 0.0
        var peak = 0f
        for (sample in samples) {
            val magnitude = abs(sample)
            sum += magnitude
            if (magnitude > peak) peak = magnitude
        }
        val mean = sum / samples.size
        return mean < SILENCE_MEAN_THRESHOLD && peak < SILENCE_PEAK_THRESHOLD
    }

    private fun appendCommitted(text: String) {
        val cleanText = text.trim()
        if (cleanText.isBlank()) return
        val last = committedSegments.lastOrNull()
        if (last != null && segmentsOverlap(last, cleanText)) {
            committedSegments.removeAt(committedSegments.lastIndex)
        }
        appendUnique(committedSegments, cleanText)
        _state.update {
            it.copy(
                committedText = committedSegments.joinToString(" ").trim(),
                partialText = "",
                error = null,
            )
        }
    }

    private fun publishError(projectId: Long, message: String) {
        _state.value = DictationState(
            isRecording = false,
            activeProjectId = projectId,
            error = message,
            engineLabel = ENGINE_LABEL,
        )
    }

    private fun segmentsOverlap(a: String, b: String): Boolean {
        val left = a.normalized()
        val right = b.normalized()
        return left.startsWith(right) || right.startsWith(left)
    }

    private fun appendUnique(segments: MutableList<String>, text: String) {
        val normalized = text.normalized()
        if (segments.lastOrNull()?.normalized() == normalized) return
        segments += text
    }

    private fun String.normalized(): String = trim().lowercase().replace(Regex("\\s+"), " ")

    private class OverlapChunker(
        private val chunkSamples: Int,
        private val overlapSamples: Int,
    ) {
        private var buffer = FloatArray(0)

        fun append(samples: FloatArray): List<FloatArray> {
            buffer += samples
            val chunks = mutableListOf<FloatArray>()
            while (buffer.size >= chunkSamples) {
                chunks += buffer.copyOfRange(0, chunkSamples)
                val keepFrom = chunkSamples - overlapSamples
                buffer = buffer.copyOfRange(keepFrom, buffer.size)
            }
            return chunks
        }

        fun flush(): FloatArray? {
            if (buffer.isEmpty()) return null
            val remaining = buffer.copyOfRange(0, min(buffer.size, chunkSamples))
            buffer = FloatArray(0)
            return remaining
        }
    }

    private companion object {
        const val ENGINE_LABEL = "Whisper local engine"
        const val CHUNK_SAMPLES = PcmAudioRecorder.SAMPLE_RATE_HZ * 2
        const val OVERLAP_SAMPLES = PcmAudioRecorder.SAMPLE_RATE_HZ / 2
        const val MAX_QUEUED_CHUNKS = 4
        const val SILENCE_MEAN_THRESHOLD = 0.006
        const val SILENCE_PEAK_THRESHOLD = 0.03f
    }
}
