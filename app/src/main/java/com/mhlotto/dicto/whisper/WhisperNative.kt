package com.mhlotto.dicto.whisper

class WhisperNative {
    private var nativeHandle: Long = 0

    fun initialize(modelPath: String) {
        if (nativeHandle != 0L) release()
        nativeHandle = nativeInitialize(modelPath)
        if (nativeHandle == 0L) {
            error("Failed to initialize whisper model at $modelPath")
        }
    }

    fun transcribePcm(samples: FloatArray): String {
        check(nativeHandle != 0L) { "Whisper model is not initialized" }
        return nativeTranscribePcm(nativeHandle, samples)
    }

    fun release() {
        val handle = nativeHandle
        nativeHandle = 0
        if (handle != 0L) nativeRelease(handle)
    }

    private external fun nativeInitialize(modelPath: String): Long
    private external fun nativeTranscribePcm(handle: Long, samples: FloatArray): String
    private external fun nativeRelease(handle: Long)
    private external fun nativeIsWhisperBuilt(): Boolean

    companion object {
        val isAvailable: Boolean by lazy {
            runCatching {
                System.loadLibrary("dicto_whisper")
                WhisperNative().nativeIsWhisperBuilt()
            }.getOrDefault(false)
        }
    }
}
