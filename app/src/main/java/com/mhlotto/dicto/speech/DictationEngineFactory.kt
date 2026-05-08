package com.mhlotto.dicto.speech

import android.content.Context

class DictationEngineFactory(
    private val context: Context,
    private val settings: DictationEngineSettings,
) {
    fun create(): DictationEngine {
        val initialState = settings.state.value
        if (initialState.choice == DictationEngineChoice.Auto || initialState.choice == DictationEngineChoice.Whisper) {
            runCatching { settings.ensureDefaultModelAvailable() }
        }
        if (initialState.choice == DictationEngineChoice.Auto || initialState.choice == DictationEngineChoice.Vosk) {
            runCatching { settings.ensureDefaultVoskModelAvailable() }
        }
        val state = settings.state.value
        val voskCanLoad = state.voskModelExists && VoskModelManager.canLoadDefaultModel(context.applicationContext)
        return when (state.choice) {
            DictationEngineChoice.SpeechRecognizer -> SpeechRecognizerDictationEngine(context.applicationContext)
            DictationEngineChoice.Whisper -> WhisperDictationEngine(modelPath = state.modelPath)
            DictationEngineChoice.Vosk -> VoskDictationEngine(context.applicationContext)
            DictationEngineChoice.MlKitGenAi -> MlKitGenAiSpeechDictationEngine(context.applicationContext)
            DictationEngineChoice.Auto -> when {
                state.whisperNativeAvailable && state.whisperModelExists -> WhisperDictationEngine(modelPath = state.modelPath)
                voskCanLoad -> VoskDictationEngine(context.applicationContext)
                else -> SpeechRecognizerDictationEngine(context.applicationContext)
            }
        }
    }
}
