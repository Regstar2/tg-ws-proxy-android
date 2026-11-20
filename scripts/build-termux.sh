#!/data/data/com.termux/files/usr/bin/bash
set -Eeuo pipefail
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
PREFIX="${PREFIX:-/data/data/com.termux/files/usr}"
die(){ printf 'ERROR: %s\n' "$*" >&2; exit 1; }
[[ -n "${TERMUX_VERSION:-}" || "$PREFIX" == *"/com.termux/files/usr" ]] || die "Run from Termux."
[[ "$(uname -m)" == aarch64 ]] || die "Only ARM64 Termux is supported."
SDK_ROOT="${ANDROID_SDK_ROOT:-${ANDROID_HOME:-}}"
if [[ -z "$SDK_ROOT" || ! -f "$SDK_ROOT/platforms/android-35/android.jar" ]]; then
  SDK_ROOT=""
  for candidate in "$HOME/opt/tgwsproxy-toolchain/android-sdk" "$HOME/opt/android-sdk" "$PREFIX/opt/android-sdk" "$HOME/android-sdk"; do
    [[ -f "$candidate/platforms/android-35/android.jar" ]] && SDK_ROOT="$candidate" && break
  done
fi
[[ -n "$SDK_ROOT" ]] || die "Android SDK Platform 35 not found; set ANDROID_SDK_ROOT."
NDK_ROOT="${ANDROID_NDK_ROOT:-${NDK_HOME:-}}"
if [[ -z "$NDK_ROOT" || ! -x "$NDK_ROOT/toolchains/llvm/prebuilt/linux-aarch64/bin/llvm-readelf" ]]; then
  NDK_ROOT=""
  for candidate in "$HOME/opt/tgwsproxy-toolchain/android-ndk-r29" "$HOME"/opt/tgwsproxy-toolchain/android-ndk-r* "$HOME"/opt/android-ndk-r* "$SDK_ROOT"/ndk/*; do
    [[ -x "$candidate/toolchains/llvm/prebuilt/linux-aarch64/bin/llvm-readelf" ]] && NDK_ROOT="$candidate"
  done
fi
[[ -n "$NDK_ROOT" ]] || die "Termux NDK linux-aarch64 host tools not found; set ANDROID_NDK_ROOT."
export ANDROID_SDK_ROOT="$SDK_ROOT" ANDROID_HOME="$SDK_ROOT" ANDROID_NDK_ROOT="$NDK_ROOT" NDK_HOME="$NDK_ROOT"
if [[ -z "${JAVA_HOME:-}" || ! -x "$JAVA_HOME/bin/java" ]]; then
  for candidate in "$PREFIX/lib/jvm/java-17-openjdk" "$PREFIX/opt/openjdk-17"; do
    [[ -x "$candidate/bin/java" ]] && JAVA_HOME="$candidate" && break
  done
fi
[[ -x "${JAVA_HOME:-/nonexistent}/bin/java" ]] || die "JDK 17 not found; install openjdk-17 and set JAVA_HOME."
export JAVA_HOME
JAVA_VERSION="$("$JAVA_HOME/bin/java" -version 2>&1 | awk -F'"' '/version/ {print $2; exit}')"
[[ "${JAVA_VERSION%%.*}" == 17 ]] || die "JDK 17 required; found ${JAVA_VERSION:-unknown}."
GO_BIN="$(command -v go || true)"; [[ -n "$GO_BIN" ]] || die "Go is missing; install golang."
GO_VERSION="$("$GO_BIN" env GOVERSION)"; GO_VERSION="${GO_VERSION#go}"
[[ "$(printf '%s\n' "$GO_VERSION" 1.25.0 | sort -V | head -n1)" == 1.25.0 ]] || die "Go 1.25.0+ required; found $GO_VERSION."
PYTHON_BIN="$(command -v python3 || true)"; [[ -n "$PYTHON_BIN" ]] || die "Python 3 is missing."
"$PYTHON_BIN" -c 'from PIL import Image' >/dev/null 2>&1 || die "Pillow missing; install python-pillow."
BUILD_TOOLS_DIR=""
if [[ -n "${ANDROID_BUILD_TOOLS_VERSION:-}" ]]; then
  BUILD_TOOLS_DIR="$SDK_ROOT/build-tools/$ANDROID_BUILD_TOOLS_VERSION"
  [[ -x "$BUILD_TOOLS_DIR/aapt2" && -x "$BUILD_TOOLS_DIR/zipalign" ]] || die "Selected build-tools need executable aapt2 and zipalign."
elif [[ -x "$SDK_ROOT/build-tools/35.0.0/aapt2" && -x "$SDK_ROOT/build-tools/35.0.0/zipalign" ]]; then
  BUILD_TOOLS_DIR="$SDK_ROOT/build-tools/35.0.0"
else
  while IFS= read -r candidate; do
    [[ -x "$candidate/aapt2" && -x "$candidate/zipalign" ]] && BUILD_TOOLS_DIR="$candidate"
  done < <(find "$SDK_ROOT/build-tools" -mindepth 1 -maxdepth 1 -type d 2>/dev/null | sort -V)
fi
[[ -n "$BUILD_TOOLS_DIR" ]] || die "No ARM64 build-tools with aapt2 and zipalign found."
AAPT2="$BUILD_TOOLS_DIR/aapt2"
"$AAPT2" version >/dev/null 2>&1 || die "$AAPT2 cannot run on Termux; install Android ARM64/Bionic build-tools."
export ANDROID_BUILD_TOOLS_VERSION="${BUILD_TOOLS_DIR##*/}"
export PATH="$BUILD_TOOLS_DIR:$SDK_ROOT/platform-tools:$PREFIX/bin:$PATH"
"$PYTHON_BIN" - "$ROOT/local.properties" "$SDK_ROOT" <<'PY'
from pathlib import Path
import sys
path = Path(sys.argv[1])
sdk = sys.argv[2].replace("\\", "\\\\").replace(" ", "\\ ")
lines = path.read_text(encoding="utf-8").splitlines() if path.exists() else []
for i, line in enumerate(lines):
    if line.startswith("sdk.dir="):
        lines[i] = f"sdk.dir={sdk}"
        break
else:
    lines.append(f"sdk.dir={sdk}")
path.write_text("\n".join(lines) + "\n", encoding="utf-8")
PY
printf 'SDK: %s\nNDK: %s\nJDK: %s (%s)\nGo: %s\nAAPT2: %s\n' "$SDK_ROOT" "$NDK_ROOT" "$JAVA_HOME" "$JAVA_VERSION" "$GO_VERSION" "$AAPT2"
cd "$ROOT"
bash "$ROOT/gradlew" --no-daemon --max-workers="${TGWS_GRADLE_WORKERS:-2}" "-Pandroid.aapt2FromMavenOverride=$AAPT2" :app:assembleDebug
APK_DIR="$ROOT/app/build/outputs/apk/debug"
ARTIFACT_DIR="$ROOT/artifacts/termux"
mkdir -p "$ARTIFACT_DIR"
for abi in armeabi-v7a arm64-v8a universal; do
  apk="$APK_DIR/app-$abi-debug.apk"
  [[ -s "$apk" ]] || die "Expected APK was not produced: $apk"
  "$PYTHON_BIN" "$ROOT/scripts/audit-apk-abis.py" "$apk" "$abi"
  dest="$ARTIFACT_DIR/tgwsproxy-termux-debug-$abi.apk"
  cp -f "$apk" "$dest"
  sha256sum "$dest" > "$dest.sha256"
done
printf '\nTermux Debug APKs verified and copied to %s\n' "$ARTIFACT_DIR"
