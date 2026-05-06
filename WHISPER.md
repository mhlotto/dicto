# Local Whisper Engine

Dicto has a replaceable `DictationEngine` abstraction. The default engine remains Android
`SpeechRecognizer`. A local `whisper.cpp` engine is scaffolded for on-device testing.

## Model File

For development, Dicto packages Whisper models in:

```text
app/src/main/assets/models/
```

The default build uses `ggml-tiny.en.bin`. Override it at build time:

```bash
gradle :app:assembleDebug \
  -Pdicto.enableWhisperNative=true \
  -Pdicto.whisperModelAsset=ggml-base.en-q5_1.bin
```

On first launch or before Whisper engine selection, `WhisperModelManager` copies the configured
asset to:

```text
/data/data/com.mhlotto.dicto/files/models/<configured-model-file>
```

The copy only runs when the internal file is missing or its size differs from the asset.
`whisper.cpp` is always initialized from the internal filesystem path because it cannot load
directly from Android assets.

The in-app "Import Whisper .bin" button remains as an optional fallback for testing another model.
Imported model paths are stored in SharedPreferences.

This app is dev-installed only, so model assets can be packaged directly in the APK when that is
the most practical testing path.

## Native Source

Use the official MIT-licensed ggml-org project at:

```text
third_party/whisper.cpp
```

Then build with native support enabled:

```bash
gradle :app:assembleDebug -Pdicto.enableWhisperNative=true
```

The default Gradle build does not enable CMake. Native builds require the local `third_party`
checkout.

## Engine Selection

The app has a small debug section with three runtime choices:

- `SpeechRecognizer`
- `Whisper local`
- `Auto`

`Auto` uses Whisper only when:

- the native `dicto_whisper` library loads successfully,
- the configured model file exists,

Otherwise `Auto` falls back to Android `SpeechRecognizer`.

## ABI

The native configuration targets `arm64-v8a` first.

## Limitations

- Chunking is intentionally simple: 5 second chunks with 1 second overlap.
- Overlap de-duplication is basic string-prefix matching.
- The JNI wrapper uses `whisper_full` synchronously on a background coroutine.
- This is a replaceable first implementation, not the final continuous dictation strategy.
