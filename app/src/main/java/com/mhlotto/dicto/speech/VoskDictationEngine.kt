package com.mhlotto.dicto.speech

import android.content.Context
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import org.vosk.Model
import org.vosk.Recognizer
import org.vosk.android.RecognitionListener
import org.vosk.android.SpeechService

// Replaceable local DictationEngine implementation. Vosk provides continuous offline
// recognition without Android SpeechRecognizer sessions or their start/stop sounds.
class VoskDictationEngine(
    context: Context,
) : DictationEngine, RecognitionListener {
    private val appContext = context.applicationContext
    private val engineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val _state = MutableStateFlow(DictationState(engineLabel = ENGINE_LABEL))
    override val state: StateFlow<DictationState> = _state

    private val committedSegments = mutableListOf<String>()
    private var activeProjectId: Long? = null
    private var startJob: Job? = null
    private var model: Model? = null
    private var recognizer: Recognizer? = null
    private var speechService: SpeechService? = null

    @Volatile
    private var recordingRequested = false

    @Volatile
    private var controlledRestart = false

    @Volatile
    private var timeoutRestartUsed = false

    override fun start(projectId: Long) {
        if (recordingRequested && activeProjectId == projectId) return
        if (activeProjectId != projectId) {
            committedSegments.clear()
        }
        activeProjectId = projectId
        recordingRequested = true
        timeoutRestartUsed = false
        _state.value = DictationState(
            isRecording = true,
            activeProjectId = projectId,
            committedText = committedSegments.joinToString(" ").trim(),
            engineLabel = "$ENGINE_LABEL (loading)",
        )
        startListening(projectId)
    }

    override fun stop() {
        val projectId = activeProjectId
        recordingRequested = false
        timeoutRestartUsed = false
        startJob?.cancel()
        startJob = null
        stopSpeechService(shutdown = false)
        _state.update {
            it.copy(
                isRecording = false,
                activeProjectId = projectId,
                partialText = "",
                engineLabel = ENGINE_LABEL,
            )
        }
    }

    override fun cancel() {
        recordingRequested = false
        controlledRestart = false
        timeoutRestartUsed = false
        startJob?.cancel()
        startJob = null
        stopSpeechService(shutdown = true)
        closeModel()
        committedSegments.clear()
        activeProjectId = null
        _state.value = DictationState(engineLabel = ENGINE_LABEL)
    }

    override fun onPartialResult(hypothesis: String?) {
        val partial = parsePartial(hypothesis).partial.trim()
        if (!recordingRequested) return
        _state.update {
            it.copy(partialText = partial, error = null, engineLabel = "$ENGINE_LABEL (listening)")
        }
    }

    override fun onResult(hypothesis: String?) {
        appendCommitted(parseResult(hypothesis).text)
    }

    override fun onFinalResult(hypothesis: String?) {
        appendCommitted(parseResult(hypothesis).text)
        if (recordingRequested && !controlledRestart) {
            restartAfterUnexpectedStop()
        }
    }

    override fun onError(exception: Exception?) {
        val message = exception?.localizedMessage ?: "Vosk recognition failed"
        if (recordingRequested) {
            _state.update { it.copy(error = message, engineLabel = "$ENGINE_LABEL (error)") }
            restartAfterUnexpectedStop()
        } else {
            _state.update { it.copy(isRecording = false, error = message, engineLabel = ENGINE_LABEL) }
        }
    }

    override fun onTimeout() {
        if (recordingRequested && !timeoutRestartUsed) {
            timeoutRestartUsed = true
            restartAfterUnexpectedStop()
        } else if (recordingRequested) {
            _state.update {
                it.copy(error = "Vosk recognition timed out", engineLabel = "$ENGINE_LABEL (timeout)")
            }
        }
    }

    private fun startListening(projectId: Long) {
        startJob?.cancel()
        startJob = engineScope.launch {
            runCatching {
                val modelDir = withContext(Dispatchers.IO) {
                    VoskModelManager.ensureDefaultModelAvailable(appContext)
                }
                if (!recordingRequested || activeProjectId != projectId) return@launch

                val loadedModel = model ?: withContext(Dispatchers.IO) {
                    Model(modelDir.absolutePath)
                }.also { model = it }
                val activeRecognizer = Recognizer(loadedModel, SAMPLE_RATE_HZ)
                val activeSpeechService = SpeechService(activeRecognizer, SAMPLE_RATE_HZ)

                recognizer = activeRecognizer
                speechService = activeSpeechService
                withContext(Dispatchers.Main.immediate) {
                    activeSpeechService.startListening(this@VoskDictationEngine)
                }
                _state.update {
                    it.copy(
                        isRecording = true,
                        activeProjectId = projectId,
                        error = null,
                        engineLabel = "$ENGINE_LABEL (listening)",
                    )
                }
            }.onFailure { error ->
                recordingRequested = false
                stopSpeechService(shutdown = true)
                closeModel()
                _state.update {
                    it.copy(
                        isRecording = false,
                        activeProjectId = projectId,
                        error = error.message ?: "Vosk failed to start",
                        engineLabel = ENGINE_LABEL,
                    )
                }
            }
        }
    }

    private fun restartAfterUnexpectedStop() {
        val projectId = activeProjectId ?: return
        if (controlledRestart) return
        controlledRestart = true
        _state.update { it.copy(engineLabel = "$ENGINE_LABEL (restarting)") }
        engineScope.launch {
            stopSpeechService(shutdown = true)
            delay(RESTART_DEBOUNCE_MS)
            controlledRestart = false
            if (recordingRequested && activeProjectId == projectId) {
                startListening(projectId)
            }
        }
    }

    private fun stopSpeechService(shutdown: Boolean) {
        val service = speechService
        speechService = null
        runCatching { service?.stop() }
        if (shutdown) {
            runCatching { service?.shutdown() }
        }
        runCatching { recognizer?.close() }
        recognizer = null
    }

    private fun closeModel() {
        runCatching { model?.close() }
        model = null
    }

    private fun appendCommitted(text: String) {
        val cleanText = text.trim()
        if (cleanText.isBlank()) return
        val normalized = cleanText.normalized()
        if (committedSegments.lastOrNull()?.normalized() != normalized) {
            committedSegments += cleanText
        }
        _state.update {
            it.copy(
                committedText = committedSegments.joinToString(" ").trim(),
                partialText = "",
                error = null,
            )
        }
    }

    private fun parsePartial(json: String?): VoskPartialResult {
        return runCatching {
            VoskPartialResult(JSONObject(json.orEmpty()).optString("partial"))
        }.getOrDefault(VoskPartialResult(partial = ""))
    }

    private fun parseResult(json: String?): VoskResult {
        return runCatching {
            VoskResult(JSONObject(json.orEmpty()).optString("text"))
        }.getOrDefault(VoskResult(text = ""))
    }

    private fun String.normalized(): String = trim().lowercase().replace(Regex("\\s+"), " ")

    private companion object {
        const val ENGINE_LABEL = "Vosk local engine"
        const val SAMPLE_RATE_HZ = 16_000.0f
        const val RESTART_DEBOUNCE_MS = 200L
    }
}
