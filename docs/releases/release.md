# Release workflow

Short release workflow for TGWSProxyAndroid. Detailed checklist: [RELEASE_CHECKLIST.md](RELEASE_CHECKLIST.md).

## Before release

- [ ] Confirm `releaseVersionName` / `releaseVersionCode` in `app/build.gradle.kts`.
- [ ] Run `.\scripts\ci.ps1` and require the embedded `audit-release.ps1` to pass.
- [ ] Run [docs/testing/README.md](../testing/README.md) on a real device on mobile data and Wi-Fi.
- [ ] Verify Telegram messages/media, reconnect and Wi-Fi ↔ mobile reconfigure.
- [ ] Verify Feedback and Updates in RU/EN.
- [ ] Update [CHANGELOG.md](../../CHANGELOG.md), README RU/EN and per-version release notes.
- [ ] Confirm no keystores, passwords, `local.properties`, private governance files, local logs, or environment files are tracked.
- [ ] Review the release branch diff for unrelated changes.

For `v1.10.14`, the expected release metadata is `versionName 1.10.14` / `versionCode 52`.

## Build debug APK for device acceptance

```powershell
.\scripts\ci.ps1
```

The final CI stage builds:

```text
app\build\outputs\apk\debug\app-debug.apk
```

For `v1.10.14` source, the debug variant uses `versionName 1.10.14-debug`. The release variant uses `versionName 1.10.14` / `versionCode 52`.

## Build final signed release artifacts locally

Local release signing remains supported through `release-signing.env` or process environment variables:

```powershell
$env:KEYSTORE_FILE = "C:\path\to\tgwsproxy-release.jks"
$env:KEYSTORE_PASSWORD = "..."
$env:KEY_PASSWORD = "..."
$env:KEY_ALIAS = "tgwsproxy"

.\scripts\release.ps1 -Version v1.10.14
```

The canonical release script:

1. validates the SemVer-like tag;
2. requires the tag to match `releaseVersionName`;
3. requires signing variables and the release keystore;
4. builds the release APK through `scripts/build-apk.ps1`;
5. verifies APK package/version metadata and the signature with `aapt`/`apksigner`;
6. writes exactly two files to `dist/`:
   - `TgWsProxy-Android-v1.10.14-arm64-v8a.apk`;
   - `TgWsProxy-Android-v1.10.14-arm64-v8a.apk.sha256`.

Do not publish if signature verification or any preceding check fails.

## GitHub-hosted release signing

The release workflow runs on GitHub-hosted `windows-latest`; a personal self-hosted runner is not required.

Repository Actions secrets must contain the existing release signing material:

```text
RELEASE_KEYSTORE_BASE64
RELEASE_KEYSTORE_PASSWORD
RELEASE_KEY_PASSWORD
RELEASE_KEY_ALIAS
```

`RELEASE_KEYSTORE_BASE64` is the existing binary release keystore encoded as Base64. The workflow decodes it only into the hosted runner temporary directory, exposes its temporary path as `KEYSTORE_FILE`, runs `scripts/release.ps1`, and relies on that script to verify the resulting APK signature. `RELEASE_KEY_ALIAS` may be omitted when the keystore uses the default alias `tgwsproxy`.

The keystore and passwords must never be committed to Git or written to release artifacts/logs.

## Native library

Release/debug builds run `scripts/build-native-android.ps1` via Gradle `preBuild` and package the ARM64 `libtgwsproxy.so`.

The full root Go/race suite is covered by the Linux Worker transport CI job. On Windows, `scripts/ci.ps1` runs portable Go package tests and the Android Gradle build compiles the actual cgo runtime with the Android NDK toolchain. This avoids depending on an arbitrary host `gcc.exe` for the Android c-shared entry point.

## Install on device

Debug acceptance build:

```powershell
adb install -r app\build\outputs\apk\debug\app-debug.apk
```

Signed release acceptance build:

```powershell
adb install -r dist\TgWsProxy-Android-v1.10.14-arm64-v8a.apk
```

Switching between debug and release signatures can be rejected by Android. Do not uninstall an existing app blindly: uninstalling may remove local settings. Confirm signature/data implications first.

## Publication

After final acceptance, create/push the exact `v1.10.14` tag. The owner-controlled release workflow checks out that tag on GitHub-hosted Windows, reruns project CI, restores the release keystore from Actions secrets, calls the canonical release script, verifies the APK and SHA-256 outputs, and publishes them only when a GitHub Release with that tag does not already exist.

Per-version notes: `RELEASE_NOTES_vX.Y.Z.md` in this directory.
