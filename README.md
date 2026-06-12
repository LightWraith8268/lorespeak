# LoreSpeak

An on-device audiobook reader for Android. Import an EPUB, Markdown, or text file and LoreSpeak reads it aloud with a neural text-to-speech voice — fully offline, no account, no cloud.

Part of [Ink & Iron Apps](https://inknironapps.com).

## Features

- **On-device neural TTS** — narration runs entirely on the phone using [Kokoro-82M](https://huggingface.co/hexgrad/Kokoro-82M) via [sherpa-onnx](https://github.com/k2-fsa/sherpa-onnx). No internet required after install.
- **Real audiobook playback** — background playback, lock-screen and Bluetooth controls, and a media notification with per-chapter progress.
- **Resume anywhere** — reading position is saved continuously per book; reopen and pick up where you left off.
- **Library of in-progress books** — import multiple books and jump back into any of them.
- **Stream or Download** — stream with live synthesis, or download a book (full pre-render to disk) for flawless, gapless offline playback.
- **Adjustable speed** — 1.0×–2.0×, pitch-preserved.
- **Voice picker** — preview and choose from the bundled English voices.

## Formats

EPUB (chapters from the book's table of contents), Markdown, and plain text.

## Building

The Kokoro model and the sherpa-onnx native libraries are large binaries that are **not** committed to the repository (the model exceeds GitHub's file-size limit). Fetch them once before building:

```bash
./scripts/fetch-assets.sh
./gradlew :app:assembleDebug
```

Requirements: JDK 17, Android SDK (compileSdk 35), an `arm64-v8a` device or emulator.

The debug APK is produced at `app/build/outputs/apk/debug/app-debug.apk`.

## Continuous integration

- **CI** builds a debug APK on every pull request and on pushes to `claude/dev`.
- Pushing to `claude/dev` opens and merges a pull request into `main`.
- Merging to `main` computes the next version from conventional commits and publishes a GitHub Release containing the debug APK.
- Releases are mirrored to the public [`lorespeak-releases`](https://github.com/LightWraith8268/lorespeak-releases) repo so the in-app updater can poll them without authentication. This requires a fine-grained PAT secret `PUBLIC_RELEASES_TOKEN` (Contents: write on the mirror repo) on this repository; without it the mirror step is skipped.

All builds are signed with the committed shared debug keystore so the in-app updater can install updates over an existing install.

## Licensing

LoreSpeak bundles third-party components, fetched at build time:

- **Kokoro-82M** — Apache-2.0
- **sherpa-onnx** — MIT
- **espeak-ng** (statically linked inside the sherpa-onnx native library, used for grapheme-to-phoneme conversion) — **GPL-3.0**

Because espeak-ng is GPL-3.0 and linked into the shipped binary, distributing the built APK carries GPL-3.0 obligations.
