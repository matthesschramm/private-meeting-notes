#!/usr/bin/env bash
# Creates the personal key that signs every build of the app, debug and release alike.
# Android only installs an update signed with the same key as the installed app. A different key forces an
# uninstall, which deletes the app's meetings and models. Run once, then back up signing/ and keystore.properties.
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
KEYSTORE="$ROOT/signing/private-meeting-notes.jks"
PROPS="$ROOT/keystore.properties"

if [[ -e "$KEYSTORE" || -e "$PROPS" ]]; then
  echo "A signing key already exists; refusing to replace it." >&2
  exit 1
fi

source "$ROOT/scripts/env.sh"
umask 077
mkdir -p "$ROOT/signing"
PASSWORD="$(openssl rand -base64 48 | tr -d '/+=\n' | head -c 32)"

keytool -genkeypair -keystore "$KEYSTORE" -storetype PKCS12 -alias private-meeting-notes -keyalg RSA -keysize 4096 \
  -validity 36500 -storepass "$PASSWORD" -keypass "$PASSWORD" -dname "CN=Private Meeting Notes, O=Personal" >/dev/null

cat > "$PROPS" <<EOF
storeFile=signing/private-meeting-notes.jks
storePassword=$PASSWORD
keyAlias=private-meeting-notes
keyPassword=$PASSWORD
EOF

echo "Created signing/private-meeting-notes.jks and keystore.properties. Back up both; they are not in git."
