# Point the current shell at this project's private toolchain in .tooling/.
# Usage (bash or zsh): source scripts/env.sh

_mn_script="${BASH_SOURCE[0]:-${(%):-%x}}"
MN_ROOT="$(cd "$(dirname "$_mn_script")/.." && pwd)"
unset _mn_script

export JAVA_HOME="$MN_ROOT/.tooling/jdk/Contents/Home"
export ANDROID_HOME="$MN_ROOT/.tooling/android-sdk"
export ANDROID_SDK_ROOT="$ANDROID_HOME"
export ANDROID_USER_HOME="$MN_ROOT/.tooling/android-user"
export GRADLE_USER_HOME="$MN_ROOT/.tooling/gradle-home"
# ONNX Runtime on the Mac (unit tests, parity reference) otherwise sends usage telemetry to Microsoft.
export ORT_DISABLE_TELEMETRY=1
# sdkmanager runs $ANDROID_CLI_BIN instead of the bundled Android CLI when it is set.
export ANDROID_CLI_BIN="$MN_ROOT/scripts/bin/android"
export PATH="$MN_ROOT/scripts/bin:$JAVA_HOME/bin:$ANDROID_HOME/platform-tools:$ANDROID_HOME/cmdline-tools/latest/bin:$MN_ROOT/.tooling/gradle/bin:$PATH"
