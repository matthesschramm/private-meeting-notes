#!/usr/bin/env bash
# Copies the models the app needs from models/ to the phone over USB, into the app's own storage.
# Files already on the phone at the right size are skipped; the app verifies checksums itself.
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
source "$ROOT/scripts/env.sh"
PACKAGE=dev.matthes.privatemeetingnotes
DEST="/sdcard/Android/data/$PACKAGE/files/models"
REGISTRY="$ROOT/app/src/main/assets/model-registry.json"

adb get-state >/dev/null
if ! adb shell pm path "$PACKAGE" </dev/null >/dev/null 2>&1; then
  echo "Install the app first (./gradlew installDebug)." >&2
  exit 1
fi

# The app creates its model folders when it starts. Folders created over adb would belong to the shell user,
# and the app could not read them.
if ! adb shell "[ -d $DEST/silero-vad ]" </dev/null; then
  adb shell am start -n "$PACKAGE/.MainActivity" </dev/null >/dev/null
  for _ in $(seq 20); do
    adb shell "[ -d $DEST/silero-vad ]" </dev/null && break
    sleep 0.5
  done
fi
if [[ "$(adb shell stat -c %U "$DEST" </dev/null | tr -d '\r')" == shell ]]; then
  echo "$DEST belongs to the shell user, so the app can't read it. Delete it with" >&2
  echo "  adb shell rm -rf $DEST" >&2
  echo "then start the app once and run this script again." >&2
  exit 1
fi

python3 -c '
import json, sys
for model in json.load(open(sys.argv[1]))["models"]:
    for f in model["files"]:
        print(f["path"], f["size"])
' "$REGISTRY" | while read -r path size; do
  local_file="$ROOT/models/$path"
  if [[ ! -f "$local_file" ]]; then
    echo "Missing $local_file. Run: python3 scripts/fetch-models.py" >&2
    exit 1
  fi
  remote_size="$(adb shell stat -c %s "$DEST/$path" </dev/null 2>/dev/null | tr -d '\r' || true)"
  if [[ "$remote_size" == "$size" ]]; then
    echo "on phone  $path"
    continue
  fi
  if ! adb shell "[ -d $DEST/$(dirname "$path") ]" </dev/null; then
    echo "The app has not created $DEST/$(dirname "$path"); update the app and start it once." >&2
    exit 1
  fi
  adb push "$local_file" "$DEST/$path" </dev/null >/dev/null 2>&1
  echo "pushed    $path"
done
echo "Done. Open the app's Settings to see model status."
