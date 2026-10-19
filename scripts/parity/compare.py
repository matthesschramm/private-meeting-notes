#!/usr/bin/env python3
"""Nemotron parity on the phone: compares speaker probabilities the app computed with the reference pipeline's.

    .tooling/venv/bin/python3 scripts/parity/compare.py <phone.diar.f32> <reference.f32> [<phone> <reference> ...]

Pass mark (development plan, section 9.2): at least 99.9% per-frame agreement after thresholding at 0.5, and every
segment boundary of the reference (runs of 50 ms or more) matched within 20 ms. Exits non-zero if any pair fails.
"""
import sys

import numpy as np

SPEAKERS = 8
FRAME_MS = 10
TOLERANCE_MS = 20
MIN_RUN_FRAMES = 5


def runs(active: np.ndarray):
    padded = np.concatenate([[False], active, [False]])
    changes = np.flatnonzero(padded[1:] != padded[:-1])
    starts, ends = changes[0::2], changes[1::2]
    keep = ends - starts >= MIN_RUN_FRAMES
    return starts[keep], ends[keep]


def compare(ours: np.ndarray, reference: np.ndarray) -> dict:
    if ours.shape != reference.shape:
        raise SystemExit(f"length differs: {ours.size} vs {reference.size}")
    ours, reference = ours.reshape(-1, SPEAKERS), reference.reshape(-1, SPEAKERS)
    total = off = max_shift = 0
    for s in range(SPEAKERS):
        for mine, theirs in zip(runs(ours[:, s] > 0.5), runs(reference[:, s] > 0.5)):
            for edge in theirs:
                total += 1
                shift = int(np.min(np.abs(mine - edge))) * FRAME_MS if len(mine) else None
                if shift is None or shift > TOLERANCE_MS:
                    off += 1
                else:
                    max_shift = max(max_shift, shift)
    agreement = float(np.mean((ours > 0.5) == (reference > 0.5)))
    return {
        "frames": len(ours),
        "agreement": agreement,
        "maxProbDiff": float(np.max(np.abs(ours - reference))),
        "boundaries": total,
        "boundariesOff": off,
        "maxShiftMs": max_shift,
        "passes": agreement >= 0.999 and off == 0,
    }


def main() -> None:
    pairs = sys.argv[1:]
    if not pairs or len(pairs) % 2:
        raise SystemExit(__doc__)
    failed = False
    for phone, reference in zip(pairs[0::2], pairs[1::2]):
        r = compare(np.fromfile(phone, dtype="<f4"), np.fromfile(reference, dtype="<f4"))
        failed |= not r["passes"]
        print(f"{'PASS' if r['passes'] else 'FAIL'}  {phone}: {r['frames']} frames, agreement {100 * r['agreement']:.4f}%, "
              f"max prob diff {r['maxProbDiff']:.4f}, {r['boundaries']} boundaries, {r['boundariesOff']} beyond 20 ms "
              f"(max shift {r['maxShiftMs']} ms)")
    sys.exit(1 if failed else 0)


if __name__ == "__main__":
    main()
