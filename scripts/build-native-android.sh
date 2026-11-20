#!/data/data/com.termux/files/usr/bin/bash
set -Eeuo pipefail
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
API_LEVEL="${ANDROID_API_LEVEL:-26}"
GO_BIN="$(command -v go || true)"
die(){ printf 'ERROR: %s\n' "$*" >&2; exit 1; }
[[ -n "${TERMUX_VERSION:-}" || "${PREFIX:-}" == *"/com.termux/files/usr" ]] || die "Run from Termux."
[[ "$(uname -m)" == aarch64 ]] || die "Termux native build requires ARM64."
BASH_BIN="$(command -v bash || true)"
[[ -n "$BASH_BIN" && -x "$BASH_BIN" ]] || die "Termux Bash is required to invoke NDK compiler wrappers."
[[ "$API_LEVEL" =~ ^[0-9]+$ ]] && (( API_LEVEL >= 26 && API_LEVEL <= 35 )) || die "ANDROID_API_LEVEL must be 26..35."
[[ -n "$GO_BIN" ]] || die "Go 1.25.0+ is required."
GO_VERSION="$("$GO_BIN" env GOVERSION)"; GO_VERSION="${GO_VERSION#go}"
[[ "$(printf '%s\n' "$GO_VERSION" 1.25.0 | sort -V | head -n1)" == 1.25.0 ]] || die "Go 1.25.0+ required; found $GO_VERSION."
NDK_ROOT="${ANDROID_NDK_ROOT:-${NDK_HOME:-}}"
if [[ -z "$NDK_ROOT" ]]; then
  for candidate in "$HOME"/opt/tgwsproxy-toolchain/android-ndk-r* "$HOME"/opt/android-ndk-r* "${ANDROID_SDK_ROOT:-${ANDROID_HOME:-/nonexistent}}"/ndk/*; do
    [[ -x "$candidate/toolchains/llvm/prebuilt/linux-aarch64/bin/llvm-readelf" ]] && NDK_ROOT="$candidate"
  done
fi
[[ -n "$NDK_ROOT" ]] || die "Set ANDROID_NDK_ROOT to the Termux NDK r29 directory."
TOOLCHAIN="$NDK_ROOT/toolchains/llvm/prebuilt/linux-aarch64/bin"
READELF="$TOOLCHAIN/llvm-readelf"
[[ -x "$READELF" ]] || die "linux-aarch64 llvm-readelf missing in $TOOLCHAIN."
rm -f "$ROOT/app/src/main/jniLibs/armeabi-v7a/libtgwsproxy.so" "$ROOT/app/src/main/jniLibs/arm64-v8a/libtgwsproxy.so"
for abi in armeabi-v7a arm64-v8a; do
  case "$abi" in
    armeabi-v7a) arch=arm; compiler="$TOOLCHAIN/armv7a-linux-androideabi${API_LEVEL}-clang"; elf_class=ELF32; machine=ARM ;;
    arm64-v8a) arch=arm64; compiler="$TOOLCHAIN/aarch64-linux-android${API_LEVEL}-clang"; elf_class=ELF64; machine=AArch64 ;;
  esac
  [[ -x "$compiler" ]] || die "NDK compiler missing: $compiler"
  out="$ROOT/artifacts/native/$abi/libtgwsproxy.so"
  mkdir -p "$(dirname "$out")"; rm -f "$out" "${out%.so}.h"
  printf '\n==> Go Android build: %s\n' "$abi"
  (
    cd "$ROOT/native/tgwsproxy"
    export GOOS=android GOARCH="$arch" CGO_ENABLED=1
    # Go/cgo starts CC with execve; run NDK shell wrappers through Termux Bash
    # so Linux-host shebangs such as /usr/bin/env are not required.
    export CC="$BASH_BIN $compiler"
    if [[ "$abi" == armeabi-v7a ]]; then export GOARM=7; else unset GOARM || true; fi
    "$GO_BIN" build -buildmode=c-shared -trimpath -o "$out" .
  )
  [[ -s "$out" ]] || die "Missing output: $out"
  header="$(LC_ALL=C "$READELF" -h "$out")" || die "llvm-readelf failed for $out"
  grep -Eq "Class:[[:space:]]+$elf_class([[:space:]]|$)" <<<"$header" || die "Wrong ELF class for $abi"
  grep -Eq "Machine:[[:space:]]+$machine([[:space:]]|$)" <<<"$header" || die "Wrong ELF machine for $abi"
  printf 'Verified %s: %s / %s\n' "$abi" "$elf_class" "$machine"
done
for abi in armeabi-v7a arm64-v8a; do
  mkdir -p "$ROOT/app/src/main/jniLibs/$abi"
  cp -f "$ROOT/artifacts/native/$abi/libtgwsproxy.so" "$ROOT/app/src/main/jniLibs/$abi/libtgwsproxy.so"
done
printf '\nNative Go libraries built and ELF-verified for ARMv7 and ARM64.\n'
