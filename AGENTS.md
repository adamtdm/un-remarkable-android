# Un-remarkable for Android

Standalone Android companion app for reMarkable Paper Pro, RM2 and RM1. See [README.md](README.md) for features and [docs/ARCHITECTURE.md](docs/ARCHITECTURE.md) for how sync and rendering work.

## Tech stack
- Kotlin 2.0, Jetpack Compose + Material 3, min SDK 26, target SDK 34, JDK 21
- Sync: direct `sync15` REST over OkHttp (`core/cloud`), started from `MainActivity` (no WorkManager or WebSocket yet)
- Storage: JSON library file, content-addressed blob files and SharedPreferences (no Room)
- Rendering: custom `.rm` v3/v5/v6 parser (`core/render`) drawn with Android Canvas; PDFs with `PdfRenderer`
- Converter: on-device spread splitting and quality profiles (`core/converter`, `app/render/AndroidDocumentOptimizer.kt`)

## Commands
- Build debug APK: `./gradlew assembleDebug` (output in `app/build/outputs/apk/debug/`)
- Run all unit tests: `./gradlew test`

## Conventions
- Keep protocol, parsing and conversion logic in the JVM `core/*` modules, with tests that use `MockWebServer`.
- Every cloud write goes through `SyncEngine.editRemoteDoc` / `commitRoot`. Document indexes use the root's schema: in v4, a blob's name is the sha256 of its bytes.
- Never drop local-only user data on a failed network call.
