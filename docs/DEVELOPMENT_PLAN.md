# Private Meeting Notes (Android): Development Plan

Version 1.1, 14 October 2026. Owner: Matthes Schramm. Builder: AI agent (Cursor).

The plan the app was built from, for the author's own use, kept as written apart from the app's name. What changed since is in [CHANGELOG.md](CHANGELOG.md).

App name: Private Meeting Notes. Package ID: `dev.matthes.privatemeetingnotes`.

This plan covers a working prototype delivered in three installable builds. It records the decisions made so far,
the architecture, every component with its model and library, the privacy controls, how each build is verified, and
the risks. Numbers marked "target" are goals to measure on the phone, not results.

---

## 1. Purpose

A personal Android app for in-person meetings with several attendees:

1. Record a meeting on the phone (screen off is fine), or import an existing recording.
2. Transcribe it on the phone with speaker labels, and name speakers once so the app recognises them next time.
3. Write a summary with decisions, open questions and action items, and tidy up the notes.
4. Answer questions about a meeting, across all past meetings, and across documents stored on the phone.

Nothing leaves the phone. The app is for Matthes's own use and is installed over USB, not through the Play Store.

## 2. Scope

In scope for the prototype:

- English speech only.
- In-room meetings captured by the phone microphone, or an external USB-C or Bluetooth microphone.
- Imported audio files (for example Plaud exports or voice memos).
- Up to eight speakers with the primary speaker model. Larger meetings use a fallback model.
- Documents: PDF, Word (.docx), plain text and Markdown.

Out of scope:

- Other languages.
- Any cloud processing, accounts or sync.
- Calls made on the phone itself. Android does not let apps capture another app's call audio.
- Live translation, iOS, tablets, Wear OS.
- Play Store distribution and multi-user features.

## 3. Principles

1. Nothing leaves the phone. The shipping app has no `INTERNET` permission, and every build fails if one appears in
   the merged manifest.
2. No Google Play services, Firebase, analytics or crash-reporting SDKs. The phone's Gemini Nano (AICore) is not used.
3. Models are fetched on the Mac, checksum-verified, and copied to the phone over USB. The app never downloads.
4. Logs record timings and counts, never transcript text or audio.
5. Every build is installable and usable on its own, and is verified on the phone before handover.
6. Use proven libraries. Write custom code only where no library exists (the Nemotron speaker cache, alignment and
   smoothing, retrieval glue).

## 4. Decisions so far

| Decision | Choice | Why |
| --- | --- | --- |
| Device | Pixel 11 (Tensor G6, 12 GB+ RAM, Android 17) | Matthes's phone |
| Language | English only | Simplifies the speech model choice |
| Delivery | Working prototype, no throwaway test steps | Real feedback comes from real use |
| Speech to text | NVIDIA Parakeet TDT 0.6B v2, int8, via sherpa-onnx | Most accurate Parakeet for English; same model FluidVoice ran on the Mac |
| Who spoke when | NVIDIA Nemotron 3 Diarization, int8 ONNX | Ranked first on Voice Arena Diarization-Bench (2026): 14.7% DER, 2.2% wrong-speaker time |
| Large meetings (9+) | pyannote segmentation 3.0 + WeSpeaker + clustering, via sherpa-onnx (Build 3.1: Nemotron, then splitting speakers that hold several voices) | No speaker cap |
| Speaker names | WeSpeaker ResNet34 voiceprints | Small, works with the fallback pipeline too |
| Writing and Q&A | Gemma 4 E4B via LiteRT-LM 0.18, on the CPU | 32K context fits a one-hour transcript in one pass |
| Speed fallback | Gemma 4 E2B build compiled for the Tensor G6 NPU | Faster, 8K context, summaries in chunks |
| Search | EmbeddingGemma 2 text 270M via LiteRT-LM `EmbeddingEngine`, plus SQLite keyword search | One runtime with Gemma; personal scale needs no vector database |
| Speaker-label clean-up | Sentence-level smoothing; no LLM relabelling | Google's DiarizationLM study: an LLM without task-specific training made speaker errors worse |
| Toolchain | Project-local in `.tooling/`; only adb's key in `~/.android` is shared with other projects | Requested by Matthes |
| Install | Sideload over USB | Personal use |
| Name | Private Meeting Notes, package `dev.matthes.privatemeetingnotes` | Chosen for the open-source release, 19 October 2026 |

## 5. Target environment

### 5.1 Phone

| Item | Value |
| --- | --- |
| Device | Pixel 11 |
| SoC | Google Tensor G6 (2 nm), TPU/NPU accessible through LiteRT |
| RAM | 12 GB (base model) or 16 GB (Pro 512 GB+) |
| OS | Android 17 (API 37) |
| App SDK levels | `minSdk` 35, `compileSdk` 37, `targetSdk` 37 |

`minSdk` 35 gives the built-in PDF text API (`PdfRenderer.Page.getTextContents`) and the `mediaProcessing`
foreground service type. Both arrived in Android 15.

### 5.2 Mac toolchain (installed 14 October 2026 in `.tooling/`)

| Tool | Version | Source |
| --- | --- | --- |
| JDK | Temurin 21.0.12.1+1, macOS aarch64 | Adoptium, SHA-256 verified |
| Android command-line tools | 23.0 (build 16111833) | dl.google.com, SHA-1 verified |
| Android platform-tools | 37.0.1 | Android CLI |
| Android SDK platform | API 37.0 | Android CLI |
| Android build-tools | 36.0.0 (AGP 9.4 default) | Android CLI |
| Gradle | 9.8.1 (AGP 9.4 needs 9.6.0 or newer) | services.gradle.org, SHA-256 verified |

The Android CLI that now sits behind `sdkmanager` collects usage metrics unless `--no-metrics` is passed. It also
unpacks itself into `ANDROID_USER_HOME`. The scripts set both, and `env.sh` sends `android` and `sdkmanager` through
`scripts/bin/android`, which adds the flag. adb is the one exception to the project-local setup: it ignores
`ANDROID_USER_HOME` and uses this Mac's shared key in `~/.android/adbkey`, which is the key the phone authorises.

### 5.3 Build stack (to pin in Build 1)

| Library | Version | Use |
| --- | --- | --- |
| Android Gradle Plugin | 9.4.1 | Build (built-in Kotlin support) |
| Kotlin | 2.4.21 | Language; Compose compiler plugin at the same version |
| Compose BOM | 2026.09.00 | UI (Material 3) |
| Room | 2.8.5 (with KSP 2.3.12) | Local database, FTS4 keyword index |
| kotlinx-coroutines | latest stable at build time | Concurrency |
| kotlinx-serialization | latest stable at build time | JSON (model registry, action items) |
| sherpa-onnx | 1.13.8, static-ONNX-Runtime AAR | VAD, Parakeet ASR, WeSpeaker embeddings, fallback diarization |
| ONNX Runtime Android | 1.31.0 | Nemotron 3 Diarization |
| LiteRT-LM Android | 0.18.0 | Gemma 4 and EmbeddingGemma 2 |

The static-ONNX-Runtime build of sherpa-onnx avoids two copies of `libonnxruntime.so` when ONNX Runtime Android is
also in the app.

## 6. Architecture

### 6.1 Data flow

```
Record (foreground service: microphone)      Import file (Storage Access Framework)
                 \                                  /
                  ->  recordings/<meetingId>.wav  <-     16 kHz mono PCM16
                                   |
        Processing service (foreground service: mediaProcessing), one meeting at a time
                                   |
  1 Decode and resample to 16 kHz mono
  2 Voice activity detection              Silero VAD
  3 Speech to text with word timings      Parakeet TDT 0.6B v2
  4 Who spoke when                        Nemotron 3 Diarization (or fallback above 8 speakers)
  5 Merge words with speaker turns, smooth per sentence
  6 Name speakers                         WeSpeaker voiceprints
  7 Save transcript                       Room database
  8 Summary, decisions, action items      Gemma 4 E4B
  9 Index for search                      EmbeddingGemma 2 + FTS4
                                   |
  UI: Meetings, Recording, Processing, Meeting (Summary, Transcript, Actions, Ask), Ask, Library, Settings
```

### 6.2 Code layout

One Gradle app module to start, organised by package:

| Package | Responsibility |
| --- | --- |
| `ui` | Compose screens, navigation, view models |
| `recording` | Recording foreground service, WAV writer, input-device selection, level meter |
| `importing` | File picker, decode with MediaExtractor/MediaCodec, downmix, resample |
| `processing` | Job queue, stage checkpoints, `mediaProcessing` service, progress |
| `asr` | sherpa-onnx VAD and Parakeet wrapper |
| `diarization` | Nemotron front end, ONNX steps, speaker cache, post-processing; fallback pipeline |
| `alignment` | Word-to-speaker assignment, sentence smoothing, utterance merging |
| `speakers` | Voiceprint extraction, matching, enrolment |
| `llm` | LiteRT-LM engine host, prompts, streaming, structured output |
| `rag` | Document extraction, chunking, embeddings, hybrid retrieval, citations |
| `data` | Room entities, DAOs, migrations, file storage |
| `models` | Model registry, presence and checksum checks |
| `diagnostics` | Timing and memory metrics, debug benchmark entry point |

### 6.3 Runtime model

- Coroutines throughout; heavy work on background dispatchers.
- One heavy model in memory at a time. A model host loads and unloads speech, speaker and language models in
  sequence and responds to memory-pressure callbacks.
- One processing job at a time. Each stage writes a checkpoint, so a killed or interrupted job resumes from the last
  completed stage instead of starting again.
- Processing starts from a user action, which matters for foreground-service time limits. A setting can delay
  processing until the phone is charging.

### 6.4 On-device storage

| Data | Location | Notes |
| --- | --- | --- |
| Database | App internal storage | Encrypted at rest by Android file-based encryption |
| Recordings | App internal storage, `recordings/` | 16 kHz mono PCM16, about 115 MB per hour |
| Models | App-specific external storage, `Android/data/<package>/files/models/` | Pushed over USB; about 4.5 GB |
| Exports | Only through the Android share sheet, on request | User-initiated |

If `adb push` into `Android/data` is refused on Android 17, debug builds fall back to `adb push` into
`/data/local/tmp` followed by `run-as <package> cp` into internal storage.

## 7. Components

### 7.1 Recording

- `AudioRecord` at 16 kHz, mono, PCM16. Default source `VOICE_RECOGNITION` (minimal processing, tuned for speech
  recognition), with `MIC` and `UNPROCESSED` selectable in settings for comparison.
- Input-device picker for USB-C and Bluetooth microphones.
- Foreground service of type `microphone`, started from the visible app (required for while-in-use access on
  Android 17). Persistent notification with pause, resume and stop.
- Crash-safe WAV writing: data is flushed regularly and the header is fixed up, so a crash loses seconds, not the
  meeting.
- Partial wake lock while recording. Auto-pause on incoming calls. Free-space check before and during recording.
- Optional fields on the record screen: meeting title and expected head count (improves speaker separation and selects
  the fallback model above eight).
- Consent reminder on the record screen (Victoria: sharing a record or report of a private conversation needs every
  party's consent).

### 7.2 Import

- Storage Access Framework picker for audio files (M4A, MP3, WAV, AAC, OGG/Opus where the phone can decode).
- Decode with `MediaExtractor` and `MediaCodec`, downmix to mono, resample to 16 kHz, write the same WAV format as
  recordings, then process as normal.

### 7.3 Voice activity detection

- Silero VAD through sherpa-onnx.
- Starting parameters: threshold 0.5, minimum speech 0.25 s, minimum silence 0.5 s, maximum segment 25 s, so
  segments fit the speech model's comfortable window. Tuned on the synthetic and real test sets.

### 7.4 Speech to text

- Parakeet TDT 0.6B v2, int8 (`sherpa-onnx-nemo-parakeet-tdt-0.6b-v2-int8`, about 480 MB compressed), through the
  sherpa-onnx offline recogniser (NeMo transducer).
- Runs per VAD segment, 4 to 6 CPU threads, greedy decoding. Output includes punctuation, capitalisation and token
  timestamps, converted to word timings.
- Later option (Build 3): a custom vocabulary of names and company terms, if sherpa-onnx hotword biasing supports this
  model; otherwise a post-correction pass that only touches listed terms.

### 7.5 Who spoke when

Primary: NVIDIA Nemotron 3 Diarization (100M parameters, OpenMDW-1.1 licence, up to eight speakers).

- ONNX files from the community conversion (`NealCaren/Nemotron-3-Diarization-ONNX`): `embed.onnx`,
  `step_int8.onnx` (about 103 MB), `mel_filters.bin`, `silence_embeds.bin`. Run on ONNX Runtime Android.
- Kotlin port of the reference pipeline:
  1. Front end: pre-emphasis 0.97, STFT (n_fft 512, Hann window 400, hop 160), 128-bin mel filterbank, log.
  2. `embed.onnx` turns features into 80 ms embedding frames.
  3. Offline-style chunked encoding: chunks of 340 frames with 40 frames of right context.
  4. Arrival-order speaker cache of 264 frames, FIFO of 40, update period 300, score-based compression.
  5. Sigmoid gives per-speaker activity every 10 ms, speakers numbered by first appearance.
- Post-processing: threshold, median filter, minimum segment and gap durations, then speaker turns.
- Parity test against the reference implementation on the Mac (see 9.2) before it ships. As built: identical in
  32-bit on the Mac and the phone. The phone keeps the 8-bit model, which agrees on 99.1% to 99.997% of frames
  because the phone's 8-bit arithmetic differs slightly; speaker error stays in line with the reference:

  | Meeting | Phone, 8-bit | Phone, 32-bit | Mac reference, 8-bit |
  | --- | --- | --- | --- |
  | 2 people, room and clean | 0.0% | 0.0% | 0.0% |
  | 4 people, room | 0.0% | 0.0% | 0.0% |
  | 4 people, clean | 0.0% | 19.7% | 0.1% |
  | 6 people, room | 10.1% | 8.4% | 9.8% |
  | 6 people, clean | 25.4% | 23.7% | 22.6% |

  (Diarization error rate with a 0.25 s collar.)
- If sherpa-onnx merges PR #4032 (native Nemotron support), switch to it behind the same interface.

Fallback, chosen when the head count is above eight: sherpa-onnx offline speaker diarization with pyannote
segmentation 3.0 (about 7 MB), WeSpeaker ResNet34 embeddings (about 27 MB) and clustering, with the number of clusters
set from the head count.

Changed in Build 3.1: the fallback gave far more words to the wrong person than Nemotron on every synthetic meeting,
and it held the whole recording in memory, so it refused recordings over 90 minutes. Meetings of more than eight now
go through Nemotron too, which follows up to eight voices; each of its speakers' speech is then diarised again on its
own, and speakers found to hold more than one voice are split, up to the head count. The fallback stays in the
benchmarks for comparison, clustering long recordings in chunks of up to an hour. sherpa-onnx needs the audio it clusters in memory (230 MB per hour), so recordings over an
hour are clustered in chunks of up to an hour, cut in pauses, and the chunks' speakers are matched up by voice the
same way extra speakers are merged after Nemotron (section 7.6 and Build 3.1 in the changelog).

### 7.6 Alignment and smoothing

1. Assign each word to the speaker with the greatest time overlap; words outside any turn go to the nearest turn.
2. Split into sentences on punctuation and give each sentence to its majority speaker, so labels don't flip
   mid-sentence.
3. Merge consecutive sentences from the same speaker into utterances.
4. Keep raw word timings so the transcript can jump to the audio and so re-splitting does not need re-transcription.

### 7.7 Speaker names (voiceprints)

- WeSpeaker ResNet34 embeddings via the sherpa-onnx speaker embedding extractor.
- Per speaker in a meeting: average the embeddings of up to eight of their longest segments (at least 1.5 s each),
  then normalise.
- Match against saved voiceprints by cosine similarity with a threshold and a margin over the second-best match;
  thresholds tuned on test data.
- Renaming a speaker creates or updates a voiceprint (running mean, capped sample count). Voiceprints can be viewed,
  renamed and deleted in settings.
- Optional "This is me" enrolment: about 30 seconds of Matthes's own voice.

### 7.8 Language model tasks

- Gemma 4 E4B (`gemma-4-E4B-it.litertlm`, 3.66 GB, Apache-2.0) through LiteRT-LM 0.18 (`Engine`,
  `Conversation`, streaming callbacks, cancellation). As built: on the GPU by default, with 32-bit intermediate
  values, since the GPU's default 16-bit format broke Gemma's output on prompts of about 6,700 tokens and more.
- As built, the context is 10,240 tokens: a 32K context in 16-bit hung the phone until its watchdog restarted it,
  32-bit values need more GPU memory, and on the CPU a longer prompt could outlast LiteRT-LM's 10-minute limit for one
  reply. Longer transcripts are summarised in parts of about 23 minutes and then combined.
- Low temperature (about 0.3) for summaries and extraction; greedy decoding for answers.
- Tasks and prompts (stored as versioned templates in the app):
  1. Summary: title, short overview, key points, decisions, open questions.
  2. Action items: JSON array of owner, task, due (as said) and evidence timestamp. As built, the notes are first
     generated freely and read leniently, and only a reply that can't be read is generated again under LiteRT-LM's
     response format (constrained decoding, about a quarter slower), so the output always parses.
  3. Tidy-up: cleaned, readable notes from the transcript, keeping speaker names and meaning.
  4. Questions: answers grounded only in retrieved passages, with citations.
- Speaker names come from voiceprints and the user's renames, never invented by the model.
- Speed fallback: Gemma 4 E2B Tensor G6 NPU build (`Backend.NPU`, 8K context, summaries in chunks).

### 7.9 Knowledge base and questions

- Sources: meeting transcripts and summaries, plus documents picked from the phone (PDF, DOCX, TXT, MD), copied into
  app storage.
- Extraction: PDF through `PdfRenderer.Page.getTextContents` per page; DOCX by reading `word/document.xml`; text and
  Markdown as is.
- Chunking: about 350 words with 50 words of overlap for documents; transcript chunks follow speaker turns up to the
  same size, with time ranges and speaker names. As built: about 200 words with 40 of overlap, and document chunks
  stay within one page (opening with the previous page's last 40 words), so citations open the right page.
- Embeddings: EmbeddingGemma 2 text 270M through LiteRT-LM `EmbeddingEngine`, query and document prompts as per the
  model card, truncated to 256 dimensions and normalised, stored as BLOBs.
- Retrieval: top 20 by vector similarity plus top 20 by FTS4 keyword search, merged with reciprocal rank fusion, top 6
  to 8 passages into the prompt. Filters by meeting, date range and speaker. As built: 5 passages, which keeps the
  first words of an answer within the 8-second target.
- Answers show citation chips that open the transcript at the right moment or the document at the right page.
- Long chats are compressed into a short context memo so the conversation fits the context window.

### 7.10 User interface

Jetpack Compose, Material 3, bottom navigation: Meetings, Ask, Library, Settings.

| Screen | Contents |
| --- | --- |
| Meetings | List with search, record button, import button, processing status badges |
| Recording | Timer, level meter, pause and stop, title and head count, input device, consent reminder |
| Processing | Stage list with progress and estimated time; continues in the background |
| Meeting: Summary | Summary sections, regenerate, copy and share as Markdown |
| Meeting: Transcript | Coloured speakers, timestamps, tap to play, rename speaker, move an utterance to another speaker, re-split with a new head count, search |
| Meeting: Actions | Action items with owner and due date, mark done, copy |
| Meeting: Ask | Questions about this meeting only |
| Ask | Questions across all meetings and documents |
| Library | Documents list, add, remove, re-index |
| Settings | Model status and setup instructions, audio retention, voiceprints, recording source, diagnostics (timings only), delete all data |

### 7.11 Data model (Room)

| Entity | Key fields |
| --- | --- |
| Meeting | id, title, startedAt, duration, source (recorded or imported), audioPath, headCount, status |
| ProcessingJob | meetingId, stage, progress, startedAt, error, checkpoint |
| Utterance | meetingId, speakerId, start, end, text, wordsJson |
| Speaker | meetingId, label, displayName, voiceprintId, embedding |
| Voiceprint | id, name, embedding, sampleCount, updatedAt |
| Summary | meetingId, overview, keyPoints, decisions, openQuestions, promptVersion, model |
| ActionItem | meetingId, owner, task, due, evidenceStart, done |
| Document | id, title, mimeType, path, pageCount, addedAt |
| Chunk | sourceType, sourceId, text, start, end or page, speaker, vector; FTS4 shadow table |
| ChatThread / ChatMessage | scope (meeting or global), role, text, citationsJson |

### 7.12 Model management

| Model | File(s) | Size | Licence |
| --- | --- | --- | --- |
| Silero VAD | `silero_vad.onnx` | under 3 MB | MIT |
| Parakeet TDT 0.6B v2 int8 | sherpa-onnx archive (encoder, decoder, joiner, tokens) | about 0.6 GB | CC-BY-4.0 |
| Nemotron 3 Diarization | `embed.onnx`, `step_int8.onnx`, `mel_filters.bin`, `silence_embeds.bin` | about 105 MB | OpenMDW-1.1 |
| pyannote segmentation 3.0 | sherpa-onnx archive | about 7 MB | MIT |
| WeSpeaker ResNet34 (VoxCeleb) | `wespeaker_en_voxceleb_resnet34_LM.onnx` | about 27 MB | Check model terms at download; fine for personal use |
| Gemma 4 E4B | `gemma-4-E4B-it.litertlm` | 3.66 GB | Apache-2.0 |
| EmbeddingGemma 2 text 270M | `embeddinggemma-2-text-270m.litertlm` | about 165 MB | Apache-2.0 |
| Gemma 4 E2B, Tensor G6 NPU (optional) | `gemma-4-E2B-it_Google_Tensor_G6.litertlm` | 3.3 GB | Apache-2.0 |
| As built instead: Gemma 4 E2B on the GPU (optional) | `gemma-4-E2B-it.litertlm` | 2.6 GB | Apache-2.0 |

- `scripts/model-sources.json` lists every download with a pinned source URL, size, SHA-256 and licence (written).
- `scripts/fetch-models.py` downloads on the Mac into `models/`, resumes interrupted downloads, checks sizes and
  checksums, unpacks archives and writes `models/manifest.json` with every file's size and SHA-256 (written).
- `scripts/push-models.sh` copies them to the phone over USB.
- The app checks presence and size at start-up (full checksum in the background) and shows setup instructions for
  anything missing.

## 8. Privacy and security checklist

- No `INTERNET` or `ACCESS_NETWORK_STATE` permission. Library-declared network permissions are stripped with
  `tools:node="remove"`, and a build step fails if the merged manifest contains them.
- `android:allowBackup="false"` and data-extraction rules that exclude everything from cloud backup and device
  transfer.
- Permissions requested: `RECORD_AUDIO`, `POST_NOTIFICATIONS`, `FOREGROUND_SERVICE`,
  `FOREGROUND_SERVICE_MICROPHONE`, `FOREGROUND_SERVICE_MEDIA_PROCESSING`, `WAKE_LOCK`.
- No analytics, crash reporting, Play services or AICore.
- Logs use one tag, carry timings and counts only, and never include transcript text, names or audio.
- Audio retention setting: keep, delete after processing, or delete after a set number of days.
- Sharing happens only through the share sheet on request.
- Release builds are signed with a personal keystore kept outside the repository.
- Mac side: Android CLI always run with `--no-metrics`; Gradle build scans off.

## 9. Testing and verification

### 9.1 Unit tests (JVM)

Alignment and sentence smoothing, chunking, reciprocal rank fusion, speaker-cache logic against reference vectors,
WAV writer and header recovery, prompt builders, action-item JSON parsing, voiceprint matching thresholds.

### 9.2 Nemotron parity test

Run the reference JavaScript implementation with ONNX Runtime for Node on the Mac (installed under `.tooling/`) on
three test clips, export per-frame speaker activity, and compare with the Kotlin port on the phone. Pass mark: at least
99.9% frame agreement after thresholding, and segment boundaries within 20 ms.

### 9.3 Synthetic meeting test set

Generated on the Mac from scripted dialogues read by several built-in macOS voices: 2, 4 and 6 speakers, with
interruptions and overlaps created by mixing, plus versions with room noise and reverberation to approximate a phone
on a table. Each meeting has a ground-truth transcript and speaker timeline (RTTM). Used to measure word error rate and
diarization error rate on every build. Synthetic voices are cleaner than real rooms, so real recordings remain the
final test.

### 9.4 On-device benchmark

A debug-only entry point processes a given file and logs per-stage time, real-time factor and peak memory. Results
are pulled over USB and contain timings only.

### 9.5 Instrumentation tests

Recording service start, pause and stop; processing resume after kill; database migrations; model loading and
unloading.

### 9.6 Privacy checks on every build

Merged-manifest check for network permissions; installed permission list from `adb shell dumpsys package`; no
network sockets opened during a full record-to-answer run.

### 9.7 Acceptance and real-meeting feedback

Each build ends with a manual acceptance checklist on the phone. Matthes then uses it in real meetings and notes
issues in `docs/FEEDBACK.md`. Plaud comparison: import an existing Plaud recording and compare transcripts and speaker
labels side by side; optionally record one meeting on both devices at once to compare capture quality.

### 9.8 Targets (to measure on the Pixel 11)

| Metric | Target |
| --- | --- |
| Speech to text plus speakers for a 60-minute meeting | 10 minutes or less on the CPU |
| Summary and action items for a 60-minute meeting | 3 minutes or less |
| Question answering | First words within 8 s, full answer within 30 s |
| Peak memory | 4.5 GB or less (one model at a time) |
| Battery | 10% or less to process a 60-minute meeting; 5% or less per hour of recording |
| Synthetic set, clean | Word error rate 8% or less; wrong-speaker time 5% or less |

## 10. Build and release workflow

- One-time: `./scripts/setup-tooling.sh` (done), then `python3 scripts/fetch-models.py` and
  `./scripts/push-models.sh` (Build 1).
- Every session: `source scripts/env.sh`.
- Build and install: `./gradlew assembleDebug` then `adb install -r app/build/outputs/apk/debug/app-debug.apk`.
- Logs: `adb logcat -s PrivateMeetingNotes`.
- Versions: 0.1.x for Build 1, 0.2.x for Build 2, 0.3.x for Build 3, with a short changelog in `docs/CHANGELOG.md`.
- Git: local repository; commits at milestones; no remote unless requested.
- Release builds: R8 shrinking and the personal keystore, installed over USB.

## 11. Milestones

Estimates are agent working time. Elapsed time depends mostly on on-phone testing and feedback from real meetings.

### M0: Foundation (done, 14 October 2026)

- Project folder `~/Projects/meeting-notes-android`.
- Project-local toolchain with checksum-verified downloads (`scripts/setup-tooling.sh`, `scripts/env.sh`).
- This plan.

### M1: Build 1, the core loop (built 14 October 2026)

Status: built and measured on the Pixel 11; results and changes from this plan are in `docs/CHANGELOG.md`. Still to
check: a 30-minute recording with the screen off, and an import of a real Plaud recording.

Tasks:

1. Scaffold the Gradle project (AGP 9.4.1, Kotlin 2.4.21, Compose, Room, KSP) with the network-permission check.
2. Model registry, `fetch-models.py` (written), `push-models.sh`, and the in-app model check screen.
3. Recording service, WAV writer, input-device picker, notification controls, record screen.
4. Import from file with decode and resample.
5. Processing service with job queue, stage checkpoints and progress UI.
6. VAD and Parakeet speech to text with word timings.
7. Nemotron 3 Diarization port (front end, chunked steps, speaker cache, post-processing) and the parity test.
8. Fallback diarization pipeline for more than eight speakers.
9. Alignment, sentence smoothing and utterance merging.
10. Transcript screen with speaker colours, tap-to-play, rename, move utterance and re-split.
11. Gemma 4 E4B host, summary prompt and action items with constrained JSON output.
12. Summary and Actions screens; share as Markdown.
13. Synthetic test set generator and on-device benchmark.
14. Measure, tune and fix, then hand over.

Done when:

- A 30-minute or longer recording made with the screen off produces a transcript with speakers, a summary and action
  items without crashes.
- An imported Plaud recording processes the same way.
- Renaming a speaker persists; re-splitting with a new head count works without re-transcribing.
- Nemotron parity test passes; synthetic-set word error rate and speaker metrics are recorded.
- The merged manifest has no network permission; timings for a 60-minute meeting are recorded.

### M2: Build 2, questions and documents (built 14 October 2026)

Status: built and measured; all three conditions below are met (see `docs/CHANGELOG.md`).

Tasks:

1. EmbeddingGemma 2 engine and indexing of transcripts and summaries.
2. Library screen; PDF, DOCX, TXT and Markdown extraction and indexing.
3. FTS4 keyword index and hybrid retrieval with reciprocal rank fusion.
4. Ask screens (per meeting and global) with streaming answers, citations and filters.
5. Context memo for long chats.
6. Retrieval tests on a small set of questions with known answers.

Done when:

- Questions about a meeting return grounded answers with working citations.
- Questions that need a document plus a past meeting cite both.
- A 100-page PDF indexes without errors; answers start within the target time.

### M3: Build 3, memory and speed (built 15 October 2026)

Status: voiceprints, names and terms, speculative decoding and an optional Gemma 4 E2B are built and measured. The
NPU route was tried and left out (the phone's EdgeTPU runtime refused the model); live captions and FLAC were not
built. Details, and why, in `docs/CHANGELOG.md`. Real-meeting feedback has yet to come.

Tasks:

1. Voiceprints: automatic naming in new meetings, "This is me" enrolment, voiceprint management.
2. Speed: Tensor G6 NPU options (Gemma 4 E2B NPU build; Parakeet NPU build if worthwhile), selectable in settings.
3. Optional live captions while recording (streaming model, no speaker labels).
4. Custom vocabulary for names and company terms, if the speech model supports it.
5. Audio retention and compression (FLAC) settings.
6. Fixes from real-meeting feedback.

Done when:

- A known colleague is named automatically in a new meeting after one rename.
- Processing time and battery meet the targets, or the gap is understood and documented.
- Feedback items from Builds 1 and 2 are closed or explicitly deferred.

### Later options (not scheduled)

Encrypted database (SQLCipher), export and backup to the Mac over USB, Quick Settings tile to start recording,
home-screen widget, sherpa-onnx native Nemotron support once released.

## 12. Risks and mitigations

| Risk | Mitigation |
| --- | --- |
| Bugs in the Nemotron Kotlin port | Parity test against the reference; fallback pipeline; switch to sherpa-onnx native support when available |
| Two copies of ONNX Runtime native libraries | Static-ONNX-Runtime sherpa-onnx AAR; check APK contents |
| Native libraries not 16 KB page-aligned (Android 15+ devices) | Check alignment of every `.so` in the APK; use library builds with 16 KB support |
| Gemma 4 E4B too slow on the Tensor G6 CPU | Streaming output; chunked summaries; E2B NPU build. As measured: the GPU reads about 7 times faster than the CPU, but writing (6 to 7 tokens a second) still puts a 60-minute meeting's notes at about 10 minutes |
| Running out of memory with several models | Load one model at a time; unload on memory pressure; memory-mapped weights. As measured: Gemma on the GPU in 32-bit leaves about 0.8 GB free |
| LiteRT-LM 0.x API changes | Pin 0.18.0 and wrap behind interfaces |
| Thermal throttling during long processing | Resumable stages; "process while charging" option |
| Android 17 foreground-service rules | Start recording from the visible app; handle the `mediaProcessing` 6-hour daily limit and `onTimeout` |
| Phone audio in large or noisy rooms | Placement guidance; external microphone support; recording-source setting |
| More than eight speakers | Head-count field selects the fallback pipeline |
| Real-room accuracy below benchmark | Plaud side-by-side; tune VAD and thresholds; DiariZen as an alternative (non-commercial licence is fine for personal use) |
| Model download or licence changes upstream | Registry with checksums and sources; models kept on the Mac |
| Storage growth | Audio retention setting; FLAC compression |

## 13. Open questions for Matthes

1. Icon (name decided: Private Meeting Notes, package `dev.matthes.privatemeetingnotes`).
2. Keep audio after processing? Proposed default: keep, with deletion available per meeting and in bulk.
3. Export: Markdown through the share sheet is planned. Is a direct save into an Obsidian vault folder on the phone
   wanted as well?
4. Voiceprints for colleagues: enrol only after they agree, or name them from meetings as they come?
5. Summary format: a general template, or a specific format (for example a team's usual meeting format)?
6. Consent reminder wording on the record screen.

## 14. Licences and attributions

| Component | Licence |
| --- | --- |
| sherpa-onnx | Apache-2.0 |
| ONNX Runtime | MIT |
| LiteRT-LM | Apache-2.0 |
| Parakeet TDT 0.6B v2 | CC-BY-4.0 |
| Nemotron 3 Diarization (and ONNX conversion) | OpenMDW-1.1 |
| Silero VAD | MIT |
| pyannote segmentation 3.0 | MIT |
| WeSpeaker ResNet34 | Code Apache-2.0; check model terms at download |
| Gemma 4 E4B / E2B | Apache-2.0 |
| EmbeddingGemma 2 | Apache-2.0 |

Attributions will be listed on an About screen.

## 15. Repository layout

```
meeting-notes-android/
  README.md
  docs/
    DEVELOPMENT_PLAN.md
    CHANGELOG.md            (from Build 1)
    FEEDBACK.md             (from Build 1)
  scripts/
    setup-tooling.sh        toolchain installer (done)
    env.sh                  shell environment (done)
    bin/android             Android CLI with metrics off (done)
    model-sources.json      pinned model sources and checksums (written)
    fetch-models.py         model and library downloader (written)
    push-models.sh          (Build 1)
    make-synthetic-set.sh   (Build 1)
    check-manifest.sh       (Build 1)
  app/                      Android app module (Build 1)
  models/                   model files on the Mac (not committed)
  test-data/synthetic/      generated meetings with ground truth
  .tooling/                 private toolchain (not committed)
```

## 16. References

- Speaker accuracy: Voice Arena Diarization-Bench v1, https://voicearena.com/diarization-bench-v1-launch
- Nemotron 3 Diarization: https://huggingface.co/nvidia/Nemotron-3-Diarization
- ONNX conversion and reference implementation: https://huggingface.co/NealCaren/Nemotron-3-Diarization-ONNX
- sherpa-onnx: https://github.com/k2-fsa/sherpa-onnx (PR #4032 adds Nemotron)
- LiteRT-LM: https://github.com/google-ai-edge/LiteRT-LM
- Gemma 4 E4B for LiteRT-LM: https://huggingface.co/litert-community/gemma-4-E4B-it-litert-lm
- EmbeddingGemma 2 text 270M: https://huggingface.co/litert-community/embeddinggemma-2-text-270m-litert-lm
- DiarizationLM: https://arxiv.org/abs/2401.03506
- AGP 9.4 compatibility: https://developer.android.com/build/releases/agp-9-4-0-release-notes
- Foreground service types: https://developer.android.com/develop/background-work/services/fgs/service-types
- Android CLI and metrics: https://developer.android.com/tools/agents/android-cli
