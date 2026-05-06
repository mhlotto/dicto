package com.mhlotto.dicto.speech

import android.content.Context
import android.content.res.AssetManager
import org.vosk.Model
import java.io.File
import java.io.FileNotFoundException

object VoskModelManager {
    private const val MODEL_DIR_NAME = "vosk-model-small-en-us-0.15"
    private const val ASSET_MODEL_DIR = "models/$MODEL_DIR_NAME"

    fun ensureDefaultModelAvailable(context: Context): File {
        val modelDir = getDefaultModelDir(context)
        if (hasDefaultModel(context)) return modelDir

        if (!hasBundledDefaultModel(context)) {
            throw FileNotFoundException("Bundled Vosk model missing at assets/$ASSET_MODEL_DIR")
        }

        modelDir.deleteRecursively()
        modelDir.mkdirs()
        copyAssetDirectory(context.assets, ASSET_MODEL_DIR, modelDir)
        return modelDir
    }

    fun hasDefaultModel(context: Context): Boolean {
        val modelDir = getDefaultModelDir(context)
        return modelDir.isDirectory &&
            File(modelDir, "am/final.mdl").isFile &&
            File(modelDir, "conf/model.conf").isFile
    }

    fun getDefaultModelDir(context: Context): File {
        return File(File(context.filesDir, "models"), MODEL_DIR_NAME)
    }

    fun canLoadDefaultModel(context: Context): Boolean {
        if (!hasDefaultModel(context)) return false
        return runCatching {
            val model = Model(getDefaultModelDir(context).absolutePath)
            model.close()
        }.isSuccess
    }

    fun hasBundledDefaultModel(context: Context): Boolean {
        return context.assets.list(ASSET_MODEL_DIR)
            ?.isNotEmpty()
            ?: false
    }

    private fun copyAssetDirectory(
        assetManager: AssetManager,
        assetPath: String,
        target: File,
    ) {
        val children = assetManager.list(assetPath).orEmpty()
        if (children.isEmpty()) {
            target.parentFile?.mkdirs()
            assetManager.open(assetPath).use { input ->
                target.outputStream().use { output ->
                    input.copyTo(output)
                }
            }
            return
        }

        target.mkdirs()
        for (child in children) {
            copyAssetDirectory(
                assetManager = assetManager,
                assetPath = "$assetPath/$child",
                target = File(target, child),
            )
        }
    }
}
