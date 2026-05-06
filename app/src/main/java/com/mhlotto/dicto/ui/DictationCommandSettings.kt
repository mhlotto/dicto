package com.mhlotto.dicto.ui

import android.content.Context
import android.content.SharedPreferences
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

data class DictationCommandSettingsState(
    val triggerPhrase: String = DictationCommandFormatter.DEFAULT_TRIGGER_PHRASE,
)

class DictationCommandSettings(context: Context) {
    private val prefs: SharedPreferences = context.applicationContext.getSharedPreferences(
        PREFS_NAME,
        Context.MODE_PRIVATE,
    )
    private val _state = MutableStateFlow(loadState())
    val state: StateFlow<DictationCommandSettingsState> = _state

    fun setTriggerPhrase(value: String) {
        val normalized = value.trim().replace(Regex("\\s+"), " ")
        prefs.edit().putString(KEY_TRIGGER_PHRASE, normalized).apply()
        _state.value = loadState()
    }

    private fun loadState(): DictationCommandSettingsState {
        val triggerPhrase = prefs.getString(
            KEY_TRIGGER_PHRASE,
            DictationCommandFormatter.DEFAULT_TRIGGER_PHRASE,
        )
            ?.takeIf { it.isNotBlank() }
            ?: DictationCommandFormatter.DEFAULT_TRIGGER_PHRASE
        return DictationCommandSettingsState(triggerPhrase = triggerPhrase)
    }

    private companion object {
        const val PREFS_NAME = "dictation_command_settings"
        const val KEY_TRIGGER_PHRASE = "trigger_phrase"
    }
}
