package com.mhlotto.dicto.speech

import android.content.Context
import com.google.mlkit.genai.common.FeatureStatus
import com.google.mlkit.genai.common.audio.AudioSource
import com.google.mlkit.genai.speechrecognition.SpeechRecognizer
import com.google.mlkit.genai.speechrecognition.SpeechRecognizerResponse
import com.google.mlkit.genai.speechrecognition.speechRecognizerRequest
import kotlinx.coroutines.CancellationException
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

// Experimental alpha DictationEngine backed by ML Kit GenAI Speech Recognition.
// This is intentionally isolated because the API and device support are still changing.
class MlKitGenAiSpeechDictationEngine(
    context: Context,
) : DictationEngine {
    private val appContext = context.applicationContext
    private val engineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val _state = MutableStateFlow(DictationState(engineLabel = ENGINE_LABEL))
    override val state: StateFlow<DictationState> = _state

    private val committedSegments = mutableListOf<String>()
    private var recognizer: SpeechRecognizer? = null
    private var recognitionJob: Job? = null
    private var activeProjectId: Long? = null

    @Volatile
    private var recordingRequested = false

    @Volatile
    private var controlledRestart = false

    override fun start(projectId: Long) {
        if (recordingRequested && activeProjectId == projectId) return
        if (activeProjectId != projectId) {
            committedSegments.clear()
        }
        activeProjectId = projectId
        recordingRequested = true
        _state.value = DictationState(
            isRecording = true,
            activeProjectId = projectId,
            committedText = committedSegments.joinToString(" ").trim(),
            engineLabel = "$ENGINE_LABEL (checking availability)",
        )
        startRecognition(projectId)
    }

    override fun stop() {
        val projectId = activeProjectId
        recordingRequested = false
        recognitionJob?.cancel()
        recognitionJob = null
        engineScope.launch {
            stopAndCloseRecognizer()
            _state.update {
                it.copy(
                    isRecording = false,
                    activeProjectId = projectId,
                    partialText = "",
                    engineLabel = ENGINE_LABEL,
                )
            }
        }
    }

    override fun cancel() {
        recordingRequested = false
        controlledRestart = false
        recognitionJob?.cancel()
        recognitionJob = null
        engineScope.launch {
            stopAndCloseRecognizer()
        }
        committedSegments.clear()
        activeProjectId = null
        _state.value = DictationState(engineLabel = ENGINE_LABEL)
    }

    private fun startRecognition(projectId: Long) {
        recognitionJob?.cancel()
        recognitionJob = engineScope.launch {
            runCatching {
                MlKitSpeechAvailability.apiCompatibilityReason()?.let { reason ->
                    throw IllegalStateException(reason)
                }
                val activeRecognizer = MlKitSpeechAvailability.createBasicRecognizer()
                recognizer = activeRecognizer
                val status = withContext(Dispatchers.IO) { activeRecognizer.checkStatus() }
                if (status != FeatureStatus.AVAILABLE) {
                    throw IllegalStateException(MlKitSpeechAvailability.statusReason(status))
                }

                _state.update {
                    it.copy(
                        isRecording = true,
                        activeProjectId = projectId,
                        error = null,
                        engineLabel = "$ENGINE_LABEL (listening)",
                    )
                }

                val request = speechRecognizerRequest {
                    audioSource = AudioSource.fromMic()
                }
                activeRecognizer.startRecognition(request).collect { response ->
                    onRecognitionResponse(response, projectId)
                }
            }.onFailure { error ->
                if (error is CancellationException) return@onFailure
                fail(projectId, error.message ?: "ML Kit GenAI Speech failed")
            }
        }
    }

    private fun onRecognitionResponse(
        response: SpeechRecognizerResponse,
        projectId: Long,
    ) {
        when (response) {
            is SpeechRecognizerResponse.PartialTextResponse -> {
                _state.update {
                    it.copy(
                        partialText = response.text.trim(),
                        error = null,
                        engineLabel = "$ENGINE_LABEL (listening)",
                    )
                }
            }
            is SpeechRecognizerResponse.FinalTextResponse -> {
                appendCommitted(response.text)
            }
            is SpeechRecognizerResponse.CompletedResponse -> {
                if (recordingRequested && !controlledRestart) {
                    restartAfterUnexpectedCompletion(projectId)
                } else {
                    _state.update { it.copy(isRecording = false, partialText = "", engineLabel = ENGINE_LABEL) }
                }
            }
            is SpeechRecognizerResponse.ErrorResponse -> {
                fail(projectId, "${response.e.message} (${response.e.errorCode})")
            }
        }
    }

    private fun restartAfterUnexpectedCompletion(projectId: Long) {
        controlledRestart = true
        _state.update { it.copy(engineLabel = "$ENGINE_LABEL (restarting)") }
        engineScope.launch {
            stopAndCloseRecognizer()
            delay(RESTART_DEBOUNCE_MS)
            controlledRestart = false
            if (recordingRequested && activeProjectId == projectId) {
                startRecognition(projectId)
            }
        }
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

    private fun fail(projectId: Long, message: String) {
        recordingRequested = false
        engineScope.launch {
            stopAndCloseRecognizer()
        }
        _state.update {
            it.copy(
                isRecording = false,
                activeProjectId = projectId,
                partialText = "",
                error = message,
                engineLabel = ENGINE_LABEL,
            )
        }
    }

    private suspend fun stopAndCloseRecognizer() {
        val activeRecognizer = recognizer
        recognizer = null
        withContext(Dispatchers.IO) {
            runCatching { activeRecognizer?.stopRecognition() }
            runCatching { activeRecognizer?.close() }
        }
    }

    private fun String.normalized(): String = trim().lowercase().replace(Regex("\\s+"), " ")

    private companion object {
        const val ENGINE_LABEL = "ML Kit GenAI Speech engine"
        const val RESTART_DEBOUNCE_MS = 250L
    }
}
