#!/usr/bin/env bash
# Installs this project's private toolchain into .tooling/ (nothing system-wide).
# Safe to re-run: anything already installed is kept, every download is checksum-verified.
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
TOOLING="$ROOT/.tooling"
DOWNLOADS="$TOOLING/downloads"
SDK="$TOOLING/android-sdk"

JDK_VERSION="21.0.12.1+1"
JDK_URL="https://github.com/adoptium/temurin21-binaries/releases/download/jdk-21.0.12.1%2B1/OpenJDK21U-jdk_aarch64_mac_hotspot_21.0.12.1_1.tar.gz"
JDK_SHA256="3623232f33a9c3baadf304480b2535f9a3cba8a58d42ecbb438ba267315d9998"

CMDLINE_TOOLS_VERSION="23.0"
CMDLINE_TOOLS_URL="https://dl.google.com/android/repository/commandlinetools-mac_arm64-16111833_latest.zip"
CMDLINE_TOOLS_SHA1="ad03dc49bfacfd52c110b14104ea548b8a07e830"

# AGP 9.4 supports up to API 37 and defaults to build-tools 36.0.0.
SDK_PACKAGES=("platform-tools" "platforms/android-37.0" "build-tools/36.0.0")

GRADLE_VERSION="9.8.1"
GRADLE_URL="https://services.gradle.org/distributions/gradle-9.8.1-bin.zip"
GRADLE_SHA256="dce76f55f8e251a3a1f130eb120f30b3d271de2b76c9b0729d316b5a1b6dc01f"

# Node.js runs only the Mac-side Nemotron parity reference.
NODE_VERSION="24.21.0"
NODE_URL="https://nodejs.org/dist/v24.21.0/node-v24.21.0-darwin-arm64.tar.gz"
NODE_SHA256="bed7eea5325e1108f32ce5228ddd6a5f0f08a499ee42aa7442aea583702f6057"

# Reference JavaScript pipeline that the Kotlin Nemotron port is checked against. The repository has no licence,
# so it is fetched for local testing only and never copied into the app.
DIAR_JS_URL="https://raw.githubusercontent.com/nealcaren/local-interview-transcriber/b11f004341b75b58ed754ecd30fda1394d8431f5/diar.js"
DIAR_JS_SHA256="20af1e9348fcdb8d5fef7bee6f0d0253901fe24b2dc863ada15e9d2596074217"

if [[ "$(uname -s)" != "Darwin" || "$(uname -m)" != "arm64" ]]; then
  echo "This script targets Apple Silicon macOS." >&2
  exit 1
fi

mkdir -p "$DOWNLOADS" "$TOOLING/android-user" "$TOOLING/gradle-home"

# The Android CLI unpacks itself into ANDROID_USER_HOME (default ~/.android) and collects
# usage metrics unless --no-metrics is passed. Keep both inside the project.
export ANDROID_USER_HOME="$TOOLING/android-user"

download() {
  local url="$1" dest="$2" algo="$3" expected="$4" actual
  if [[ ! -f "$dest" ]]; then
    echo "Downloading $(basename "$dest")"
    curl -fL --retry 3 --progress-bar -o "$dest.part" "$url"
    mv "$dest.part" "$dest"
  fi
  actual="$(shasum -a "$algo" "$dest" | awk '{print $1}')"
  if [[ "$actual" != "$expected" ]]; then
    echo "Checksum mismatch for $(basename "$dest"): expected $expected, got $actual" >&2
    rm -f "$dest"
    exit 1
  fi
}

echo "== JDK (Temurin $JDK_VERSION)"
if [[ ! -x "$TOOLING/jdk/Contents/Home/bin/java" ]]; then
  download "$JDK_URL" "$DOWNLOADS/temurin-jdk.tar.gz" 256 "$JDK_SHA256"
  rm -rf "$TOOLING/jdk"
  mkdir -p "$TOOLING/jdk"
  tar -xzf "$DOWNLOADS/temurin-jdk.tar.gz" -C "$TOOLING/jdk" --strip-components=1
fi
export JAVA_HOME="$TOOLING/jdk/Contents/Home"

echo "== Android command-line tools $CMDLINE_TOOLS_VERSION"
if [[ ! -x "$SDK/cmdline-tools/latest/bin/sdkmanager" ]]; then
  download "$CMDLINE_TOOLS_URL" "$DOWNLOADS/cmdline-tools.zip" 1 "$CMDLINE_TOOLS_SHA1"
  rm -rf "$SDK/cmdline-tools" "$TOOLING/cmdline-tools-unpack"
  mkdir -p "$SDK/cmdline-tools" "$TOOLING/cmdline-tools-unpack"
  unzip -q "$DOWNLOADS/cmdline-tools.zip" -d "$TOOLING/cmdline-tools-unpack"
  mv "$TOOLING/cmdline-tools-unpack/cmdline-tools" "$SDK/cmdline-tools/latest"
  rm -rf "$TOOLING/cmdline-tools-unpack"
fi
ANDROID_CLI=("$SDK/cmdline-tools/latest/bin/android" --no-metrics --sdk="$SDK")

echo "== Android SDK packages: ${SDK_PACKAGES[*]}"
for pkg in "${SDK_PACKAGES[@]}"; do
  if [[ ! -d "$SDK/$pkg" ]]; then
    "${ANDROID_CLI[@]}" sdk install "$pkg" >> "$TOOLING/sdk-install.log"
  fi
done

echo "== Gradle $GRADLE_VERSION"
if [[ ! -x "$TOOLING/gradle/bin/gradle" ]]; then
  download "$GRADLE_URL" "$DOWNLOADS/gradle-bin.zip" 256 "$GRADLE_SHA256"
  rm -rf "$TOOLING/gradle" "$TOOLING/gradle-$GRADLE_VERSION"
  unzip -q "$DOWNLOADS/gradle-bin.zip" -d "$TOOLING"
  mv "$TOOLING/gradle-$GRADLE_VERSION" "$TOOLING/gradle"
fi

echo "== Node.js $NODE_VERSION and the Nemotron parity reference"
if [[ ! -x "$TOOLING/node/bin/node" ]]; then
  download "$NODE_URL" "$DOWNLOADS/node.tar.gz" 256 "$NODE_SHA256"
  rm -rf "$TOOLING/node"
  mkdir -p "$TOOLING/node"
  tar -xzf "$DOWNLOADS/node.tar.gz" -C "$TOOLING/node" --strip-components=1
fi
mkdir -p "$TOOLING/parity"
download "$DIAR_JS_URL" "$TOOLING/parity/diar.js" 256 "$DIAR_JS_SHA256"
cp "$ROOT/scripts/parity/package.json" "$ROOT/scripts/parity/reference.mjs" "$TOOLING/parity/"
(
  cd "$TOOLING/parity"
  export PATH="$TOOLING/node/bin:$PATH" npm_config_update_notifier=false npm_config_cache="$TOOLING/npm-cache"
  if [[ -f "$ROOT/scripts/parity/package-lock.json" ]]; then
    cp "$ROOT/scripts/parity/package-lock.json" .
    npm ci --no-audit --no-fund --loglevel=error
  else
    npm install --no-audit --no-fund --loglevel=error
    cp package-lock.json "$ROOT/scripts/parity/package-lock.json"
  fi
)

echo "== Python tools (numpy) in .tooling/venv"
if [[ ! -x "$TOOLING/venv/bin/python3" ]]; then
  python3 -m venv "$TOOLING/venv"
fi
"$TOOLING/venv/bin/pip" install --quiet --disable-pip-version-check --require-hashes -r "$ROOT/scripts/requirements.txt"

cat > "$TOOLING/VERSIONS.txt" <<EOF
JDK              Temurin $JDK_VERSION (macOS aarch64)
Android cmdline  $CMDLINE_TOOLS_VERSION
Android SDK      ${SDK_PACKAGES[*]}
Gradle           $GRADLE_VERSION
Node.js          $NODE_VERSION (parity reference only)
Python tools     $("$TOOLING/venv/bin/python3" --version 2>&1), numpy $("$TOOLING/venv/bin/python3" -c 'import numpy; print(numpy.__version__)')
Installed        $(date -u +%Y-%m-%dT%H:%M:%SZ)
EOF

echo
echo "== Verification"
"$JAVA_HOME/bin/java" -version 2>&1 | head -1
"$SDK/platform-tools/adb" version | head -2
"${ANDROID_CLI[@]}" sdk list 2>/dev/null | grep -E "^\s+(platform-tools|platforms|build-tools|cmdline-tools)"
GRADLE_USER_HOME="$TOOLING/gradle-home" "$TOOLING/gradle/bin/gradle" --version --quiet | grep -E "^(Gradle|Launcher JVM|Daemon JVM)" || true
echo
echo "Done. Run 'source scripts/env.sh' to use this toolchain in a shell."
