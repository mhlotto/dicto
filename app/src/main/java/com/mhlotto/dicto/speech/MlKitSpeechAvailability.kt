package com.mhlotto.dicto.speech

import android.content.Context
import android.os.Build
import com.google.mlkit.genai.common.FeatureStatus
import com.google.mlkit.genai.speechrecognition.SpeechRecognition
import com.google.mlkit.genai.speechrecognition.SpeechRecognizerOptions
import com.google.mlkit.genai.speechrecognition.speechRecognizerOptions
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import java.util.Locale

object MlKitSpeechAvailability {
    fun isAvailable(context: Context): Boolean {
        if (apiCompatibilityReason() != null) return false
        return runBlocking {
            withContext(Dispatchers.IO) {
                runCatching {
                    val recognizer = createBasicRecognizer()
                    try {
                        recognizer.checkStatus() == FeatureStatus.AVAILABLE
                    } finally {
                        recognizer.close()
                    }
                }.getOrDefault(false)
            }
        }
    }

    fun availabilityReason(context: Context): String? {
        apiCompatibilityReason()?.let { return it }
        return runBlocking {
            withContext(Dispatchers.IO) {
                runCatching {
                    val recognizer = createBasicRecognizer()
                    try {
                        statusReason(recognizer.checkStatus())
                    } finally {
                        recognizer.close()
                    }
                }.getOrElse { error ->
                    error.message ?: "ML Kit GenAI Speech is unavailable on this device"
                }
            }
        }
    }

    fun apiCompatibilityReason(): String? {
        return when {
            Build.VERSION.SDK_INT < Build.VERSION_CODES.O ->
                "ML Kit GenAI Speech requires Android API 26+"
            Build.VERSION.SDK_INT < Build.VERSION_CODES.S ->
                "ML Kit GenAI Speech microphone input requires Android API 31+"
            else -> null
        }
    }

    fun statusReason(status: Int): String? {
        return when (status) {
            FeatureStatus.AVAILABLE -> null
            FeatureStatus.DOWNLOADABLE ->
                "ML Kit GenAI Speech model is downloadable but not installed"
            FeatureStatus.DOWNLOADING ->
                "ML Kit GenAI Speech model download is already in progress"
            FeatureStatus.UNAVAILABLE ->
                "ML Kit GenAI Speech is unavailable on this device"
            else -> "ML Kit GenAI Speech returned unknown availability status: $status"
        }
    }

    fun createBasicRecognizer() = SpeechRecognition.getClient(
        speechRecognizerOptions {
            locale = Locale.US
            preferredMode = SpeechRecognizerOptions.Mode.MODE_BASIC
        },
    )

    @Suppress("UNUSED_PARAMETER")
    private fun Context.keepSignatureStable() = Unit
}
