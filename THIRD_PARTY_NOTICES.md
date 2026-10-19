# Third-party models, libraries and tools

None of the files below are stored in this repository. The scripts download them from their publishers at pinned
versions and check their SHA-256 checksums (`scripts/model-sources.json`, `scripts/setup-tooling.sh`). Each comes
under its own licence, which you accept by downloading and using it.

## Models on the phone

Fetched by `scripts/fetch-models.py` and copied to the phone by `scripts/push-models.sh`.

| Model | Used for | Licence | Download |
| --- | --- | --- | --- |
| Silero VAD | Finding speech | MIT | sherpa-onnx release `asr-models`, about 0.6 MB |
| NVIDIA Parakeet TDT 0.6B v2, int8 (sherpa-onnx export) | Speech to text | CC-BY-4.0 | sherpa-onnx release `asr-models`, about 480 MB |
| NVIDIA Nemotron 3 Diarization, ONNX conversion by Neal Caren | Who spoke when | OpenMDW-1.1 | Hugging Face `NealCaren/Nemotron-3-Diarization-ONNX`, about 105 MB |
| WeSpeaker ResNet34-LM, VoxCeleb (sherpa-onnx export) | Voice profiles and voiceprints | WeSpeaker code Apache-2.0; check the model's own terms | sherpa-onnx release `speaker-recongition-models`, about 27 MB |
| pyannote segmentation 3.0 (sherpa-onnx export) | Benchmark comparison only | MIT | sherpa-onnx release `speaker-segmentation-models`, about 7 MB |
| EmbeddingGemma 2, 270M (LiteRT-LM) | Search | Apache-2.0 | Hugging Face `litert-community/embeddinggemma-2-text-270m-litert-lm`, about 165 MB |
| Gemma 4 E4B (LiteRT-LM) | Notes and answers | Apache-2.0 | Hugging Face `litert-community/gemma-4-E4B-it-litert-lm`, about 3.7 GB |
| Gemma 4 E2B (LiteRT-LM), optional | Faster answers and notes | Apache-2.0 | Hugging Face `litert-community/gemma-4-E2B-it-litert-lm`, about 2.6 GB |

## Libraries in the app

| Library | Licence |
| --- | --- |
| sherpa-onnx 1.13.8, with ONNX Runtime linked in (fetched into `app/libs/` by `fetch-models.py`) | Apache-2.0; ONNX Runtime MIT |
| ONNX Runtime for Android 1.31.0 | MIT |
| LiteRT-LM 0.18.0 | Apache-2.0 |
| AndroidX (Core, Activity, Lifecycle, Navigation, Room) and Jetpack Compose | Apache-2.0 |
| Kotlin standard library, kotlinx.coroutines, kotlinx.serialization | Apache-2.0 |

Tests only: JUnit 4.13.2 (EPL-1.0) and ONNX Runtime for the JVM 1.30.0 (MIT).

## Development tools

Installed into `.tooling/` by `scripts/setup-tooling.sh`; they are not part of the app.

| Tool | Licence |
| --- | --- |
| Eclipse Temurin JDK 21 | GPL-2.0 with the Classpath Exception |
| Android SDK command-line tools, platform and build tools | Android Software Development Kit License Agreement |
| Gradle | Apache-2.0 |
| Node.js and onnxruntime-node (the Nemotron parity test only) | MIT |
| NumPy | BSD-3-Clause |
| `diar.js` from `nealcaren/local-interview-transcriber` (the Nemotron parity test only) | No licence published. It is downloaded at a pinned commit to compare against locally, and is not copied into this repository or the app. |

## Speech used for testing

The synthetic test meetings in `test-data/synthetic/` are scripted dialogues. Their audio is generated on your Mac
with its built-in voices by `scripts/make-synthetic-set.py` and is not distributed.
