#!/data/data/com.termux/files/usr/bin/bash
set -euo pipefail

API_LEVEL="${ANDROID_API_LEVEL:-26}"
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
SOURCE_DIR="$ROOT/native/tgwsproxy"
NDK_ROOT="${ANDROID_NDK_ROOT:-${ANDROID_NDK_HOME:-}}"

if [ -z "$NDK_ROOT" ] && [ -f "$ROOT/local.properties" ]; then
    NDK_ROOT="$(sed -n 's/^ndk\.dir=//p' "$ROOT/local.properties" | head -n 1)"
fi

if [ -z "$NDK_ROOT" ] || [ ! -d "$NDK_ROOT/toolchains/llvm/prebuilt" ]; then
    echo "ERROR: Android NDK not found"
    exit 1
fi

PREBUILT="$NDK_ROOT/toolchains/llvm/prebuilt"
HOST_TAG="${ANDROID_NDK_HOST_TAG:-linux-aarch64}"
if [ ! -d "$PREBUILT/$HOST_TAG" ]; then
    HOST_TAG="$(basename "$(find "$PREBUILT" -mindepth 1 -maxdepth 1 -type d -name 'linux-*' -print -quit)")"
fi
NDK_BIN="$PREBUILT/$HOST_TAG/bin"
if [ ! -d "$NDK_BIN" ]; then
    echo "ERROR: NDK compiler directory not found under $PREBUILT"
    exit 1
fi

if [ ! -d "$SOURCE_DIR" ]; then
    echo "ERROR: Go source directory not found: $SOURCE_DIR"
    exit 1
fi

WRAPPER_DIR="$(mktemp -d "${TMPDIR:-/tmp}/tgwsproxy-cc.XXXXXX")"
trap 'rm -rf "$WRAPPER_DIR"' EXIT

make_wrapper() {
    local compiler="$1"
    local wrapper="$2"
    printf '#!%s/bin/bash\nexec "%s/bin/bash" "%s" "$@"\n' "$PREFIX" "$PREFIX" "$compiler" > "$wrapper"
    chmod 700 "$wrapper"
}

build_target() {
    local abi="$1"
    local goarch="$2"
    local triple="$3"
    local goarm="${4:-}"
    local cc_real="$NDK_BIN/${triple}${API_LEVEL}-clang"
    local cxx_real="$NDK_BIN/${triple}${API_LEVEL}-clang++"
    local cc_wrapper="$WRAPPER_DIR/${abi}-clang"
    local cxx_wrapper="$WRAPPER_DIR/${abi}-clang++"
    local artifact_dir="$ROOT/artifacts/native/$abi"
    local jni_dir="$ROOT/app/src/main/jniLibs/$abi"
    local output="$artifact_dir/libtgwsproxy.so"

    if [ ! -f "$cc_real" ] || [ ! -f "$cxx_real" ]; then
        echo "ERROR: compiler missing for $abi: $cc_real"
        return 1
    fi

    make_wrapper "$cc_real" "$cc_wrapper"
    make_wrapper "$cxx_real" "$cxx_wrapper"
    mkdir -p "$artifact_dir" "$jni_dir"

    echo "==> Building $abi (GOARCH=$goarch, API=$API_LEVEL)"
    if [ "$goarch" = "arm" ]; then
        (cd "$SOURCE_DIR" && env GOOS=android GOARCH="$goarch" GOARM="$goarm" CGO_ENABLED=1 CC="$cc_wrapper" CXX="$cxx_wrapper" go build -buildmode=c-shared -trimpath -o "$output" .)
    else
        (cd "$SOURCE_DIR" && env GOOS=android GOARCH="$goarch" CGO_ENABLED=1 CC="$cc_wrapper" CXX="$cxx_wrapper" go build -buildmode=c-shared -trimpath -o "$output" .)
    fi

    if [ ! -s "$output" ]; then
        echo "ERROR: Native library was not created for $abi"
        return 1
    fi

    cp -f "$output" "$jni_dir/libtgwsproxy.so"
    ls -lh "$output" "$jni_dir/libtgwsproxy.so"
}

echo "========== GO NATIVE ANDROID BUILD =========="
echo "NDK: $NDK_ROOT"
echo "Host toolchain: $HOST_TAG"
echo "API: $API_LEVEL"

build_target "arm64-v8a" "arm64" "aarch64-linux-android"
build_target "armeabi-v7a" "arm" "armv7a-linux-androideabi" "7"
build_target "x86" "386" "i686-linux-android"
build_target "x86_64" "amd64" "x86_64-linux-android"

echo "========== ALL NATIVE ABIS BUILT SUCCESSFULLY =========="
