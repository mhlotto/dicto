package com.mhlotto.dicto.speech

import android.content.Context

object WhisperModelConfig {
    fun defaultModelPath(context: Context): String {
        return WhisperModelManager.getDefaultModelFile(context).absolutePath
    }
}
