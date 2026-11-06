# Un-remarkable for Android

A standalone Android companion app for **reMarkable Paper Pro**, **reMarkable 2** and **reMarkable 1**.

It talks directly to the reMarkable cloud (the `sync15` protocol), so there is no server, Docker container or desktop app to run. Everything (sync cache, rendering, file conversion) happens on your phone.

> **Unofficial.** This project is not affiliated with or endorsed by reMarkable AS. It uses the same undocumented cloud API as the official desktop apps, and that API can change without notice.

---

## Features

- **Cloud library sync**
  - Pair once with a one-time code from [my.remarkable.com](https://my.remarkable.com/device/desktop/connect).
  - Browse folders, pinned favourites and recent documents.
  - Downloads only what changed, and caches files by content hash.
  - Your reading position syncs both ways, so the tablet opens on the page you left off on your phone.
  - Deleting a document moves it to the reMarkable trash.
- **Reader**
  - Handwritten notebooks (`.rm` v3/v5/v6 stroke files) drawn as vectors, plus PDFs.
  - Dark mode inverts lightness in the OKLCH colour space, so highlighters and coloured pens keep their colour.
  - Choose between "Basalt Dark", "Warm Paper" or the system theme.
  - Fullscreen reading, swipe or edge-tap page turns, pinch-to-zoom, two-page spreads on foldables and tablets.
  - Page thumbnails, PDF table of contents, jump to page, and bookmarks.
  - Annotate on the phone and export an annotated PDF.
- **Converter**: optimise PDFs, comics (`.cbz`/zip) and images for your tablet's screen.
  - 5-stop quality slider, from Smallest to Best.
  - Splits two-page spreads, with a right-to-left order option for manga.
  - Profiles for Paper Pro, Paper Pro Move, RM2 and RM1.
  - Works from the Android share sheet.
- **Quick capture**: type a note or task and it becomes a PDF in your library, then syncs to the tablet.
- **Meeting notes**: record a meeting with live transcription (Android speech recognition). It becomes a notebook with your notes on page 1 and the transcript on page 2.
- **Sleep Screen Studio**: make a `suspended.png` with owner and return info.

## Install

Download the APK from the [Releases](https://github.com/adamtdm/un-remarkable-android/releases) page, or build it yourself.

Requires Android 8.0 (API 26) or newer.

## Build

You need JDK 21 and the Android SDK (set `sdk.dir` in `local.properties` or `ANDROID_HOME`).

```bash
./gradlew assembleDebug   # APK at app/build/outputs/apk/debug/
./gradlew test            # unit tests for all modules
```

## Project layout

```
app/              Android app: Compose UI, PDF/notebook rendering, local storage, meeting recorder
core/model/       Shared data types (documents, metadata, index entries, device profiles)
core/cloud/       Pairing, sync15 client, sync engine, content-addressed blob store
core/render/      .rm stroke file parser, OKLCH colours, SVG renderer
core/converter/   Comic/spread splitting, quality profiles, image-to-PDF writer
```

The `core` modules are plain Kotlin/JVM with no Android dependency, so their tests run fast on any machine.

See [docs/ARCHITECTURE.md](docs/ARCHITECTURE.md) for how sync and rendering work.

## Privacy

- Your reMarkable device token is stored in the app's private storage and is excluded from Android backups.
- The app only talks to reMarkable's servers. It has no analytics and sends nothing to any other server.
- Meeting transcription uses Android's built-in speech recogniser, which may use Google's servers, depending on your device.

## License

[MIT](LICENSE)
