#!/usr/bin/env python3
"""Builds synthetic test meetings from scripted dialogues read by built-in macOS voices.

For each test-data/synthetic/<name>/script.json this writes, next to it:
  clean.wav   the voices with natural gaps, interruptions and overlaps
  room.wav    the same with per-speaker distance, reverberation and room noise (roughly a phone on a table)
  truth.json  every line with its speaker, start, end and text
  truth.rttm  the speaker timeline in RTTM format
With --long-minutes N, the room version of one set is also repeated into long.wav (plus long.truth.*) for timing.
With --absent K as well, each pass is laid out afresh instead, with K people away from it in turn.

Run with .tooling/venv/bin/python3 scripts/make-synthetic-set.py [set ...]. The output is deterministic.
"""
import argparse
import json
import subprocess
import tempfile
import wave
import zlib
from pathlib import Path

import numpy as np

ROOT = Path(__file__).resolve().parent.parent
SETS = ROOT / "test-data" / "synthetic"
SR = 16_000


def render(voice: str, text: str, tmp: Path) -> np.ndarray:
    out = tmp / "line.wav"
    subprocess.run(["say", "-v", voice, "--file-format=WAVE", "--data-format=LEI16@16000", "-o", str(out), text], check=True)
    with wave.open(str(out)) as w:
        assert w.getframerate() == SR and w.getnchannels() == 1 and w.getsampwidth() == 2, f"{voice}: unexpected format"
        audio = np.frombuffer(w.readframes(w.getnframes()), dtype="<i2").astype(np.float32) / 32768
    return trim(audio)


def trim(audio: np.ndarray, below_peak_db: float = 45.0, pad_s: float = 0.03) -> np.ndarray:
    """Cuts leading and trailing silence, so each line's start and end in the ground truth are accurate."""
    frame = SR // 100
    n = len(audio) // frame
    energy = np.sqrt(np.mean(audio[: n * frame].reshape(n, frame) ** 2, axis=1) + 1e-12)
    loud = np.nonzero(20 * np.log10(energy / energy.max()) > -below_peak_db)[0]
    start = max(0, loud[0] * frame - int(pad_s * SR))
    end = min(len(audio), (loud[-1] + 1) * frame + int(pad_s * SR))
    return audio[start:end]


def normalise(clip: np.ndarray, rms_db: float) -> np.ndarray:
    rms = np.sqrt(np.mean(clip ** 2)) + 1e-12
    return clip * (10 ** (rms_db / 20) / rms)


def room_impulse(rng: np.random.Generator, distance_m: float) -> np.ndarray:
    """A direct path plus an exponentially decaying diffuse tail; farther speakers get relatively more reverb."""
    rt60 = rng.uniform(0.35, 0.6)
    t = np.arange(int(rt60 * SR)) / SR
    tail = rng.standard_normal(len(t)) * np.exp(-6.9 * t / rt60)
    tail[: int(0.003 * SR)] = 0
    drr_db = 8.0 - 6.0 * distance_m
    tail *= 10 ** (-drr_db / 20) / np.sqrt(np.sum(tail ** 2))
    tail[0] = 1.0
    return tail.astype(np.float32)


def convolve(signal: np.ndarray, kernel: np.ndarray) -> np.ndarray:
    n = len(signal) + len(kernel) - 1
    size = 1 << (n - 1).bit_length()
    return np.fft.irfft(np.fft.rfft(signal, size) * np.fft.rfft(kernel, size), size)[:n].astype(np.float32)


def pink_noise(rng: np.random.Generator, n: int) -> np.ndarray:
    spectrum = np.fft.rfft(rng.standard_normal(n))
    freqs = np.fft.rfftfreq(n, 1 / SR)
    spectrum[1:] /= np.sqrt(freqs[1:])
    spectrum[0] = 0
    noise = np.fft.irfft(spectrum, n)
    return (noise / np.sqrt(np.mean(noise ** 2))).astype(np.float32)


def write_wav(path: Path, audio: np.ndarray) -> None:
    peak = np.max(np.abs(audio))
    audio = audio * (0.89 / peak) if peak > 0.89 else audio
    with wave.open(str(path), "wb") as w:
        w.setnchannels(1)
        w.setsampwidth(2)
        w.setframerate(SR)
        w.writeframes((np.clip(audio, -1, 1) * 32767).astype("<i2").tobytes())


def write_truth(stem: Path, name: str, title: str, segments: list, duration: float) -> None:
    speakers = sorted({s["speaker"] for s in segments}, key=lambda n: min(s["start"] for s in segments if s["speaker"] == n))
    truth = {"title": title, "durationSec": round(duration, 3), "speakers": speakers, "segments": segments}
    Path(f"{stem}.json").write_text(json.dumps(truth, indent=1) + "\n")
    rttm = [f"SPEAKER {name} 1 {s['start']:.3f} {s['end'] - s['start']:.3f} <NA> <NA> {s['speaker']} <NA> <NA>" for s in segments]
    Path(f"{stem}.rttm").write_text("\n".join(rttm) + "\n")


def place(lines: list, clips: list, rng: np.random.Generator, first_start: float = 0.6):
    """Lays the lines out one after another with natural gaps, interruptions and overlaps."""
    segments, placed = [], []
    previous_start, previous_end = 0.0, first_start
    for (speaker, text, *gap), clip in zip(lines, clips):
        if gap:
            pause = gap[0]
        else:
            pause = rng.uniform(0.15, 0.8) if rng.random() > 0.1 else -rng.uniform(0.15, 0.4)
        start = max(previous_start + 0.3, previous_end + pause) if segments else previous_end
        end = start + len(clip) / SR
        segments.append({"speaker": speaker, "start": round(start, 3), "end": round(end, 3), "text": text})
        placed.append((speaker, int(start * SR), clip))
        previous_start, previous_end = start, max(previous_end, end)
    return segments, placed


def build_varied_long(folder: Path, script: dict, clips: list, seats: dict, long_minutes: int, absent: int) -> None:
    """A long version that isn't one recording repeated: each pass through the script is laid out afresh, and a
    different group of [absent] people is away for it, as in a long meeting where people come and go."""
    rng = np.random.default_rng(zlib.crc32((folder.name + "-long").encode()))
    speakers = list(script["voices"])
    wet = [convolve(clip, seats[speaker][1]) / seats[speaker][0] for (speaker, *_), clip in zip(script["lines"], clips)]
    segments, placed, start, k = [], [], 0.6, 0
    while start < long_minutes * 60:
        away = {speakers[(k * absent + j) % len(speakers)] for j in range(absent)}
        keep = [i for i, (speaker, *_) in enumerate(script["lines"]) if speaker not in away]
        lines = [[script["lines"][i][0], script["lines"][i][1]] for i in keep]
        part, part_placed = place(lines, [clips[i] for i in keep], rng, start)
        segments += part
        placed += [(speaker, offset, wet[i]) for (speaker, offset, _), i in zip(part_placed, keep)]
        start = max(s["end"] for s in part) + rng.uniform(1.0, 3.0)
        k += 1
    duration = start + 1.0
    total = int(duration * SR)
    room = np.zeros(total + 10 * SR, np.float32)
    for _, offset, clip in placed:
        room[offset:offset + len(clip)] += clip
    room = room[:total]
    speech_rms = np.sqrt(np.mean(room[room != 0] ** 2))
    noise = pink_noise(rng, 300 * SR)
    room += np.tile(noise, total // len(noise) + 1)[:total] * speech_rms * 10 ** (-25 / 20)
    write_wav(folder / "long.wav", room)
    write_truth(folder / "long.truth", folder.name + "-long", script["title"], segments, duration)
    print(f"{folder.name}: varied long version {duration / 60:.1f} min, {k} passes, {absent} away in each")


def build(folder: Path, long_minutes: int, absent: int = 0) -> None:
    script = json.loads((folder / "script.json").read_text())
    rng = np.random.default_rng(zlib.crc32(folder.name.encode()))
    with tempfile.TemporaryDirectory() as tmp:
        clips = [normalise(render(script["voices"][speaker], text, Path(tmp)), -22 + rng.uniform(-2, 2))
                 for speaker, text, *_ in script["lines"]]

    segments, placed = place(script["lines"], clips, rng)
    duration = max(s["end"] for s in segments) + 1.0
    total = int(duration * SR)

    clean = np.zeros(total, np.float32)
    for _, offset, clip in placed:
        clean[offset:offset + len(clip)] += clip

    seats = {}
    for speaker in script["voices"]:
        distance = rng.uniform(0.6, 2.4)
        seats[speaker] = (distance, room_impulse(rng, distance))
    room = np.zeros(total + SR, np.float32)
    for speaker, offset, clip in placed:
        distance, impulse = seats[speaker]
        wet = convolve(clip, impulse) / distance
        room[offset:offset + len(wet)] += wet
    room = room[:total]
    speech_rms = np.sqrt(np.mean(room[room != 0] ** 2))
    room += pink_noise(rng, total) * speech_rms * 10 ** (-25 / 20)

    write_wav(folder / "clean.wav", clean)
    write_wav(folder / "room.wav", room)
    write_truth(folder / "truth", folder.name, script["title"], segments, duration)
    print(f"{folder.name}: {len(segments)} lines, {duration / 60:.1f} min, {len(script['voices'])} speakers")

    if long_minutes and absent:
        build_varied_long(folder, script, clips, seats, long_minutes, absent)
    elif long_minutes:
        repeats = int(np.ceil(long_minutes * 60 / duration))
        long_segments = [dict(s, start=round(s["start"] + k * duration, 3), end=round(s["end"] + k * duration, 3))
                         for k in range(repeats) for s in segments]
        write_wav(folder / "long.wav", np.tile(room, repeats))
        write_truth(folder / "long.truth", folder.name + "-long", script["title"], long_segments, duration * repeats)
        print(f"{folder.name}: long version {duration * repeats / 60:.1f} min")


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("sets", nargs="*", help="set names (default: all)")
    parser.add_argument("--long-minutes", type=int, default=0, help="also write a long version of these sets")
    parser.add_argument("--absent", type=int, default=0,
                        help="lay the long version out afresh for each pass, with this many people away from each")
    args = parser.parse_args()
    folders = [SETS / name for name in args.sets] or sorted(p.parent for p in SETS.glob("*/script.json"))
    for folder in folders:
        build(folder, args.long_minutes, args.absent)


if __name__ == "__main__":
    main()
