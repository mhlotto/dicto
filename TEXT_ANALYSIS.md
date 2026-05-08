# Text Analysis

Dicto has a small `TextAnalyzer` abstraction for analyzing saved note text. The first implementation
is `MlKitEntityTextAnalyzer`, backed by ML Kit Entity Extraction.

## ML Kit Entity Extraction

Dependency:

```text
com.google.mlkit:entity-extraction:16.0.0-beta6
```

The analyzer uses English entity extraction:

```text
EntityExtractorOptions.ENGLISH
```

When the user taps `Analyze text` on a saved note edit screen, Dicto:

- creates an ML Kit entity extractor
- calls `downloadModelIfNeeded()`
- calls `annotate(noteText)`
- maps ML Kit annotations into app-level `ExtractedEntity` objects
- shows results grouped in the edit screen
- closes the extractor

Results are in memory only for now. They are not persisted to Room.

## Privacy

Entity extraction runs through ML Kit on-device APIs. Dicto does not send note text to a server.
Network may be needed the first time ML Kit downloads the English entity extraction model.

## Displayed Groups

The UI groups extracted entities into:

- Dates and times
- Addresses
- Phone numbers
- Emails
- URLs
- Money
- Other

Each result shows extracted text, entity type, and any simple metadata exposed by ML Kit.
