package com.mhlotto.dicto.audio

import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

class PcmAudioRecorder(
    private val sampleRate: Int = SAMPLE_RATE_HZ,
) {
    @Volatile
    private var activeRecorder: AudioRecord? = null

    fun audioChunks(scope: CoroutineScope): Flow<FloatArray> = callbackFlow {
        val minBufferSize = AudioRecord.getMinBufferSize(
            sampleRate,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
        )
        if (minBufferSize == AudioRecord.ERROR || minBufferSize == AudioRecord.ERROR_BAD_VALUE) {
            close(IllegalStateException("16 kHz mono PCM recording is not supported on this device"))
            return@callbackFlow
        }

        val readBuffer = ShortArray((sampleRate / 10).coerceAtLeast(minBufferSize / 2))
        val recorder = AudioRecord.Builder()
            .setAudioSource(MediaRecorder.AudioSource.VOICE_RECOGNITION)
            .setAudioFormat(
                AudioFormat.Builder()
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .setSampleRate(sampleRate)
                    .setChannelMask(AudioFormat.CHANNEL_IN_MONO)
                    .build(),
            )
            .setBufferSizeInBytes(minBufferSize * 4)
            .build()
        if (recorder.state != AudioRecord.STATE_INITIALIZED) {
            recorder.release()
            close(IllegalStateException("Microphone recorder failed to initialize"))
            return@callbackFlow
        }

        val job: Job = scope.launch(Dispatchers.IO) {
            runCatching {
                activeRecorder = recorder
                recorder.startRecording()
                if (recorder.recordingState != AudioRecord.RECORDSTATE_RECORDING) {
                    error("Microphone recorder failed to start")
                }
                while (isActive && recorder.recordingState == AudioRecord.RECORDSTATE_RECORDING) {
                    val read = recorder.read(readBuffer, 0, readBuffer.size)
                    if (read > 0) {
                        val floats = FloatArray(read)
                        for (i in 0 until read) {
                            floats[i] = (readBuffer[i] / Short.MAX_VALUE.toFloat()).coerceIn(-1f, 1f)
                        }
                        trySend(floats)
                    }
                }
            }.onSuccess {
                close()
            }.onFailure { error ->
                close(error)
            }
        }

        awaitClose {
            job.cancel()
            runCatching { recorder.stop() }
            if (activeRecorder === recorder) activeRecorder = null
            recorder.release()
        }
    }

    fun stop() {
        runCatching { activeRecorder?.stop() }
    }

    companion object {
        const val SAMPLE_RATE_HZ = 16_000
    }
}
