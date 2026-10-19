# Private Meeting Notes

An Android app for in-person meetings that works entirely on the phone. It records a meeting, transcribes it with
speaker labels, writes notes with decisions and action items, and answers questions across your meetings and
documents. The app has no network permission, so nothing it records or writes can leave the device.

It is a working personal project rather than a finished product: built and tested on a Pixel 11, English only, and
installed from your computer over USB rather than from an app store.

## What it does

- **Records** meetings with the screen off, with pause and resume, from the phone's microphones or a USB or
  Bluetooth one; or **imports** audio files.
- **Transcribes** speech (NVIDIA Parakeet TDT 0.6B) and finds **who spoke when** (NVIDIA Nemotron 3 Diarization).
  With the number of people given, it merges or splits voices to match, including meetings of more than eight.
- **Remembers voices**: name a speaker once, and later meetings name that voice automatically.
- **Writes notes** with Gemma 4 on the phone's GPU: an overview, key points, decisions, open questions and action
  items with owners and dates, plus tidy prose notes on request.
- **Answers questions** across meetings and documents (PDF, Word, text), with citations you can tap to open the
  passage or play the moment.
- **Names and terms**: a list of names and words the transcript keeps getting wrong, corrected automatically.
- **Exports** notes as Markdown, with or without the transcript, through the Android share sheet.

## Privacy by design

- The app requests no network permission. The build fails if any library adds one, or adds a component that would
  start code when the app launches; `scripts/check-manifest.sh` checks the installed app and its open sockets.
- The app never downloads anything. Models are fetched on your computer, checked against pinned checksums and copied
  to the phone over USB.
- App data is excluded from Android backups and device transfers. Logs hold timings and counts, never what was said.
- Sharing happens only when you ask, through Android's share sheet.

## How well it works

Measured on a Pixel 11 (Tensor G6, 12 GB) with synthetic test meetings and real ones; every build's results are in
[docs/CHANGELOG.md](docs/CHANGELOG.md).

| Test | Result |
| --- | --- |
| Synthetic meetings of 2, 4 and 6 people, simulated room | 1.5% to 5.7% word error rate; 0.0% to 9.2% of words given to the wrong person |
| 60-minute synthetic meeting, end to end | 16.4 minutes: speech, speakers and transcript 5.7, notes 10.1, search index 0.6; about 2.7% of the battery |
| Real meeting, 3 hours 9 minutes, 10 people | About 55 minutes in all: transcription 11, speakers 11, notes 30 |
| Questions over meetings and documents | First words of an answer after about 3 seconds (with Gemma 4 E2B) |

Real meetings are harder than the synthetic ones: strong accents, fast speech and technical vocabulary raise the word
error rate well above these figures. Names and terms helps with recurring words.

## Requirements

- **Phone**: Android 15 or later, 64-bit ARM. Developed on a Pixel 11; other phones are untested. Gemma 4 E4B on
  the GPU needs about 6 GB of memory, so a phone with 12 GB is recommended; with less, choose Gemma 4 E2B in
  Settings. The language model can also run on the CPU, more slowly.
- **Storage on the phone**: about 15 GB free: 4.5 GB of models (7 GB with E2B) and up to 9 GB of compiled model
  caches, which Android may clear and the app rebuilds.
- **Computer**: the scripts set up a private toolchain (JDK 21, Android SDK 37, Gradle) on an Apple Silicon Mac. On
  other systems, install JDK 21 and the Android SDK yourself and point `JAVA_HOME` and `ANDROID_HOME` at them.
  Python 3.9 or later runs the model and test scripts.
- **Downloads**: about 4.5 GB of models, plus 2.6 GB for the optional Gemma 4 E2B.

## Quick start

```bash
git clone https://github.com/matthesschramm/private-meeting-notes.git
cd private-meeting-notes
./scripts/setup-tooling.sh        # JDK, Android SDK and Gradle into .tooling/ (accepts the Android SDK licence)
source scripts/env.sh
./scripts/make-signing-key.sh     # your own signing key, into signing/ and keystore.properties (not in git)
python3 scripts/fetch-models.py   # models into models/ and the sherpa-onnx library into app/libs/, checksum-verified
```

Then connect the phone with USB debugging on (Settings → System → Developer options) and run:

```bash
./gradlew :app:installRelease     # build and install the app
./scripts/push-models.sh          # copy the models into the app's storage; skips files already there
```

Open the app and check Settings → Models: every model should show as present and verified.

Back up `signing/` and `keystore.properties` somewhere safe. Android only installs updates signed with the same key,
and reinstalling with a different one means uninstalling first, which deletes every meeting on the phone.

## Models

Model weights are not in this repository. They are several gigabytes, more than GitHub stores, and each comes under
its own licence from its publisher. `scripts/model-sources.json` pins every download (URL at a fixed commit, size,
SHA-256 and licence); `scripts/fetch-models.py` downloads and verifies them and resumes interrupted downloads, and
`python3 scripts/fetch-models.py gemma-4-e4b` fetches a single one.

| Model | Used for | Size | Licence |
| --- | --- | --- | --- |
| Silero VAD | Finding speech | 0.6 MB | MIT |
| Parakeet TDT 0.6B v2, int8 | Speech to text | 480 MB | CC-BY-4.0 |
| Nemotron 3 Diarization | Who spoke when | 105 MB | OpenMDW-1.1 |
| WeSpeaker ResNet34-LM | Voice profiles | 27 MB | see its terms |
| pyannote segmentation 3.0 | Benchmark comparison | 7 MB | MIT |
| EmbeddingGemma 2, 270M | Search | 165 MB | Apache-2.0 |
| Gemma 4 E4B | Notes and answers | 3.7 GB | Apache-2.0 |
| Gemma 4 E2B (optional) | Faster answers and notes | 2.6 GB | Apache-2.0 |

Details and the libraries the app uses are in [THIRD_PARTY_NOTICES.md](THIRD_PARTY_NOTICES.md).

## Building and testing

```bash
source scripts/env.sh
./gradlew :app:installRelease      # everyday use: optimised and not debuggable
./gradlew :app:installDebug        # development and on-device benchmarks
./gradlew :app:testDebugUnitTest   # unit tests on the computer
./scripts/check-manifest.sh        # permissions of the APK and the installed app, and the app's network sockets
```

Both builds use your signing key, so either installs over the other and keeps the app's data. When the phone is
unlocked during an install, Google Play Protect may offer to send the app to Google for a check; choose "Don't send",
or turn off Developer options → "Verify apps over USB".

The unit tests include a parity test of the Kotlin Nemotron pipeline against a reference implementation. It runs when
`models/` is present and the reference outputs exist
(`node .tooling/parity/reference.mjs test-data/synthetic/<set>/<clean|room>.wav`), and is skipped otherwise.

### Benchmarks on the phone

Debug-only test code runs pipeline stages inside the app and pulls JSON results into `test-data/bench-results/`:

```bash
./scripts/bench.sh pipeline test-data/synthetic/four-speakers/room.wav heads=4    # whole pipeline with timings
./scripts/bench.sh pipeline test-data/synthetic/ten-speakers/room.wav heads=10 summary=false   # more than 8 people
./scripts/bench.sh notes test-data/synthetic/four-speakers/truth.json             # Gemma's notes on a transcript
.tooling/venv/bin/python3 scripts/score.py words test-data/synthetic/four-speakers/truth.json \
    test-data/bench-results/four-speakers-room.pipeline-heads4.json
```

`fallback=true` runs the earlier clustering pipeline instead of Nemotron for comparison, and `chunkMinutes=<n>` sets
the longest stretch it clusters at once.

The retrieval test needs the meetings' ground truth and the documents pushed alongside its question file:

```bash
S=test-data/synthetic; R=test-data/retrieval
EXTRA="$S/two-speakers/truth.json:two-speakers-truth.json $S/four-speakers/truth.json:four-speakers-truth.json \
$S/six-speakers/truth.json:six-speakers-truth.json $R/travel-policy.md:travel-policy.md \
$R/office-move-budget-memo.docx:office-move-budget-memo.docx $R/launch-plan.pdf:launch-plan.pdf \
$R/annual-report.pdf:annual-report.pdf" ./scripts/bench.sh retrieval $R/questions.json
```

Benchmarks interrupted half-way can leave "Benchmark: …" meetings behind; remove them with
`adb shell am instrument -w -e class dev.matthes.privatemeetingnotes.bench.BenchTest#cleanup
dev.matthes.privatemeetingnotes.test/androidx.test.runner.AndroidJUnitRunner`.

The synthetic meetings are generated with macOS's built-in voices (`.tooling/venv/bin/python3
scripts/make-synthetic-set.py`, plus `--long-minutes 60` for a long version; `ten-speakers --long-minutes 125
--absent 3` lays each pass out afresh with three people away), and the retrieval PDFs with
`python3 scripts/make-retrieval-pdf.py`.

## Project layout

```
app/          the Android app (Kotlin, Jetpack Compose)
scripts/      toolchain, model, device, benchmark and scoring scripts
docs/         development plan, changelog with each build's measurements, and a template for real-meeting feedback
test-data/    synthetic meeting scripts and ground truth, retrieval test documents and questions
.tooling/     private toolchain (created by setup-tooling.sh, not committed)
models/       model files fetched on the computer (not committed)
signing/      your signing key (not committed; back it up)
```

[docs/DEVELOPMENT_PLAN.md](docs/DEVELOPMENT_PLAN.md) explains the design and the choice of every model, and
[docs/CHANGELOG.md](docs/CHANGELOG.md) what each build changed and measured, including what was tried and left out.

## A note on recording

Recording a conversation may require the consent of everyone in it, depending on where you are. Ask first.

## Licence

The code is licensed under the [Apache License 2.0](LICENSE); see [NOTICE](NOTICE). Models, libraries and tools come
under their own licences, listed in [THIRD_PARTY_NOTICES.md](THIRD_PARTY_NOTICES.md).

## Acknowledgements

Built on the work of NVIDIA (Parakeet, Nemotron 3 Diarization), Google (Gemma, EmbeddingGemma, LiteRT-LM), the
sherpa-onnx project, Silero, pyannote, WeSpeaker, ONNX Runtime and Hugging Face Transformers, and Neal Caren's ONNX
conversion of Nemotron 3 Diarization and reference pipeline. This project is not affiliated with any of them.
