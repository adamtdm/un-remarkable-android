# Un-remarkable for Android

A standalone, zero-server Android companion app for **reMarkable Paper Pro**, **reMarkable 2**, and **reMarkable 1**.

Sync directly with the official reMarkable cloud (`sync15`), read handwritten notes and PDFs in perceptual OKLCH dark mode, optimize and upload heavy comic/document files on-device, and capture quick thoughts without requiring Docker or any external server infrastructure.

---

## Key Features

- **100% Standalone (No Docker Required):** Connects directly from your Android device to reMarkable Cloud (`my.remarkable.com` & `internal.cloud.remarkable.com`). All processing, caching, and conversions run locally on your phone.
- **Direct Cloud Sync (`sync15`):** Continuous background synchronization using Android `WorkManager` and WebSocket live push updates. Stores content-addressed blobs securely in local application storage.
- **On-Device Comic & Document Converter:**
  - 5-stop quality slider (Smallest to Best).
  - Splitting double-page spreads into portrait reading orientation.
  - Manga reading mode (right-to-left split ordering).
  - Native screen targets: **reMarkable Paper Pro** (2160×1620 Color Canvas), **RM2** (1872×1404 Monochrome), and **RM1**.
  - Direct system Share sheet integration (e.g. share CBZ/EPUB/PDF from your file manager or browser directly to your reMarkable tablet).
- **Perceptual OKLCH Dark Mode Vector Reader:**
  - Native on-device parsing of `.rm` v6 binary stroke files.
  - Vector rendering with stroke pressure, tilt, and authentic brush simulation.
  - Perceptual lightness inversion preserving highlighter colors, pen hues, and grayscale shades.
  - Fullscreen distraction-free reading with edge-tap navigation and auto-hiding HUD controls.
- **Quick Capture & Task Consolidation:**
  - Instant note capture and `- [ ]` task addition directly to your synced "Tasks" or "Quick Notes" notebooks.
  - Quick Settings Tile and Home Screen Widget for single-tap capture without opening the app.
- **Local Full-Text Search (SQLite FTS5):**
  - Offline search across notebook titles, typed text, tags, and document metadata.

---

## Architectural Blueprint

```
un-remarkable-android/
├── app/                  # Jetpack Compose UI (Library, Reader, Converter, Capture)
├── core/
│   ├── cloud/            # sync15 client, auth pairing, WebSocket notifications
│   ├── converter/        # On-device PDF/EPUB/CBZ optimizer & spread splitter
│   ├── database/         # Room DB + SQLite FTS5 search index
│   ├── model/            # Common domain entities (Document, Page, Folder, Task)
│   └── render/           # .rm v6 binary parser & OKLCH color engine
└── docs/                 # Architecture specifications & protocol documentation
```

See [docs/ARCHITECTURE.md](docs/ARCHITECTURE.md) for full design specifications.

---

## License

GPL-3.0 / MIT (Open Source).
