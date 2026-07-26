# Release checklist

Short release workflow for TGWSProxyAndroid. Detailed checklist: [RELEASE_CHECKLIST.md](RELEASE_CHECKLIST.md).

## Before release

- [ ] Bump `versionCode` and `versionName` in `app/build.gradle.kts`
- [ ] Run [docs/testing/README.md](../testing/README.md) on a real device (mobile + Wi‑Fi)
- [ ] Update [CHANGELOG.md](../../CHANGELOG.md)
- [ ] Update [README.md](../../README.md) if UI or behavior changed
- [ ] Update screenshots in `docs/assets/screenshots/` if UI changed
- [ ] Add `docs/releases/RELEASE_NOTES_vX.Y.Z.md` if the release is feature-heavy
- [ ] Confirm no keystores, passwords, or `local.properties` are committed

## Build debug APK

```powershell
.\gradlew.bat assembleDebug
```

Output: `app\build\outputs\apk\debug\app-debug.apk`

Optional copy:

```powershell
.\scripts\build-apk.ps1 -Configuration Debug
```

→ `artifacts\apk\debug\tgwsproxy-debug.apk`

## Build release APK

Release signing uses **local** keystore and environment variables (not in git):

```powershell
$env:KEYSTORE_FILE = "C:\path\to\tgwsproxy-release.jks"   # or repo-root file
$env:KEYSTORE_PASSWORD = "..."
$env:KEY_PASSWORD = "..."
$env:KEY_ALIAS = "tgwsproxy"

.\scripts\build-apk.ps1 -Configuration Release
```

Gradle output: `app\build\outputs\apk\release\app-release.apk`  
Script copy: `artifacts\apk\release\tgwsproxy-release.apk`

## Native library

Release/debug builds run `scripts/build-native-android.ps1` via Gradle `preBuild` (ARM64 `libtgwsproxy.so`).

## Artifacts (gitignored)

| Path | Content |
|------|---------|
| `app/build/` | Gradle intermediates and APKs |
| `artifacts/apk/` | Copied APKs from `build-apk.ps1` |
| `artifacts/native/` | Built `.so` |
| `app/src/main/jniLibs/` | Copied `.so` for packaging |

## Install on device

```powershell
adb install -r artifacts\apk\release\tgwsproxy-release.apk
```

Use `adb install -r` after uninstall if signatures differ (debug vs release).

## Historical release notes

Per-version notes: `RELEASE_NOTES_vX.Y.Z.md` in this directory.
