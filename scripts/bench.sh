#!/usr/bin/env bash
# Runs a debug-only pipeline benchmark on the phone and pulls its results into test-data/bench-results/.
#   ./scripts/bench.sh <method> <input file> [key=value ...]     e.g. ./scripts/bench.sh asr meeting.wav threads=6
# Set SKIP_BUILD=1 to reuse the APKs already installed.
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
source "$ROOT/scripts/env.sh"
cd "$ROOT"

METHOD="$1"
WAV="$2"
shift 2
PACKAGE=dev.matthes.privatemeetingnotes
BENCH="/sdcard/Android/data/$PACKAGE/files/bench"
OUT="$ROOT/test-data/bench-results"
# Prefix with the folder name: every synthetic set has a room.wav.
NAME="$(basename "$(dirname "$WAV")")-$(basename "$WAV")"

if [[ "${SKIP_BUILD:-0}" != 1 ]]; then
  ./gradlew -q assembleDebug assembleDebugAndroidTest 2>&1 | { grep -v sysconf || true; }
  adb install -r app/build/outputs/apk/debug/app-debug.apk >/dev/null
  adb install -r -t app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk >/dev/null
fi

# Debug builds create bench/ at start-up; one created over adb would belong to the shell user.
if ! adb shell "[ -d $BENCH ]"; then
  adb shell am start -n "$PACKAGE/.MainActivity" >/dev/null
  sleep 2
fi
adb shell "rm -f $BENCH/${NAME%.*}.*.json $BENCH/${NAME%.*}.*.f32"
adb push "$WAV" "$BENCH/$NAME" >/dev/null
# EXTRA="local/path:name-on-phone ..." pushes more files the method reads, such as documents.
for extra in ${EXTRA:-}; do
  adb push "${extra%%:*}" "$BENCH/${extra##*:}" >/dev/null
done

extra=()
for pair in "$@"; do
  value="${pair#*=}"
  # adb shell hands the command line to the phone's shell, so values with spaces or symbols need quoting there.
  extra+=(-e "${pair%%=*}" "'${value//\'/\'\\\'\'}'")
done

adb shell am instrument -w -e class "$PACKAGE.bench.BenchTest#$METHOD" -e input "$NAME" ${extra[@]+"${extra[@]}"} \
  "$PACKAGE.test/androidx.test.runner.AndroidJUnitRunner" | grep -vE "^\s*$|^INSTRUMENTATION_STATUS(_CODE)?: (class|current|id|numtests|stream|test)" || true

mkdir -p "$OUT"
for result in $(adb shell "ls $BENCH/${NAME%.*}.*.json $BENCH/${NAME%.*}.*.f32 2>/dev/null" | tr -d '\r'); do
  adb pull "$result" "$OUT/" >/dev/null
  echo "pulled $(basename "$result")"
done
