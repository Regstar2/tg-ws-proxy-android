# Android ABI builds and local acceptance

Production APKs support Android 8.0+ (API 26), ARMv7 (`armeabi-v7a`) and ARM64
(`arm64-v8a`). x86 and x86_64 are not shipped. Universal contains both ARM ABIs.
All variants use the existing application ID and the same versionCode, allowing
switching between compatible APK variants without an ABI-specific version jump.

## Build on Windows

Use JDK 17, the repository Gradle Wrapper, Go, Android SDK 35 and an installed NDK.
`ANDROID_NDK_ROOT` explicitly selects an NDK; otherwise the highest installed
version under `ANDROID_SDK_ROOT/ndk` is used. API 26 is the native build default.

```powershell
Set-Location C:\Base\projects\TGWSProxyAndroid
.\scripts\build-apk.ps1 -Configuration Debug
# Existing configured signing key required; creates no new production key:
.\scripts\release.ps1 -Version v1.11.10
```

In v1.11.10, CF Proxy defaults to plaintext WS/80 if no preference is saved; persisted false selects verified WSS/443. This is a transport default, not an ABI change. For WSS/443, turn off CF Proxy without TLS.

Signed in-place debug smoke: scripts/build-signed-worker-smoke.ps1 -Install. This requires the original release keystore in local release-signing.env; signed Debug uses versionCode 66, ordinary debug uses 67 and a different signer. Do not uninstall existing data to work around a signing conflict.

Gradle preBuild compiles both Go/CGO shared libraries. The native script verifies
ELF32/ARM and ELF64/AArch64 with NDK llvm-readelf, then copies them into jniLibs
only after both succeed. Requested stale libraries are invalidated before build.
`GOARM=7` applies only to ARMv7. The script restores the caller's Go/CGO environment.

Debug outputs: `app/build/outputs/apk/debug/app-{armeabi-v7a,arm64-v8a,universal}-debug.apk`.
Release outputs use the same suffixes under `release/`. `build-apk.ps1` copies all
three into `artifacts/apk/`; the historical `tgwsproxy-debug.apk` alias is Universal.
`release.ps1` validates metadata/signatures and writes three versioned APKs plus
three `.sha256` sidecars to `dist/`. It preserves unrelated local files in dist.
Publish only the current version's six files from a clean release workspace.

`scripts/audit-apk-abis.py APK VARIANT` rejects unexpected libraries, missing
`libjnidispatch.so`/`libtgwsproxy.so`, duplicate ZIP entries and mismatched ELF
class/machine. Release workflow changes are source changes only; running a local
release build does not publish anything.

## Installation and updates

Use an ABI-specific APK when its ABI appears in `adb -s SERIAL shell getprop
ro.product.cpu.abilist`, or Universal on either supported ARM system. CPU hardware
alone does not establish what the installed Android OS supports. A 64-bit CPU can
run a 32-bit-only OS. Preserve user data when changing APK variant; the signing
certificate and application ID must match. Never uninstall or clear data merely
to bypass a signature mismatch.

The updater follows `Build.SUPPORTED_ABIS` order, tries valid official ABI-specific
assets, then Universal. Unsupported devices get no installable asset. It retains
official URL validation, SHA-256, APK signature, installed signer, package name,
strictly newer versionCode and minSdk checks. Before installation it validates
the actual APK native payload and ELF headers, including JNA for every ABI.

## Safe runtime tests

Use a separate `.abismoke` Debug package; it has independent data and does not
replace the production installation. Always use explicit ADB serials.

```powershell
Set-Location C:\Base\projects\TGWSProxyAndroid
.\scripts\setup-issue-162-emulator.ps1
.\scripts\issue-162-armv7-smoke.ps1 -Build
# Physical device, actual 32-bit process, including Universal:
.\scripts\issue-162-armv7-smoke.ps1 -Serial YOUR_DEVICE_SERIAL -Abi armeabi-v7a -Variant universal
.\scripts\issue-162-armv7-smoke.ps1 -Serial YOUR_DEVICE_SERIAL -Abi arm64-v8a -Variant arm64-v8a
.\scripts\issue-162-native-tests.ps1 -Serial YOUR_DEVICE_SERIAL -Abi armeabi-v7a
```

Replace the example physical serial with the verified intended device. The smoke
runner checks package identity and ABI, waits for boot, installs without deleting
data, calls instrumentation with a timeout, and writes logs/results to artifacts.
It verifies actual process/JNA bitness, Go calls, local SOCKS5 echo, listener
stop/restart, Activity and foreground service lifecycle. It does not establish
end-to-end Telegram transport operation. Emulator ARM translation may fail even
when actual ARM hardware works; consult the [recorded results](../testing/issue-162-armv7-results.md).
