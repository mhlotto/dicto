# ML Kit GenAI Speech Engine

Dicto includes an experimental `MlKitGenAiSpeechDictationEngine` behind the same `DictationEngine`
interface as SpeechRecognizer, Vosk, and Whisper.

This engine uses Google's ML Kit GenAI Speech Recognition alpha API:

```text
com.google.mlkit:genai-speech-recognition:1.0.0-alpha1
```

It uses on-device models through AICore/ML Kit. Dicto does not add any cloud speech API or network
transcription path for this engine.

## Device Support

The API is alpha and device support varies.

- The library requires Android API 26+.
- Microphone recognition with Basic mode requires supported Android API 31+ devices.
- Advanced mode is currently limited to select newer devices, such as Pixel 10-class devices.
- Model availability depends on device support and installed AICore/ML Kit components.

Dicto currently uses Basic mode for the first integration because it is the broader-supported mode.
Advanced mode is intentionally not enabled yet.

## Runtime Behavior

The engine uses ML Kit's streaming recognition flow:

- partial responses update `DictationState.partialText`
- final responses append to `DictationState.committedText`
- completed sessions are restarted once in a controlled way when recording is still active
- errors are surfaced through `DictationState.error`

Unlike Android `SpeechRecognizer`, this engine does not use `android.speech.SpeechRecognizer`.
Unlike Vosk and Whisper, it depends on ML Kit GenAI/AICore device support.

## Availability

The engine checks:

- API level compatibility
- ML Kit feature status through `SpeechRecognizer.checkStatus()`
- model/device availability

If unavailable, it fails gracefully with a readable error. `Auto` mode does not select this alpha
engine; select `ML Kit GenAI` explicitly in the developer engine section.

## Limitations

- The API is alpha and may change.
- Unsupported devices will report unavailable instead of recording.
- Dicto does not automatically download ML Kit models yet.
- Continuous behavior depends on ML Kit's streaming session behavior; Dicto only does a minimal
  controlled restart after unexpected completion.
