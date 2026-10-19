# Changelog

All builds are measured on the Pixel 11 (Tensor G6, Android 17) unless noted. Synthetic meetings are the scripted
test sets in `test-data/synthetic/` (macOS voices, with and without simulated room acoustics).

## Build 3.1: long and large meetings (15 October 2026)

A real meeting of 3 hours 9 minutes with a head count of 10 failed at the speaker step: "Recordings longer than 90
minutes need fewer than nine speakers".

### Added

- "Copy transcript" in a meeting's menu puts the transcript alone on the clipboard as plain text, one paragraph per
  line of the transcript ("Emily [1:02:15]: …"). The clip is marked sensitive, so Android's copy confirmation
  doesn't preview the meeting's words. The 3-hour meeting's transcript was cut short when pasted into another app,
  so for transcripts over 50,000 characters the app says so and points to sharing instead.

### Fixed

- "Share notes with transcript" did nothing for the 3-hour meeting: Android hands text to the share sheet in a
  single message, and at 621 KB it failed twice and gave up without a word. Notes longer than 50,000 characters
  (about 45 minutes of transcript) are now shared as a text file, through a file provider that lets only the app
  picked in the share sheet read that one file.
- Meetings of more than eight people had to be under 90 minutes. Above eight, the speakers came from the clustering
  pipeline (pyannote, WeSpeaker and clustering in sherpa-onnx), which needs the whole recording in memory, and 90
  minutes was the most the app's 512 MB of Java memory could hold. These meetings now go through Nemotron, which
  reads the audio a window at a time, so any length works (see Changed).
- "Try again" after a failure started again from the beginning and threw away the finished transcription. A failed
  job recorded the stage it had started from instead of the one that failed, and retrying cleared everything from
  there; "Retry" on the meeting list even queued the meeting as if it had never been processed. Both now carry on
  with the first unfinished stage and keep everything before it, and failed jobs record the stage that failed. The
  3-hour meeting had been transcribed three times (about 11 minutes each) by the time it reached this build.
- Notes for meetings of several hours could fail at the very end: a 3-hour transcript is summarised in about eight
  parts, and their notes together could exceed the language model's 10,240-token context in the combining request.
  Notes that don't fit one request are now combined in batches, and the batches' notes combined in turn.
- The combined notes of a 3-hour meeting were cut off at the 2,048-token limit for a reply (the reply ended after
  exactly the time 2,048 tokens take) and couldn't be read, so the parts' notes were joined as they were. Combining
  now allows 3,072 tokens.
- The search index failed on those joined notes: the notes are indexed as one passage, and at 3,684 tokens it was
  over the embedding model's 1,024. Notes are now indexed in pieces of whole lines of up to about 200 words, like
  the transcript, and any longer input is cut to fit.
- Reading audio made a second, byte copy of the whole range read, half again as much memory; it now reads in 128 KB
  blocks.
- Speaker colours: twelve instead of eight before they repeat.

### Changed

- More than eight people: Nemotron follows up to eight voices, and two people beyond that end up sharing one of its
  speakers. Each speaker's speech is then diarised again on its own, where the few voices sharing it should come
  apart, and speakers found to hold more than one voice are split, those whose parts sound least alike first, up to
  the head count. A re-split to or from more than eight people runs the speaker stage again.
- The clustering pipeline no longer processes meetings; it stays in the benchmarks for comparison
  (`fallback=true`), and clusters recordings over an hour in chunks of up to an hour, cut in pauses, whose speakers
  are matched up by voice.

### Measured

Wrong-speaker words, which the Build 1 notes didn't report for the clustering pipeline:

| Synthetic meeting | Clustering pipeline | Nemotron |
| --- | --- | --- |
| 4 people, room | 25.4% (3 of 4 speakers found) | 0.2% (4 of 4) |
| 6 people, room | 38.9% (4 of 6) | 9.2% (6 of 6) |
| 10 people, room, 11 minutes, first choice of voices | 48.8% (6 of 10) | 19.9% (8 of 10) |
| 10 people, room, 11 minutes, as committed | | 9.7% (8 of 10) |
| 10 people, room, 132 minutes, first choice of voices, 3 chunks | 49.5% (10 of 10) | |

The clustering pipeline lumped voices together roughly by sex (one of its speakers held three of the four men),
and asking it for 20 or 30 clusters (48.6%, 55.1%) or clustering by a distance threshold (63.7%, 79.1%) did no
better. Nemotron's errors were whole voices sharing a speaker: in the first ten-person set, Olivia with Emily and
Niamh with Thandi, everyone else separate. Those are the voices Karen and Samantha, Moira and Tessa, which Nemotron
also partly mixed up in the six-person meeting; the committed set uses Flo and Shelley for Olivia and Thandi instead.
Diarising a speaker's speech again found a second voice in none of the 16 speakers tried: the voices Nemotron had
put together were ones it could not tell apart on their own either. So the splitting step is untested on a meeting
where it would help; in these tests it split no one wrongly, and it costs about one more pass of Nemotron.

The real meeting, 3 hours 9 minutes with a head count of 10, finished with this build from its saved transcription:

| Stage | Time |
| --- | --- |
| Speakers: Nemotron, then 6 of its speakers diarised again | 10.7 minutes |
| Voices, transcript | 32 s, 3 s |
| Notes: 8 parts of 2.5 to 3.6 minutes, combining 6.1 minutes | 30.4 minutes |
| Search index (200 passages) | 88 s |

Nemotron gave six speakers enough speech to diarise again; four of them came apart into three to five voices each,
and the two most distinct were split, for 10 speakers, 9 of them with words: 1,184 utterances, 32,637 words.
Whether the splits follow the real people isn't measured; the meeting's content stays on the phone. The first
attempt at the notes, before the reply limit was raised, took 29.8 minutes.

The 132-minute ten-person meeting through the chunked clustering pipeline: speech detection 131 s, speech to text
9.6 minutes, speakers 19.3 + 0.8 minutes, peak memory of the app process 1.3 GB, word error rate 6.6%. Its word
error rates are measured ten minutes at a time (the alignment is quadratic), which counts about one point more than
aligning the whole transcript at once on the 60-minute meeting.

## Build 3: memory and speed (15 October 2026)

### Added

- Voiceprints. Naming a speaker teaches the app that voice, on the phone only; later meetings, and re-splits, name
  matching voices automatically and mark them with a voice icon, and correcting such a name teaches the right voice.
  The rename dialog has "This is me" for your own voice. Settings → Voices lists the remembered voices (rename,
  forget) and can turn the feature off.
- Names and terms (Settings). One per line, such as "Niamh: Nive, Neve": the notes use these spellings, and whole
  words the transcript wrote instead are corrected in meetings processed or re-split afterwards.
- Speculative decoding: Gemma's model file includes a small draft model that proposes several tokens at once. On by
  default; Gemma writes 18% faster, and greedy answers come out identical.
- Gemma 4 E2B as an optional, faster language model on the GPU. When it is on the phone, questions in Ask always use
  it, and notes use the model chosen in Settings (E4B by default).

### Measured

Voiceprints, on the synthetic meetings processed in turn (2, then 4, then 6 people), naming everyone after each:

| Result | Count |
| --- | --- |
| Named automatically and right | 2 (Olivia, named once in the four-person meeting; and one voice shared by two synthetic people) |
| Named wrongly | 0 |
| Known voice, not named | 3 (the two synthetic male voices, which score as alike as the same voice in another room) |

The voice embeddings of different people point in nearly the same direction (their average has length 0.93 on the
synthetic meetings and 0.84 on recordings of real people), so similarities are measured after subtracting the average
of the voiceprints and the meeting's speakers. On short real-speech clips, the same person then scores 0.35 on average
and different people −0.12, with overlap; whole meetings give steadier voices than 2-to-8-second clips. A voice
recorded through the Mac's speakers in a real room did not match the same voice in a simulated room at all. Naming is
deliberately cautious: it needs six voices to compare against, and a clear lead over every other voice.

| Language model (four-person meeting's notes, GPU) | Reading | Writing | First output | Notes |
| --- | --- | --- | --- | --- |
| Gemma 4 E4B | 216 tokens/s | 7.1 tokens/s | 7.4 s | 113 s |
| Gemma 4 E4B, speculative decoding | 216 tokens/s | 8.3 tokens/s | 7.4 s | 80 s |
| Gemma 4 E2B, speculative decoding | 400 tokens/s | 11.7 tokens/s | 4.0 s | 69 s |

E2B's notes had the right facts but were less discerning: action items listed as decisions, and questions the
meeting had settled listed as open. Its GPU weight cache is 0.8 GB against E4B's 6.6 GB.

Questions answered by E2B, on the same retrieval test as Build 2: expected sources retrieved for 12 of 12, correct
answers 11 of 12 (the same miss as E4B), every expected source cited for 10 of 12 (E4B: 11). First words after 2.0 to
4.4 s, median 3.0 s (E4B: median 6.1 s, at most 8.0 s); full answers median 4.1 s (E4B: 8.6 s).

Why answers moved to E2B: in the release build, opening Ask straight after a meeting had been summarised, with E4B
still loaded, ran the phone out of memory. Android closed dozens of apps within a second, the launcher twice and this
app last, although it was in the foreground. E4B on the GPU takes about 5 to 6 GB of the phone's 11.9 GB in 32-bit;
E2B about 0.8 GB.

The 60-minute synthetic meeting end to end, with the Build 3 defaults (E4B on the GPU, speculative decoding):

| Stage | Time |
| --- | --- |
| Speech detection, speech to text, speakers, alignment | 5.7 minutes (target 10) |
| Notes | 10.1 minutes (target 3) |
| Search index | 37 s |
| Total | 16.4 minutes |

Word error rate 6.0%, wrong-speaker words 0.2%, all 4 speakers found; peak memory of the app process 1.7 GB.
Android's estimate of the energy used was 76.5 mAh by the app's processor time plus 61.2 mAh by the GPU, which it
doesn't assign to apps but nothing else was using: about 138 mAh, or 2.7% of the 5,087 mAh battery (target 10%).

### Tried and left out

- The Tensor G6 NPU. The Gemma 4 E2B build for this chip loads with Google's LiteRT NPU plugins
  (`litert-npu-runtime-google-tensor`, which declares only the two EdgeTPU vendor libraries), but the phone's EdgeTPU
  runtime refuses to create an invocation context for it. Removed again, with the 3.3 GB model; worth retrying with
  newer LiteRT or Pixel software.
- The "-gpu" E2B build, which wrote only random tokens on this phone with LiteRT-LM 0.18 (the general build works).
- Hotwords in speech to text (sherpa-onnx's beam search with boosted words). It fixed "Thandi" and "Niamh", but on
  the same recordings dropped whole sentences and, with an unrelated hotword, wrote that word in their place.
- Live captions while recording (optional in the plan): they need a separate streaming speech model running
  alongside the recording. Not built.
- FLAC compression: not built. A recording is 115 MB per hour as WAV, and Settings can delete audio after processing.

The release build (shrunk and optimised by R8) was checked on the phone: a meeting processed from the start through
every stage (129 s for 4.2 minutes of audio), a PDF added and indexed, and a question answered with page citations.

### Still to check in real use

- A recording of 30 minutes or more with the screen off (Build 1's checklist): left for a real meeting.
- An import of a real Plaud recording, and voiceprints with real colleagues' voices.
- Memory while notes are written: E4B leaves little room (0.8 GB free at the lowest during a 60-minute meeting), so
  Android may close other apps meanwhile. If that disrupts the phone, choosing E2B for notes in Settings frees about
  5 GB at some cost to the notes.

## Build 2: questions and documents (14 October 2026)

### Added

- Search index over meeting transcripts, meeting notes and documents: EmbeddingGemma 2 (270M) vectors, cut to 256
  dimensions, plus an FTS4 keyword index ranked with BM25, merged with reciprocal rank fusion.
- Library: add PDF, Word (DOCX), text and Markdown files from the phone. Files are copied into the app; PDFs keep their
  pages, and Word documents their page breaks.
- Ask: questions across all meetings and documents, or within one meeting (the meeting's Ask tab), with filters for
  date range and documents. Answers stream in, cite their passages, and each citation opens the transcript at the
  moment it was said (and plays from there) or the document at the cited page.
- Long chats are folded into a short memo so they keep fitting the language model's context.
- The search index rebuilds itself after an update that changes how it is built.
- Retrieval test (`test-data/retrieval/questions.json`): 12 questions over the three synthetic meetings and four
  documents, including a 100-page PDF and questions whose answer is split between a meeting and a document.

### Measured

| Check | Result |
| --- | --- |
| Expected sources retrieved | 12 of 12 questions |
| Correct answers | 11 of 12 (the miss names "one engineer" but not Tom; it varies between runs) |
| Every expected source cited | 11 of 12 |
| Time to first words | 3.7 to 8.0 s, median 6.1 s; the first question after opening Ask is the slowest (target 8 s) |
| Time to full answer | 6.5 to 16.2 s, median 8.6 s (target 30 s) |
| 100-page PDF | Indexed without errors: 193 passages in 46 to 75 s, of which text extraction takes 0.14 s |

### Fixed after checking every screen on the phone

- A citation of a meeting opened the transcript at the top instead of at the cited moment.
- While an import was still being written, its meeting offered "Process", which would have processed part of a file.
- The time-left estimate stood still during the summary, and said "Under a minute left" for most of it.
- The recording notification now shows the elapsed time.
- Importing was about 3 times faster after replacing the resampler's per-sample filter maths with precomputed taps.
- Opening Ask now also warms up the GPU's reading of prompts, which took about 2.5 s off the first answer.

### Changes from the plan

- Passages are about 200 words with 40 words of overlap (plan: 350 and 50), and 5 go into a prompt (plan: 6 to 8).
  Larger passages pushed the first words past 8 seconds.
- Document passages never cross a page break, so a citation opens the page that holds the passage. The first passage
  of each page starts with the previous page's last 40 words.
- Answers use greedy decoding. With sampling, figures copied from passages were sometimes garbled.

## Build 1: the core loop (14 October 2026)

### Added

- Recording in a foreground service: 16 kHz mono WAV written crash-safely (header rewritten and synced every 5 s,
  repaired after a crash), choice of microphone including Bluetooth headsets, pause and stop from the notification,
  automatic pause while a call takes the microphone, and checks for free space.
- Import of any audio file the phone can decode (M4A, MP3, WAV, AAC, Opus), mixed to mono and resampled to 16 kHz.
- Processing in a foreground service with checkpoints, so an interrupted job resumes where it stopped: speech
  detection (Silero VAD), speech to text with word timings (Parakeet TDT 0.6B v2), who spoke when (NVIDIA Nemotron 3
  Diarization, ported to Kotlin, up to 8 speakers; pyannote with WeSpeaker above 8), alignment of words to speakers,
  notes (Gemma 4 E4B) and the search index. An option holds processing until the phone is charging.
- Meeting screen: summary, key points, decisions, open questions and action items with the time each was said;
  transcript with speaker colours, tap to play, search, rename a speaker (also in the notes), move a line to another
  speaker, and re-split with a different number of people (names follow the voices). Notes share as Markdown.
- Settings: model check with checksums, recording source, language model on GPU or CPU, audio retention,
  per-stage timings of the last run, and deleting everything.
- Synthetic test meetings with 2, 4 and 6 speakers, a 60-minute version, scoring scripts and on-device benchmarks.

### Measured

Speech to text and speakers on the synthetic meetings (wrong-speaker words: words attributed to the wrong person):

| Meeting | Word error rate | Wrong-speaker words | Speakers found |
| --- | --- | --- | --- |
| 2 people, room | 1.5% | 0.0% | 2 of 2 |
| 2 people, clean | 1.5% | 0.0% | 2 of 2 |
| 4 people, room | 5.6% | 0.2% | 4 of 4 |
| 4 people, clean | 1.7% | 0.9% | 4 of 4 |
| 6 people, room | 5.7% | 9.2% | 6 of 6 |
| 6 people, clean | 5.3% | 21.6% | 5 of 6 |
| 4 people, M4A import | 5.9% | (not separated in this test) | |
| 4 people, recorded in the app from the Mac's speakers | 2.9% | 0.0% | 4 of 4 |

The last row is a real recording: the clean synthetic meeting played through the Mac's speakers and recorded by the
app on the phone lying on the desk. Speech detection, speech to text and speakers took 35 s for 4.2 minutes of audio,
and the notes 108 s.

60-minute synthetic meeting: speech detection 46 s, speech to text 272 s, speakers 112 + 19 s, alignment 1 s, so
about 7.5 minutes before the notes (target 10 minutes). The notes took 10.4 minutes (target 3 minutes; see below).

Nemotron port: identical to the reference implementation in 32-bit on both the Mac and the phone. The phone runs the
8-bit model, which agrees with the reference on 99.1% to 99.997% of frames; the differences come from the phone's
8-bit arithmetic, and speaker error stays in line with the reference (table in the development plan).

Privacy: the app requests no network permission, holds none, and owns no network sockets while running
(`scripts/check-manifest.sh`). Logs were searched for meeting and document text after a full test run; none appeared.

### Changes from the plan

- Gemma runs on the GPU by default, with 32-bit intermediate values. In the GPU's default 16-bit format, notes for
  meetings longer than about 20 minutes broke down into malformed text; 32-bit costs no speed but about 2 GB more
  memory (free memory fell to 0.8 GB during a 60-minute meeting's notes).
- The language model's context is 10,240 tokens, not 16,384 or 32,768, so a meeting is summarised in parts of about
  23 minutes that are then combined. A 32K context on the GPU hung the phone until its hardware watchdog restarted it,
  and on the CPU a longer part could outlast LiteRT-LM's 10-minute limit for one reply.
- Notes are first generated freely and read leniently; only a reply that still can't be read is generated again under
  the JSON schema, which is slower. Long meetings still get notes if one part or the combining step fails.
- The phone keeps the 8-bit Nemotron model (about twice as fast as 32-bit, same accuracy on the test sets).
- Long stretches of speech are cut at their quietest point between 8 and 20 seconds before speech to text; Parakeet
  skipped words in regions over about 20 seconds.
- The app asks for `MODIFY_AUDIO_SETTINGS`, which Android requires to record from a Bluetooth headset.

### Privacy fixes found during the build

- ONNX Runtime (used for Nemotron, and for the Mac reference) contains Microsoft telemetry. It is switched off in code
  and by environment variable, and the start-up hook its Android library adds, which reads the device's ANDROID_ID,
  is removed from the app. The build now fails if any library adds a component that starts code, or a network
  permission. Two early reference runs on the Mac ran before telemetry was switched off; they ran in a sandbox that
  only allows package registries, so they very likely could not send anything.
- Google Play Protect asked to upload the test build to Google for a security check while the phone was unlocked
  during installation. The upload was declined (see the README).

### Known issues

- Notes take about 2 minutes per 23 minutes of meeting plus 3 minutes to combine, well over the 3-minute target for a
  60-minute meeting: Gemma 4 E4B writes about 6 to 7 tokens a second on this phone. Build 3 adds speculative decoding
  and an optional E2B; the gap remains (see Build 3).
- Six people with similar voices: one speaker can be missed and about a fifth of the words given to the wrong person.
- Importing decodes at about 9 times real time in the debug build (a 60-minute file takes about 7 minutes).
- The model caches in the app's cache folder take about 9 GB (GPU 6.7 GB, CPU 2.2 GB); Android can clear them, and
  they are rebuilt on the next load.
