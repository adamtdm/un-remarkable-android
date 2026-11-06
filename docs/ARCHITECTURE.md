# Architecture

How Un-remarkable for Android is put together. For features and build steps, see the [README](../README.md).

## Principles

1. **No server.** The phone talks to the reMarkable cloud directly over HTTPS.
2. **Small core.** Protocol, parsing and conversion logic live in plain Kotlin/JVM modules (`core/*`) with unit tests. Android-only code stays in `app`.
3. **Never lose user data.** Local-only items stay local until the cloud confirms them, and every file write goes to a temp file first, then gets moved into place.

## Modules

```
            ┌──────────────────────────────┐
            │ app (Compose UI, Android I/O)│
            └──┬──────────┬──────────┬─────┘
               │          │          │
     ┌─────────▼──┐ ┌─────▼─────┐ ┌──▼─────────────┐
     │ core/cloud │ │core/render│ │ core/converter │
     └─────────┬──┘ └───────────┘ └──┬─────────────┘
               │                     │
            ┌──▼─────────────────────▼──┐
            │        core/model         │
            └───────────────────────────┘
```

| Module | What it does |
|---|---|
| `core/model` | `DocumentItem`, `DocumentMetadata` (parses `.metadata`/`.content`), index `Entry`, `DeviceProfile`, the `SyncRepository` interface |
| `core/cloud` | `AuthManager` (pairing, token refresh), `RMCloudClient` (HTTP with 401 refresh and 429/5xx backoff), `SyncEngine`, `IndexParser`, `FileBlobStore` |
| `core/render` | `RmBinaryParser` for `.rm` v3/v5/v6, scene model, `RmColor` (OKLCH inversion), `RmSvgRenderer` |
| `core/converter` | Spread detection and splitting, 5 quality stops, PDF writer |
| `app` | `MainActivity` (wires everything, runs sync), screens and dialogs, `PdfDocumentRenderer`, `RmPageCanvasRenderer`, `AndroidDocumentOptimizer`, storage (`PersistentDocumentRepository`, `AnnotationStore`, `AppSettings`, `AndroidTokenStorage`) |

## Authentication

1. The user gets an 8-character code from `my.remarkable.com/device/desktop/connect`.
2. `POST {auth host}/token/json/2/device/new` with `{code, deviceDesc, deviceID}` returns a long-lived **device token**.
3. `POST {auth host}/token/json/2/user/new` with the device token returns a short-lived **user token** (a JWT). The app refreshes it before it expires and again on any 401.

The auth host is `webapp-prod.cloud.remarkable.engineering`. Tokens live in private `SharedPreferences`, and the app is excluded from Android backups (`allowBackup="false"`).

## Sync (`sync15`)

The cloud stores files as content-addressed blobs. A **root index** (`root.docSchema`) lists one entry per document. Each document has its own index (`<id>.docSchema`) that lists its subfiles: `.metadata`, `.content`, `.pdf`, page `.rm` files and so on. The root carries a **generation** number, which works like a version counter: updating the root only succeeds if you send the generation you read.

`SyncEngine.sync()`:

1. Pushes queued reading positions.
2. Uploads local-only folders, then local-only PDFs. An uploaded PDF is retired locally straight away, because the cloud copy gets a new id. This means a failure later in the sync can't upload it twice.
3. Reads the root. If the hash and generation match what we last saw and nothing was uploaded, the sync stops here.
4. Downloads only document indexes whose hash changed, plus their `.metadata`/`.content` (3 at a time). Page payloads download on demand in the reader.
5. Marks local documents that are missing from the root as deleted. Documents whose parent is `trash` count as deleted.
6. Saves the new root hash and generation.

**Writing to the cloud** (reading position, move to trash, new folder) follows one pattern in `SyncEngine.editRemoteDoc` / `commitRoot`:

1. Rewrite the JSON subfile.
2. Upload it under its sha256.
3. Rebuild the document index with the same schema as the root. In schema v4, the index is named by the sha256 of its own bytes, and its header carries the document id.
4. Rebuild the root index, upload it, then `PUT /sync/v3/root` with the old generation.

The local root hash only moves forward if we were already in sync, so changes made on other devices are still pulled next time.

## Rendering

- **Notebooks:** `RmBinaryParser` reads tagged v6 blocks (layers, strokes, points with pressure and tilt, typed text). It also reads the older v3/v5 formats. `RmPageCanvasRenderer` draws the strokes with Android `Canvas`/`Path`, simulating each pen type.
- **PDFs:** Android `PdfRenderer` draws the pages, and any `.rm` annotation layer is drawn on top.
- **Dark mode:** each colour is converted to OKLCH, its lightness is inverted (`L' = 1 − L`), and the result is mapped back into the sRGB range. Black ink becomes light ink on dark paper, while hues and highlighters stay recognisable.

## Converter

- Inputs: PDF, CBZ/zip of images, single images.
- Device targets: Paper Pro 2160×1620, Paper Pro Move 1620×2160, RM2 and RM1 1872×1404.
- Quality stops: Smallest (1200×1600, q65), Compact (1440×1920, q72), Balanced (1620×2160, q82, recommended), Sharp (1920×2560, q88), Best (2250×3000, q92).
- A page wider than 1.15× its height counts as a two-page spread. It's split into two portrait pages, right half first in manga mode.
- The output PDF is added to the library as a local-only document and uploaded on the next sync.

## Local storage (app private files)

| Path | Contents |
|---|---|
| `files/blobs/<2-char prefix>/<sha256>` | Cached cloud blobs |
| `files/library_documents.json` | Library (document list, hashes, reading positions) |
| `files/annotations/` | On-phone ink and bookmarks, per document page |
| `shared_prefs/rm_auth_prefs` | Device and user tokens |
| `shared_prefs/rm_sync_state` | Last root hash and generation, pending position pushes |
| `cache/exports/` | Exported PDFs and sleep screens, shared through `FileProvider` |

## Not built yet

These ideas are not in the code yet:

- background sync with WorkManager
- WebSocket live updates
- full-text search with SQLite FTS5
- a Quick Settings tile and home screen widget
- EPUB conversion
