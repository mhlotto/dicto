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
        val state = settings.state.value
        val useWhisper = when (state.choice) {
            DictationEngineChoice.SpeechRecognizer -> false
            DictationEngineChoice.Whisper -> true
            DictationEngineChoice.Auto -> state.whisperNativeAvailable && state.whisperModelExists
        }
        return if (useWhisper) {
            WhisperDictationEngine(modelPath = state.modelPath)
        } else {
            SpeechRecognizerDictationEngine(context.applicationContext)
        }
    }
}
