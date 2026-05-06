# Local Whisper Engine

Dicto has a replaceable `DictationEngine` abstraction. The default engine remains Android
`SpeechRecognizer`. A local `whisper.cpp` engine is included in the same dev-installed APK as the
other dictation engines.

## Model File

For development, Dicto packages Whisper models in:

```text
app/src/main/assets/models/
```

The default build uses `ggml-tiny.en.bin` as the initial packaged Whisper model. Additional `.bin`
models may also live in the assets directory for development, and the in-app import button remains
available for testing another model path.

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
gradle :app:assembleDebug
```

The Gradle build always enables CMake now. Native builds require the local `third_party` checkout.

## Engine Selection

The app has a small debug section with runtime choices:

- `SpeechRecognizer`
- `Whisper local`
- `Vosk local`
- `Auto`

`Auto` uses Whisper only when:

- the native `dicto_whisper` library loads successfully,
- the configured model file exists,

Otherwise `Auto` tries Vosk when its bundled model exists and can be opened, then falls back to
Android `SpeechRecognizer`.

## ABI

The native configuration targets `arm64-v8a` first.

## Limitations

- Chunking is intentionally simple: 5 second chunks with 1 second overlap.
- Overlap de-duplication is basic string-prefix matching.
- The JNI wrapper uses `whisper_full` synchronously on a background coroutine.
- This is a replaceable first implementation, not the final continuous dictation strategy.
