package com.mhlotto.dicto.speech

import android.content.Context
import com.mhlotto.dicto.BuildConfig
import java.io.File

object WhisperModelManager {
    private val assetModelName: String
        get() = BuildConfig.WHISPER_MODEL_ASSET

    private val assetModelPath: String
        get() = "models/$assetModelName"

    fun ensureDefaultModelAvailable(context: Context): File {
        val modelFile = getDefaultModelFile(context)
        modelFile.parentFile?.mkdirs()

        val assetSize = context.assets.openFd(assetModelPath).use { it.length }
        if (modelFile.isFile && modelFile.length() == assetSize) {
            return modelFile
        }

        context.assets.open(assetModelPath).use { input ->
            modelFile.outputStream().use { output ->
                input.copyTo(output)
            }
        }
        return modelFile
    }

    fun hasDefaultModel(context: Context): Boolean {
        return runCatching {
            val modelFile = getDefaultModelFile(context)
            val assetSize = context.assets.openFd(assetModelPath).use { it.length }
            modelFile.isFile && modelFile.length() == assetSize
        }.getOrDefault(false)
    }

    fun getDefaultModelFile(context: Context): File {
        return File(File(context.filesDir, "models"), assetModelName)
    }
}
