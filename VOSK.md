# Vosk Local Dictation

Dicto includes a `VoskDictationEngine` behind the same `DictationEngine` interface used by SpeechRecognizer and Whisper. It runs fully local/offline through `com.alphacephei:vosk-android` and does not use Android `SpeechRecognizer`, cloud APIs, or network permissions.

## Model Asset

For development builds, place the Vosk small English model directory here:

```text
app/src/main/assets/models/vosk-model-small-en-us-0.15/
```

On first use, Dicto copies that asset directory to:

```text
context.filesDir/models/vosk-model-small-en-us-0.15/
```

Vosk needs a real filesystem directory, so the engine never loads directly from assets. The expected copied model should include at least:

```text
am/final.mdl
conf/model.conf
```

The current repo does not implement model downloading. If the asset directory is missing, the debug section reports Vosk as missing and the Vosk engine returns a useful startup error.

## Engine Behavior

Vosk uses `org.vosk.Model`, `org.vosk.Recognizer`, and `org.vosk.android.SpeechService` at `16000.0f` Hz. Partial JSON results update `DictationState.partialText`; final JSON results append to `DictationState.committedText` and clear the partial.

Unlike SpeechRecognizer, Vosk is meant to listen continuously, so Dicto does not run a restart loop for normal final results. It only performs a controlled restart if `SpeechService` stops unexpectedly while recording is still active, or once after `onTimeout`.

## Tradeoffs

Vosk is simpler to integrate than `whisper.cpp` and avoids Android recognizer beeps because it does not use Android `SpeechRecognizer`. It should be more continuous and lower latency than Whisper on-device, but recognition quality is typically lower than Whisper, especially with small models.
