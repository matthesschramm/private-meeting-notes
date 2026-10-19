#!/usr/bin/env bash
# Privacy check for each build: the APK and the installed app must hold no network permission, and the app must own
# no network sockets. Run during or after a full record-to-answer session for the socket check to mean something.
#   ./scripts/check-manifest.sh [path/to/app.apk]
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
source "$ROOT/scripts/env.sh"
APK="${1:-$ROOT/app/build/outputs/apk/debug/app-debug.apk}"
PACKAGE=dev.matthes.privatemeetingnotes
NETWORK='android\.permission\.(INTERNET|ACCESS_NETWORK_STATE|CHANGE_NETWORK_STATE|ACCESS_WIFI_STATE|CHANGE_WIFI_STATE)'
AAPT2="$ANDROID_HOME/build-tools/36.0.0/aapt2"
failed=0

echo "== Permissions requested by $(basename "$APK")"
"$AAPT2" dump permissions "$APK" | grep "uses-permission" | sed 's/^/  /'
if "$AAPT2" dump permissions "$APK" | grep -Eq "$NETWORK"; then
  echo "FAIL: the APK requests network access" >&2
  failed=1
fi

if adb get-state >/dev/null 2>&1 && adb shell pm path "$PACKAGE" >/dev/null 2>&1; then
  echo "== Permissions held by the installed app"
  adb shell dumpsys package "$PACKAGE" | grep -E "android.permission.[A-Z_]+: granted=true" | sed 's/^ */  /' | sort -u
  if adb shell dumpsys package "$PACKAGE" | grep -Eq "$NETWORK"; then
    echo "FAIL: the installed app holds a network permission" >&2
    failed=1
  fi

  echo "== Network sockets owned by the app"
  uid="$(adb shell cmd package list packages -U "$PACKAGE" | tr -d '\r' | grep "package:$PACKAGE " | sed 's/.*uid://')"
  sockets="$(adb shell cat /proc/net/tcp /proc/net/tcp6 /proc/net/udp /proc/net/udp6 2>/dev/null | awk -v uid="$uid" '$8 == uid' | wc -l | tr -d ' ')"
  echo "  uid $uid: $sockets sockets"
  if [[ "$sockets" != 0 ]]; then
    echo "FAIL: the app has open network sockets" >&2
    failed=1
  fi
else
  echo "(No phone with the app installed; skipped the on-device checks.)"
fi

if [[ "$failed" == 0 ]]; then echo "PASS: no network access"; fi
exit "$failed"
