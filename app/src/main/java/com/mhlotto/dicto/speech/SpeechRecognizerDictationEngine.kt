package com.mhlotto.dicto.speech

import android.content.Context
import android.content.Context.AUDIO_SERVICE
import android.content.Intent
import android.media.AudioManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update

// Replaceable DictationEngine implementation. SpeechRecognizer is useful for the first app pass,
// but this boundary is intended to allow Whisper, Vosk, or another continuous STT engine later.
class SpeechRecognizerDictationEngine(
    private val context: Context,
) : DictationEngine {
    private val handler = Handler(Looper.getMainLooper())
    private val beepSuppressor = RecognitionBeepSuppressor(context, handler)
    private val mode: SpeechMode by lazy { detectMode() }
    private val _state = MutableStateFlow(DictationState(engineLabel = "Recognizer not started"))

    override val state: StateFlow<DictationState> = _state

    private var recognizer: SpeechRecognizer? = null
    private var active = false
    private var isListening = false
    private var restartRunnable: Runnable? = null
    private var serviceBackoffCount = 0
    private val committedSegments = mutableListOf<String>()

    override fun start(projectId: Long) {
        if (active && _state.value.activeProjectId == projectId) return
        if (_state.value.activeProjectId != projectId) {
            resetTranscript(projectId)
        }
        active = true
        _state.update {
            it.copy(
                isRecording = true,
                activeProjectId = projectId,
                error = null,
                engineLabel = mode.label,
            )
        }
        startListening()
    }

    override fun stop() {
        active = false
        cancelScheduledRestart()
        releaseRecognizer()
        _state.update { it.copy(isRecording = false, error = null) }
    }

    override fun cancel() {
        active = false
        cancelScheduledRestart()
        releaseRecognizer()
        committedSegments.clear()
        _state.value = DictationState(engineLabel = mode.label)
    }

    private fun resetTranscript(projectId: Long) {
        serviceBackoffCount = 0
        committedSegments.clear()
        _state.value = DictationState(activeProjectId = projectId, engineLabel = mode.label)
    }

    private fun startListening() {
        if (!active || isListening) return
        releaseRecognizer()
        val speechRecognizer = createRecognizer()
        recognizer = speechRecognizer
        speechRecognizer.setRecognitionListener(listener)
        runCatching {
            beepSuppressor.runWithSuppressedBoundarySound {
                speechRecognizer.startListening(recognizerIntent())
            }
            isListening = true
            _state.update {
                it.copy(isRecording = true, error = null, engineLabel = mode.label)
            }
        }.onFailure {
            isListening = false
            releaseRecognizer()
            publishError("Recognizer start failed")
            scheduleRestart(delayMs = SERVICE_BACKOFF_MS, replaceExisting = true)
        }
    }

    private fun createRecognizer(): SpeechRecognizer {
        return if (mode.isOnDevice && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            SpeechRecognizer.createOnDeviceSpeechRecognizer(context)
        } else {
            SpeechRecognizer.createSpeechRecognizer(context)
        }
    }

    private fun detectMode(): SpeechMode {
        val onDevice = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
            SpeechRecognizer.isOnDeviceRecognitionAvailable(context)
        return if (onDevice) {
            SpeechMode("On-device recognizer", isOnDevice = true)
        } else {
            SpeechMode("Default recognizer", isOnDevice = false)
        }
    }

    private fun recognizerIntent(): Intent {
        return Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, mode.isOnDevice)
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 3)
        }
    }

    private fun scheduleRestart(
        delayMs: Long = RESTART_DEBOUNCE_MS,
        replaceExisting: Boolean = true,
    ) {
        if (!active) return
        restartRunnable?.let { existing ->
            if (!replaceExisting) return
            handler.removeCallbacks(existing)
        }
        val restart = Runnable {
            restartRunnable = null
            startListening()
        }
        restartRunnable = restart
        handler.postDelayed(restart, delayMs)
    }

    private fun cancelScheduledRestart() {
        restartRunnable?.let(handler::removeCallbacks)
        restartRunnable = null
    }

    private fun releaseRecognizer() {
        val speechRecognizer = recognizer ?: return
        recognizer = null
        isListening = false
        beepSuppressor.runWithSuppressedBoundarySound {
            runCatching { speechRecognizer.stopListening() }
            runCatching { speechRecognizer.cancel() }
            runCatching { speechRecognizer.destroy() }
        }
    }

    private fun destroyRecognizerAfterTerminalCallback() {
        val speechRecognizer = recognizer ?: return
        recognizer = null
        isListening = false
        runCatching { speechRecognizer.destroy() }
    }

    private val listener = object : RecognitionListener {
        override fun onReadyForSpeech(params: Bundle?) = Unit

        override fun onBeginningOfSpeech() {
            serviceBackoffCount = 0
        }

        override fun onRmsChanged(rmsdB: Float) = Unit
        override fun onBufferReceived(buffer: ByteArray?) = Unit

        override fun onEndOfSpeech() {
            isListening = false
            commitPartial()
            scheduleRestart(delayMs = END_OF_SPEECH_RESTART_MS, replaceExisting = false)
        }

        override fun onError(error: Int) {
            isListening = false
            commitPartial()
            if (!active) return
            cancelScheduledRestart()
            destroyRecognizerAfterTerminalCallback()
            publishError("${errorMessage(error)}, restarting")
            scheduleRestart(delayMs = restartDelayForError(error), replaceExisting = true)
        }

        override fun onResults(results: Bundle?) {
            isListening = false
            cancelScheduledRestart()
            bestText(results)?.let(::commitFinal)
            destroyRecognizerAfterTerminalCallback()
            serviceBackoffCount = 0
            scheduleRestart(replaceExisting = true)
        }

        override fun onPartialResults(partialResults: Bundle?) {
            bestText(partialResults)?.let { partial ->
                _state.update { it.copy(partialText = partial, error = null) }
            }
        }

        override fun onEvent(eventType: Int, params: Bundle?) = Unit
    }

    private fun commitPartial() {
        val partial = _state.value.partialText.trim()
        if (partial.isBlank()) return
        appendCommitted(partial)
    }

    private fun commitFinal(text: String) {
        val finalText = text.trim()
        if (finalText.isBlank()) return
        appendCommitted(finalText)
    }

    private fun appendCommitted(text: String) {
        val lastCommitted = committedSegments.lastOrNull()
        if (lastCommitted != null && segmentsOverlap(lastCommitted, text)) {
            committedSegments.removeAt(committedSegments.lastIndex)
        }
        appendUnique(committedSegments, text)
        _state.update { current ->
            current.copy(
                committedText = committedSegments.joinToString(" ").trim(),
                partialText = "",
                error = null,
            )
        }
    }

    private fun bestText(results: Bundle?): String? {
        return results
            ?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
            ?.firstOrNull()
            ?.trim()
            ?.takeIf { it.isNotBlank() }
    }

    private fun publishError(message: String) {
        _state.update { it.copy(error = message, isRecording = active, engineLabel = mode.label) }
    }

    private fun errorMessage(error: Int): String {
        return when (error) {
            SpeechRecognizer.ERROR_AUDIO -> "Audio recording error"
            SpeechRecognizer.ERROR_CLIENT -> "Recognizer client error"
            SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> "Missing microphone permission"
            SpeechRecognizer.ERROR_NETWORK -> "Network recognition error"
            SpeechRecognizer.ERROR_NETWORK_TIMEOUT -> "Network timeout"
            SpeechRecognizer.ERROR_NO_MATCH -> "No speech match"
            SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> "Recognizer busy"
            SpeechRecognizer.ERROR_SERVER -> "Recognizer server error"
            SpeechRecognizer.ERROR_SERVER_DISCONNECTED -> "Recognizer service disconnected"
            SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> "Speech timeout"
            SpeechRecognizer.ERROR_TOO_MANY_REQUESTS -> "Recognizer throttled"
            else -> "Recognizer error $error"
        }
    }

    private fun restartDelayForError(error: Int): Long {
        return when (error) {
            SpeechRecognizer.ERROR_RECOGNIZER_BUSY,
            SpeechRecognizer.ERROR_SERVER_DISCONNECTED,
            SpeechRecognizer.ERROR_TOO_MANY_REQUESTS -> {
                serviceBackoffCount = (serviceBackoffCount + 1).coerceAtMost(MAX_SERVICE_BACKOFF_STEPS)
                SERVICE_BACKOFF_MS * serviceBackoffCount
            }
            else -> {
                serviceBackoffCount = 0
                RESTART_DEBOUNCE_MS
            }
        }
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

    private data class SpeechMode(
        val label: String,
        val isOnDevice: Boolean,
    )

    private class RecognitionBeepSuppressor(
        context: Context,
        private val handler: Handler,
    ) {
        private val audioManager = context.getSystemService(AUDIO_SERVICE) as AudioManager
        private var restoreRunnable: Runnable? = null
        private var originalVolume: Int? = null
        private var wasOriginallyMuted = false

        fun runWithSuppressedBoundarySound(action: () -> Unit) {
            captureOriginalAudioStateIfNeeded()
            muteRecognitionStream()
            try {
                action()
            } finally {
                scheduleRestore()
            }
        }

        private fun captureOriginalAudioStateIfNeeded() {
            if (restoreRunnable != null) {
                handler.removeCallbacks(restoreRunnable!!)
                restoreRunnable = null
                return
            }
            originalVolume = audioManager.getStreamVolume(AudioManager.STREAM_MUSIC)
            wasOriginallyMuted = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                audioManager.isStreamMute(AudioManager.STREAM_MUSIC)
            } else {
                originalVolume == 0
            }
        }

        private fun muteRecognitionStream() {
            runCatching {
                audioManager.adjustStreamVolume(AudioManager.STREAM_MUSIC, AudioManager.ADJUST_MUTE, 0)
            }
        }

        private fun scheduleRestore() {
            val capturedVolume = originalVolume
            val capturedMuted = wasOriginallyMuted
            val restore = Runnable {
                // SpeechRecognizer beep behavior varies by recognition service/OEM. This best-effort
                // mute is isolated so future engines do not inherit platform-specific audio hacks.
                runCatching {
                    if (capturedVolume != null) {
                        audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, capturedVolume, 0)
                    }
                    val direction = if (capturedMuted) AudioManager.ADJUST_MUTE else AudioManager.ADJUST_UNMUTE
                    audioManager.adjustStreamVolume(AudioManager.STREAM_MUSIC, direction, 0)
                }
                originalVolume = null
                restoreRunnable = null
            }
            restoreRunnable = restore
            handler.postDelayed(restore, BEEP_RESTORE_DELAY_MS)
        }
    }

    private companion object {
        const val RESTART_DEBOUNCE_MS = 200L
        const val END_OF_SPEECH_RESTART_MS = 300L
        const val SERVICE_BACKOFF_MS = 500L
        const val MAX_SERVICE_BACKOFF_STEPS = 3
        const val BEEP_RESTORE_DELAY_MS = 250L
    }
}
