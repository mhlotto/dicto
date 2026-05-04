package com.mhlotto.dicto.speech

import kotlinx.coroutines.flow.StateFlow

interface DictationEngine {
    val state: StateFlow<DictationState>

    fun start(projectId: Long)
    fun stop()
    fun cancel()
}

data class DictationState(
    val isRecording: Boolean = false,
    val activeProjectId: Long? = null,
    val committedText: String = "",
    val partialText: String = "",
    val error: String? = null,
    val engineLabel: String = "Dictation engine idle",
) {
    val rollingText: String
        get() = listOf(committedText, partialText)
            .filter { it.isNotBlank() }
            .joinToString(" ")
            .trim()
}
