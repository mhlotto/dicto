package com.mhlotto.dicto.speech

import android.content.Context
import java.io.File

object WhisperModelManager {
    private const val ASSET_MODEL_PATH = "models/ggml-tiny.en.bin"
    private const val DEFAULT_MODEL_NAME = "ggml-tiny.en.bin"

    fun ensureDefaultModelAvailable(context: Context): File {
        val modelFile = getDefaultModelFile(context)
        modelFile.parentFile?.mkdirs()

        val assetSize = context.assets.openFd(ASSET_MODEL_PATH).use { it.length }
        if (modelFile.isFile && modelFile.length() == assetSize) {
            return modelFile
        }

        context.assets.open(ASSET_MODEL_PATH).use { input ->
            modelFile.outputStream().use { output ->
                input.copyTo(output)
            }
        }
        return modelFile
    }

    fun hasDefaultModel(context: Context): Boolean {
        return runCatching {
            val modelFile = getDefaultModelFile(context)
            val assetSize = context.assets.openFd(ASSET_MODEL_PATH).use { it.length }
            modelFile.isFile && modelFile.length() == assetSize
        }.getOrDefault(false)
    }

    fun getDefaultModelFile(context: Context): File {
        return File(File(context.filesDir, "models"), DEFAULT_MODEL_NAME)
    }
}
