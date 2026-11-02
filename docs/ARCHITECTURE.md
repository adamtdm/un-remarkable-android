# Un-remarkable Android Architecture Specification

This document details the architectural blueprint for the standalone Un-remarkable Android companion application.

---

## 1. Design Principles & Goals

1. **Zero External Server Dependency:**
   The Android application runs 100% autonomously. It does not require a Docker container, Python backend, or MCP host. It speaks directly to reMarkable's cloud infrastructure via HTTPS and WebSockets.
2. **Battery & Data Efficiency:**
   Utilizes Android `WorkManager` with network constraints for periodic library metadata refreshes, and foreground WebSockets for real-time synchronization while actively browsing.
3. **High-Performance On-Device Media Processing:**
   Executes document optimization (PDF, EPUB, CBZ) using native Android graphics and memory-mapped buffers to prevent out-of-memory errors on large graphic novels.
4. **Authentic Vector Rendering:**
   Renders native `.rm` v6 binary stroke trees using Android `Canvas` / `Path` commands with perceptual OKLCH dark mode color mapping.

---

## 2. Module Breakdown

```
┌─────────────────────────────────────────────────────────────┐
│                      :app (UI Layer)                        │
│  - Jetpack Compose + Material 3                             │
│  - Fullscreen Reader HUD & OKLCH Dark Mode                  │
│  - Converter View & Device Target Selector                  │
│  - Quick Capture Bottom Sheet / Dialog                      │
│  - Quick Settings Tile & App Widget                         │
└──────────────┬───────────────────────────────┬──────────────┘
               │                               │
┌──────────────▼──────────────┐ ┌──────────────▼──────────────┐
│       :core:converter       │ │        :core:render         │
│ - PDF/EPUB/CBZ processing   │ │ - .rm v6 binary parser      │
│ - 5-stop quality downscale  │ │ - Vector stroke generator   │
│ - 2-page spread splitter    │ │ - OKLCH shader & palette    │
│ - Native hardware profiles  │ │ - Distraction-free HUD      │
└──────────────┬──────────────┘ └──────────────┬──────────────┘
               │                               │
┌──────────────▼───────────────────────────────▼──────────────┐
│                      :core:database                         │
│ - Room Database (Documents, Folders, Blobs, Tasks)          │
│ - SQLite FTS5 Full-Text Search Table                        │
│ - Local content-addressed blob storage (filesDir/blobs/)     │
└──────────────────────────────┬──────────────────────────────┘
                               │
┌──────────────────────────────▼──────────────────────────────┐
│                        :core:cloud                          │
│ - Device Pairing (my.remarkable.com/token/json/2/device/new)│
│ - User Token Refresh (my.remarkable.com/token/json/2/user/new│
│ - sync15 Protocol (Root tree, manifests, blob downloads)    │
│ - WebSocket Push Notification Client                        │
│ - EncryptedSharedPreferences (Hardware-backed Keystore)     │
└─────────────────────────────────────────────────────────────┘
```

---

## 3. Core Subsystems

### 3.1 Authentication & sync15 Cloud Client (`:core:cloud`)

- **Pairing Flow:**
  1. User navigates to `my.remarkable.com/device/browser/connect` and copies the 8-character code.
  2. Android app sends a `POST` request to `https://my.remarkable.com/token/json/2/device/new` with:
     ```json
     {
       "code": "abcdefgh",
       "deviceDesc": "android",
       "deviceID": "<uuid>"
     }
     ```
  3. Returns a permanent Device Token stored in Android's `EncryptedSharedPreferences`.
  4. Device token is exchanged for temporary User Tokens via `https://my.remarkable.com/token/json/2/user/new`.

- **Sync Protocol:**
  - Connects to WebSocket notification service for document change notifications.
  - Pulls latest sync root generation from `internal.cloud.remarkable.com/sync/v1/root`.
  - Walks the generation tree to identify added, modified, or deleted document manifests.
  - Downloads only missing blobs into `context.filesDir/blobs/{hash}` (content-addressed store).

### 3.2 On-Device Document & Comic Converter (`:core:converter`)

- **Input Formats:** `.pdf`, `.cbz` (zip of JPEGs/PNGs/WebPs), `.epub` (extracts image spine or XHTML text).
- **Device Profiles:**
  - **reMarkable Paper Pro:** 2160 × 1620 (229 PPI, Color Canvas)
  - **reMarkable Paper Pro Move:** 1620 × 2160 (Portrait)
  - **reMarkable 2:** 1872 × 1404 (226 PPI, Grayscale)
  - **reMarkable 1:** 1872 × 1404 (226 PPI, Grayscale)
- **5-Stop Quality Presets:**
  - Stop 1 (Smallest): Max 1600px, JPEG q65 (~75% reduction)
  - Stop 2 (Compact): Max 1920px, JPEG q72 (~65% reduction)
  - Stop 3 (Balanced - Recommended): 1:1 screen match, JPEG q82 (~50% reduction)
  - Stop 4 (Sharp): High-res, JPEG q88 (~40% reduction)
  - Stop 5 (Best): Max fidelity, JPEG q92 (~10% reduction)
- **Spread Splitting & Orientation:**
  - Evaluates aspect ratio: if `width > height * 1.15`, treats as a two-page spread.
  - Splits left half and right half into two separate portrait pages.
  - Manga mode swaps ordering: splits right half first, then left half.
- **Output:** Writes clean single-layer PDF using `android.graphics.pdf.PdfDocument`, creates `.content` and `.pagedata` metadata, and pushes directly to reMarkable cloud.

### 3.3 Vector `.rm` Parser & OKLCH Dark Mode (`:core:render`)

- **`.rm` v6 Binary Format:**
  - Header: `reMarkable .lines file, version=6`
  - Tagged blocks: Layers, stroke groups, anchor points, pen type, color, thickness.
  - Text item CRDT: typed text characters, paragraphs, formatting styles.
- **Perceptual OKLCH Inversion:**
  - Convert sRGB color to OKLCH $(L, C, H)$.
  - Invert luminance: $L_{new} = 1.0 - L_{old}$.
  - Apply gamut mapping to prevent chromatic saturation clipping.
  - Result: Highlighters and colored annotations remain vibrant, while dark ink on white canvas transforms into crisp light ink on pure dark background without discoloration.

### 3.4 Quick Capture & Task Intelligence (`:app` & `:core:database`)

- Fast input modal triggered from app, Quick Settings Tile, or Android Share Sheet.
- Modes:
  - **Task:** Formats item as `- [ ] <text>` and appends to the tablet's designated "Tasks" notebook.
  - **Quick Note:** Formats item with ISO timestamp and appends to "Quick Notes".
- Automatically triggers a sync generation upload so the note appears on the user's tablet immediately.
