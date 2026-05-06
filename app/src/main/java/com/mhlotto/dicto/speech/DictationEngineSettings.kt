package com.mhlotto.dicto.speech

import android.content.Context
import android.content.SharedPreferences
import com.mhlotto.dicto.whisper.WhisperNative
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import java.io.File

enum class DictationEngineChoice {
    SpeechRecognizer,
    Whisper,
    Vosk,
    Auto,
}

data class DictationEngineSettingsState(
    val choice: DictationEngineChoice = DictationEngineChoice.Auto,
    val modelPath: String,
    val voskModelPath: String,
    val whisperNativeAvailable: Boolean = false,
    val whisperModelExists: Boolean = false,
    val voskModelExists: Boolean = false,
    val voskBundledModelExists: Boolean = false,
) {
    val selectedEngineLabel: String
        get() = when (choice) {
            DictationEngineChoice.SpeechRecognizer -> "SpeechRecognizer"
            DictationEngineChoice.Whisper -> "Whisper local"
            DictationEngineChoice.Vosk -> "Vosk local"
            DictationEngineChoice.Auto -> "Auto"
        }
}

class DictationEngineSettings(context: Context) {
    private val appContext = context.applicationContext
    private val prefs: SharedPreferences = appContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    private val defaultModelPath: String
        get() = WhisperModelConfig.defaultModelPath(appContext)

    private val _state = MutableStateFlow(loadState())
    val state: StateFlow<DictationEngineSettingsState> = _state

    fun setChoice(choice: DictationEngineChoice) {
        prefs.edit().putString(KEY_ENGINE_CHOICE, choice.name).apply()
        refresh()
    }

    fun setModelPath(path: String) {
        prefs.edit().putString(KEY_MODEL_PATH, path).apply()
        refresh()
    }

    fun refresh() {
        _state.value = loadState()
    }

    fun ensureDefaultModelAvailable(): File {
        return WhisperModelManager.ensureDefaultModelAvailable(appContext).also {
            if (prefs.getString(KEY_MODEL_PATH, null) == null) {
                prefs.edit().putString(KEY_MODEL_PATH, it.absolutePath).apply()
            }
            refresh()
        }
    }

    fun ensureDefaultVoskModelAvailable(): File {
        return VoskModelManager.ensureDefaultModelAvailable(appContext).also {
            refresh()
        }
    }

    fun importModel(displayName: String?, bytes: ByteArray): String {
        val modelDir = File(appContext.filesDir, "models").also { it.mkdirs() }
        val safeName = displayName
            ?.takeIf { it.endsWith(".bin", ignoreCase = true) }
            ?.replace(Regex("[^A-Za-z0-9._-]"), "_")
            ?: WhisperModelManager.getDefaultModelFile(appContext).name
        val modelFile = File(modelDir, safeName)
        modelFile.writeBytes(bytes)
        setModelPath(modelFile.absolutePath)
        return modelFile.absolutePath
    }

    private fun loadState(): DictationEngineSettingsState {
        val choiceName = prefs.getString(KEY_ENGINE_CHOICE, DictationEngineChoice.Auto.name)
        val choice = runCatching { DictationEngineChoice.valueOf(choiceName ?: "") }
            .getOrDefault(DictationEngineChoice.Auto)
        val modelPath = prefs.getString(KEY_MODEL_PATH, defaultModelPath) ?: defaultModelPath
        return DictationEngineSettingsState(
            choice = choice,
            modelPath = modelPath,
            voskModelPath = VoskModelManager.getDefaultModelDir(appContext).absolutePath,
            whisperNativeAvailable = WhisperNative.isAvailable,
            whisperModelExists = File(modelPath).isFile,
            voskModelExists = VoskModelManager.hasDefaultModel(appContext),
            voskBundledModelExists = VoskModelManager.hasBundledDefaultModel(appContext),
        )
    }

    private companion object {
        const val PREFS_NAME = "dictation_engine_settings"
        const val KEY_ENGINE_CHOICE = "engine_choice"
        const val KEY_MODEL_PATH = "model_path"
    }
}
