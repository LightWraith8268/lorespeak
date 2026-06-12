# LoreSpeak

An on-device audiobook reader for Android. Import an EPUB, Markdown, or text file and LoreSpeak reads it aloud with a neural text-to-speech voice — fully offline, no account, no cloud.

Part of [Ink & Iron Apps](https://inknironapps.com).

## Features

- **On-device neural TTS** — narration runs entirely on the phone using [Kokoro-82M](https://huggingface.co/hexgrad/Kokoro-82M) via [sherpa-onnx](https://github.com/k2-fsa/sherpa-onnx). No internet required for reading.
- **Real audiobook playback** — background playback, lock-screen and Bluetooth controls, and a media notification with per-chapter progress.
- **Stream or Download** — stream with live synthesis, or download a book (full pre-render to disk) for flawless, gapless offline playback. A download manager shows all downloads, with bulk "download all".
- **Resume anywhere** — reading position is saved continuously per book; reopen and pick up where you left off.
- **Library of in-progress books** — import multiple books and jump back into any of them.
- **Chapter-accurate** — EPUB chapters come from the book's table of contents.
- **Adjustable speed** — 1.0×–2.0×, pitch-preserved.
- **Voice picker** — preview and choose from the bundled English voices.
- **In-app updates** — checks GitHub Releases and installs the latest build.

## Formats

EPUB (chapters from the table of contents), Markdown, and plain text.

## Requirements / supported devices

LoreSpeak runs a neural TTS model **on the CPU** (there is no usable mobile NPU path for this model), so device performance matters.

- **Architecture:** `arm64-v8a` (64-bit ARM) — **required**. No 32-bit or x86 libraries are bundled.
- **OS:** Android 8.0 (API 26) or newer.
- **RAM:** 4 GB or more recommended (the model + inference session use roughly 250–400 MB).
- **Storage:** ~160 MB for the app (model + native libraries), plus ~170 MB per hour of audio for any **downloaded** book.

**Streaming (live synthesis)** needs a phone fast enough to generate speech faster than it plays back. That means a recent **flagship or upper-mid-range SoC** — roughly 2023-or-newer flagship class (e.g. Google Tensor G3+, Snapdragon 8 Gen 2+, Dimensity 9000+). On slower chips, live streaming will stutter because synthesis can't keep up with playback.

Reference device: **Google Pixel 10 Pro XL (Tensor G5)** — sustained realtime factor ≈ 0.72 (synthesizes ~1.4 s of audio per second of compute), comfortably ahead of 1.25× playback.

**Download mode works on any arm64 device.** Because it pre-renders the whole book to disk before (or while) you listen, playback becomes simple file reading with no realtime requirement — lower-end phones just take longer to render. If your phone stutters while streaming, download the book first.

## Building

The Kokoro model and the sherpa-onnx native libraries are large binaries that are **not** committed to the repository (the model exceeds GitHub's file-size limit). Fetch them once before building:

```bash
./scripts/fetch-assets.sh
./gradlew :app:assembleDebug
```

Requirements: JDK 17, Android SDK (compileSdk 35). The debug APK is produced at `app/build/outputs/apk/debug/app-debug.apk`.

All builds are signed with the committed shared debug keystore so the in-app updater can install updates over an existing install.

## Continuous integration

- **CI** builds a debug APK on every pull request and on pushes to `claude/dev`.
- Pushing to `claude/dev` merges into `main`.
- Merging to `main` computes the next version from conventional commits and publishes a GitHub Release containing the debug APK, which the in-app updater polls.

## License

LoreSpeak is licensed under the **GNU General Public License v3.0** — see [`LICENSE`](LICENSE).

It bundles third-party components, including **espeak-ng** (GPL-3.0, statically linked for grapheme-to-phoneme conversion), which is why the project as a whole is GPL-3.0. The Kokoro model is Apache-2.0 and sherpa-onnx is MIT. Full attributions are in [`THIRD-PARTY-NOTICES.md`](THIRD-PARTY-NOTICES.md).
