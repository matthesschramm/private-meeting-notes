#!/usr/bin/env python3
"""Accuracy against the synthetic meetings' ground truth.

    .tooling/venv/bin/python3 scripts/score.py diar  <truth.json> <probs.f32> [...]      speaker activity, frames x 8
    .tooling/venv/bin/python3 scripts/score.py words <truth.json> <transcript.json>      a transcript from the bench

Diarisation error rate (DER) is computed per 10 ms frame after mapping hypothesis speakers to reference speakers one
to one, maximising overlap. It is missed speech + false alarm + speaker confusion, over total reference speech;
overlapping speech counts once per speaker. "Wrong speaker" is the confusion part alone. Results are shown without a
collar and with the customary 0.25 s collar around reference boundaries.

For transcripts, WER compares normalised words (lower case, no punctuation), and "wrong-speaker words" is the share
of correctly recognised words attributed to a different speaker than the one who said them.
"""
import itertools
import json
import re
import sys

import numpy as np

FRAME = 0.01


def reference_activity(truth: dict, frames: int):
    speakers = truth["speakers"]
    ref = np.zeros((frames, len(speakers)), bool)
    for s in truth["segments"]:
        ref[int(round(s["start"] / FRAME)):int(round(s["end"] / FRAME)), speakers.index(s["speaker"])] = True
    return ref, speakers


def collar_mask(truth: dict, frames: int, collar: float) -> np.ndarray:
    keep = np.ones(frames, bool)
    if collar <= 0:
        return keep
    c = int(round(collar / FRAME))
    for s in truth["segments"]:
        for edge in (int(round(s["start"] / FRAME)), int(round(s["end"] / FRAME))):
            keep[max(0, edge - c):edge + c] = False
    return keep


def assign(score: np.ndarray) -> dict:
    """Row -> column, one to one, maximising the total score. Brute force: matrices here are at most 8 x 8."""
    rows, cols = score.shape
    best, best_total = {}, -1
    if rows <= cols:
        for perm in itertools.permutations(range(cols), rows):
            total = sum(score[r, c] for r, c in enumerate(perm))
            if total > best_total:
                best, best_total = dict(enumerate(perm)), total
    else:
        for perm in itertools.permutations(range(rows), cols):
            total = sum(score[r, c] for c, r in enumerate(perm))
            if total > best_total:
                best, best_total = {r: c for c, r in enumerate(perm)}, total
    return best


def best_mapping(ref: np.ndarray, hyp: np.ndarray) -> dict:
    """Hypothesis speaker -> reference speaker, maximising overlapping speech."""
    used = [h for h in range(hyp.shape[1]) if hyp[:, h].any()]
    overlap = hyp[:, used].T.astype(np.int64) @ ref.astype(np.int64)
    return {used[h]: r for h, r in assign(overlap).items()}


def der(ref: np.ndarray, hyp: np.ndarray, keep: np.ndarray) -> dict:
    mapping = best_mapping(ref, hyp)
    mapped = np.zeros_like(ref)
    for h, r in mapping.items():
        mapped[:, r] |= hyp[:, h]
    n_ref, n_hyp = ref.sum(1), hyp.sum(1)
    correct = (mapped & ref).sum(1)
    miss = np.maximum(0, n_ref - n_hyp)
    fa = np.maximum(0, n_hyp - n_ref)
    conf = np.minimum(n_ref, n_hyp) - correct
    total = n_ref[keep].sum()
    return {k: float(v[keep].sum() / total) for k, v in (("miss", miss), ("falseAlarm", fa), ("confusion", conf))} | {
        "der": float((miss + fa + conf)[keep].sum() / total),
        "hypSpeakers": int(sum(hyp[:, h].any() for h in range(hyp.shape[1]))),
    }


def score_diar(truth_path: str, probs_path: str) -> None:
    truth = json.load(open(truth_path))
    probs = np.fromfile(probs_path, "<f4").reshape(-1, 8)
    ref, speakers = reference_activity(truth, len(probs))
    hyp = probs > 0.5
    for collar in (0.0, 0.25):
        r = der(ref, hyp, collar_mask(truth, len(probs), collar))
        print(f"{probs_path} collar {collar:.2f}s: DER {100 * r['der']:.1f}% (miss {100 * r['miss']:.1f}, "
              f"false alarm {100 * r['falseAlarm']:.1f}, wrong speaker {100 * r['confusion']:.1f}); "
              f"{r['hypSpeakers']} speakers found, {len(speakers)} expected")


ONES = ("zero one two three four five six seven eight nine ten eleven twelve thirteen fourteen fifteen sixteen "
        "seventeen eighteen nineteen").split()
TENS = "_ _ twenty thirty forty fifty sixty seventy eighty ninety".split()
IRREGULAR_ORDINALS = {"one": "first", "two": "second", "three": "third", "five": "fifth", "eight": "eighth",
                      "nine": "ninth", "twelve": "twelfth"}
SPELLING = {"center": "centre", "color": "colour", "favorite": "favourite", "behavior": "behaviour",
            "mmhm": "mm hm", "mhm": "mm hm", "okay": "ok"}
NOT_IZE = {"size", "prize", "seize", "capsize"}


def number_words(n: int) -> str:
    if n < 20:
        return ONES[n]
    if n < 100:
        return TENS[n // 10] + ("" if n % 10 == 0 else " " + ONES[n % 10])
    if n < 1000:
        return ONES[n // 100] + " hundred" + ("" if n % 100 == 0 else " " + number_words(n % 100))
    for value, name in ((1_000_000_000, "billion"), (1_000_000, "million"), (1_000, "thousand")):
        if n >= value:
            return number_words(n // value) + " " + name + ("" if n % value == 0 else " " + number_words(n % value))
    raise ValueError(n)


def ordinal_words(n: int) -> str:
    words = number_words(n).split()
    last = words[-1]
    words[-1] = IRREGULAR_ORDINALS.get(last) or (last[:-1] + "ieth" if last.endswith("y") else last + "th")
    return " ".join(words)


def british(word: str) -> str:
    """Treats US -ize spellings as the British -ise ones, so spelling conventions don't count as errors."""
    m = re.fullmatch(r"(\w+)iz(e|es|ed|ing|ation)", word)
    if m and word not in NOT_IZE and m.group(1) + "ize" not in NOT_IZE:
        return m.group(1) + "is" + m.group(2)
    return SPELLING.get(word, word)


def normalise(text: str) -> list:
    """Lower case, numbers spelled out, punctuation and spelling conventions removed."""
    t = text.lower()
    t = re.sub(r"\$\s?([\d,]+)", lambda m: m.group(1) + " dollars", t)
    t = re.sub(r"(\d)\s?%", r"\1 percent", t)
    t = re.sub(r"\b(\d+)(st|nd|rd|th)\b", lambda m: ordinal_words(int(m.group(1))), t)
    t = re.sub(r"\d[\d,]*", lambda m: number_words(int(m.group(0).replace(",", ""))), t)
    words = re.sub(r"[^a-z0-9' ]+", " ", t.replace("-", " ")).split()
    return " ".join(british(w) for w in words).split()


def align(ref: list, hyp: list):
    """Levenshtein alignment; returns (errors, pairs of matched indices)."""
    n, m = len(ref), len(hyp)
    d = np.zeros((n + 1, m + 1), np.int32)
    d[:, 0], d[0, :] = np.arange(n + 1), np.arange(m + 1)
    for i in range(1, n + 1):
        for j in range(1, m + 1):
            d[i, j] = min(d[i - 1, j] + 1, d[i, j - 1] + 1, d[i - 1, j - 1] + (ref[i - 1] != hyp[j - 1]))
    pairs, i, j = [], n, m
    while i > 0 and j > 0:
        if d[i, j] == d[i - 1, j - 1] + (ref[i - 1] != hyp[j - 1]):
            if ref[i - 1] == hyp[j - 1]:
                pairs.append((i - 1, j - 1))
            i, j = i - 1, j - 1
        elif d[i, j] == d[i - 1, j] + 1:
            i -= 1
        else:
            j -= 1
    return int(d[n, m]), pairs[::-1]


def window_edges(segments: list, window: float) -> list:
    """Times about [window] apart, each in the longest pause between reference lines near it."""
    ends = sorted(s["end"] for s in segments)
    starts = sorted(s["start"] for s in segments)
    edges, target = [0.0], window
    while target < starts[-1]:
        gaps = [(b - a, (a + b) / 2) for a, b in zip(ends, starts[1:]) if b > a and abs((a + b) / 2 - target) < window / 10]
        edges.append(max(gaps)[1] if gaps else target)
        target += window
    return edges + [float("inf")]


def score_words(truth_path: str, transcript_path: str) -> None:
    truth = json.load(open(truth_path))
    transcript = json.load(open(transcript_path))
    ref_words, ref_speakers, ref_times = [], [], []
    for s in truth["segments"]:
        for w in normalise(s["text"]):
            ref_words.append(w)
            ref_speakers.append(s["speaker"])
            ref_times.append(s["start"])
    hyp_words, hyp_speakers, hyp_times = [], [], []
    for u in transcript["utterances"]:
        for w in normalise(u["text"]):
            hyp_words.append(w)
            hyp_speakers.append(u["speaker"])
            hyp_times.append(u["start"])
    # The alignment is quadratic, so a long meeting is aligned ten minutes at a time.
    edges = window_edges(truth["segments"], 600.0) if truth["durationSec"] > 1200 else [0.0, float("inf")]
    errors, pairs = 0, []
    for lo, hi in zip(edges, edges[1:]):
        r = [i for i, t in enumerate(ref_times) if lo <= t < hi]
        h = [j for j, t in enumerate(hyp_times) if lo <= t < hi]
        e, p = align([ref_words[i] for i in r], [hyp_words[j] for j in h])
        errors += e
        pairs += [(r[a], h[b]) for a, b in p]
    # Map hypothesis speakers to reference speakers by the words they share.
    counts = {}
    for i, j in pairs:
        counts[(hyp_speakers[j], ref_speakers[i])] = counts.get((hyp_speakers[j], ref_speakers[i]), 0) + 1
    hyp_ids = sorted({h for h, _ in counts})
    ref_ids = truth["speakers"]
    matrix = np.array([[counts.get((h, r), 0) for r in ref_ids] for h in hyp_ids])
    mapping = {hyp_ids[h]: ref_ids[r] for h, r in assign(matrix).items()} if hyp_ids else {}
    wrong = sum(1 for i, j in pairs if mapping.get(hyp_speakers[j]) != ref_speakers[i])
    print(f"{transcript_path}: WER {100 * errors / len(ref_words):.1f}% ({errors} errors / {len(ref_words)} words), "
          f"wrong-speaker words {100 * wrong / max(1, len(pairs)):.1f}% of {len(pairs)} matched; "
          f"{len(hyp_ids)} speakers found, {len(ref_ids)} expected")


def main() -> None:
    if len(sys.argv) < 4 or sys.argv[1] not in ("diar", "words"):
        raise SystemExit(__doc__)
    kind, truth, hyps = sys.argv[1], sys.argv[2], sys.argv[3:]
    for hyp in hyps:
        (score_diar if kind == "diar" else score_words)(truth, hyp)


if __name__ == "__main__":
    main()
