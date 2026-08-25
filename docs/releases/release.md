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

For `v1.10.13`, the expected release metadata is `versionName 1.10.13` / `versionCode 51`.

## Build debug APK for device acceptance

```powershell
.\scripts\ci.ps1
```

The final CI stage builds:

```text
app\build\outputs\apk\debug\app-debug.apk
```

For `v1.10.13` source, the debug variant intentionally uses `versionName 1.10.13-debug` and `versionCode 52`, one code above release, so it can be installed over the latest release during pre-release testing.

## Build final signed release artifacts

Release signing stays local and outside Git. Configure the keystore through `release-signing.env` or process environment variables:

```powershell
$env:KEYSTORE_FILE = "C:\path\to\tgwsproxy-release.jks"
$env:KEYSTORE_PASSWORD = "..."
$env:KEY_PASSWORD = "..."
$env:KEY_ALIAS = "tgwsproxy"

.\scripts\release.ps1 -Version v1.10.13
```

The canonical release script:

1. validates the SemVer-like tag;
2. requires the tag to match `releaseVersionName`;
3. requires local signing variables and keystore;
4. builds the release APK through `scripts/build-apk.ps1`;
5. verifies the APK signature with `apksigner`;
6. writes exactly two files to `dist/`:
   - `TgWsProxy-Android-v1.10.13-arm64-v8a.apk`;
   - `TgWsProxy-Android-v1.10.13-arm64-v8a.apk.sha256`.

Do not publish if signature verification or any preceding check fails.

## Native library

Release/debug builds run `scripts/build-native-android.ps1` via Gradle `preBuild` and package the ARM64 `libtgwsproxy.so`.

## Install on device

Debug acceptance build:

```powershell
adb install -r app\build\outputs\apk\debug\app-debug.apk
```

Signed release acceptance build:

```powershell
adb install -r dist\TgWsProxy-Android-v1.10.13-arm64-v8a.apk
```

Switching between debug and release signatures can be rejected by Android. Do not uninstall an existing app blindly: uninstalling may remove local settings. Confirm signature/data implications first.

## Publication

The owner-controlled release workflow is triggered only after final Issue #7 acceptance. It checks out the exact `v*` tag on the trusted self-hosted Windows/X64 runner, reruns project CI, calls the release script, and publishes the verified APK and SHA-256 only when an existing release with that tag is absent.

Per-version notes: `RELEASE_NOTES_vX.Y.Z.md` in this directory.
