# Third-Party Notices

LoreSpeak is distributed under the GNU General Public License v3.0 (see `LICENSE`). It builds on the
following third-party components. The large binaries are fetched at build time by
`scripts/fetch-assets.sh` and are not stored in this repository.

| Component | Use | License |
|---|---|---|
| [Kokoro-82M](https://huggingface.co/hexgrad/Kokoro-82M) | Neural text-to-speech model | Apache-2.0 |
| [sherpa-onnx](https://github.com/k2-fsa/sherpa-onnx) | On-device TTS runtime + Kotlin API (`Tts.kt`) | MIT |
| [espeak-ng](https://github.com/espeak-ng/espeak-ng) | Grapheme-to-phoneme, statically linked into the sherpa-onnx native library | **GPL-3.0** |
| [ONNX Runtime](https://github.com/microsoft/onnxruntime) | Model inference engine (inside the sherpa-onnx libraries) | MIT |
| [jsoup](https://jsoup.org/) | EPUB/HTML parsing | MIT |
| AndroidX / Jetpack Compose / Media3 | UI and media playback | Apache-2.0 |

## Why GPL-3.0

The shipped APK statically links **espeak-ng**, which is licensed under GPL-3.0. Distributing a binary
that links GPL-3.0 code makes the combined work subject to GPL-3.0, so LoreSpeak is released under
GPL-3.0 as a whole. The generated audio output is not a derivative work of these tools and is not
covered by these licenses.
