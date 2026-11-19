# Technical stack

## Android application

| Area | Technology |
|---|---|
| Language | Kotlin `1.9.22` |
| UI | Jetpack Compose + Material / Material 3 |
| Android Gradle Plugin | `8.2.2` |
| Gradle Wrapper | `8.2.1` |
| Compose BOM | `2024.02.00` |
| Compose compiler extension | `1.5.8` |
| AndroidX Core | `1.12.0` |
| Lifecycle | `2.7.0` |
| Activity Compose | `1.8.2` |
| Native bridge | JNA `5.14.0` + CGO-exported Go runtime |
| Unit tests | JUnit `4.13.2` |

Android configuration:

- `compileSdk 35`;
- `targetSdk 35`;
- `minSdk 26` (Android 8.0+);
- supported ABIs: `armeabi-v7a`, `arm64-v8a` (plus Universal APK);
- application id: `io.github.regstar2.tgwsproxy` (Kotlin namespace: `com.amurcanov.tgwsproxy`).

Latest published release: v1.11.9 / versionCode 65. Current source metadata: v1.11.10 / versionCode 66 (unpublished candidate; signed Release acceptance pending).

## Native proxy runtime

The proxy runtime is implemented in Go under `native/tgwsproxy/`.

The Android build produces:

```text
app/src/main/jniLibs/{armeabi-v7a,arm64-v8a}/libtgwsproxy.so
```

The Gradle `preBuild` task invokes `scripts/build-native-android.ps1`, then the library is packaged into the APK and accessed from Kotlin through JNA/CGO bridge functions.

The runtime contains the local proxy frontend, route selection and Telegram/Cloudflare transport logic. Android lifecycle, settings, foreground service and UI remain in the Kotlin layer.

## Runtime route model

The current implementation distinguishes the user-facing/local frontend from the outbound route kind.

Local frontends:

- MTProto Proxy;
- SOCKS5/WebSocket compatibility frontend.

Outbound route kinds:

- `cf_proxy_ws`;
- `direct_ws`;
- `cf_worker_ws`;
- `awg_warp` (userspace AWG/WARP, no VpnService);
- `tcp_fallback`.

WebSocket is a transport, not a route kind. CF Proxy defaults to verified WSS/443; opt-in WS/80 is plaintext and unauthenticated. These choices share the cf_proxy_ws route.

## Build toolchain

Supported/documented build host: Windows.

Required tools:

- PowerShell;
- JDK 17 for the Android/Gradle toolchain;
- Android SDK;
- Android NDK;
- Go;
- Python 3;
- Gradle Wrapper from the repository.

Primary commands:

```powershell
.\gradlew.bat testDebugUnitTest
.\gradlew.bat assembleDebug
.\scripts\build-apk.ps1 -Configuration Debug

Push-Location native\tgwsproxy
go test ./...
Pop-Location
```

The repository does not currently claim a verified Linux/macOS native build workflow.

## Release signing

Release signing uses a local keystore and environment variables. The original certificate can also sign a Debug device smoke via scripts/build-signed-worker-smoke.ps1 -Install. Official release artifacts contain three ABI variants plus SHA-256 files. Keys and credentials are never repository content.

Expected variables:

- `KEYSTORE_FILE`;
- `KEYSTORE_PASSWORD`;
- `KEY_PASSWORD`;
- `KEY_ALIAS`.

Keystores, private keys, local signing environment files and secret directories are excluded through `.gitignore`.

## Localization

Android resources include the default `values/` resources and Russian `values-ru/`. Public releases must keep RU/EN user-facing strings aligned; hardcoded user-visible strings should be avoided.

## Documentation boundaries

Public project documentation belongs under `docs/product/`, `docs/architecture/`, `docs/testing/`, `docs/releases/`, `docs/versions/` and other explicitly public folders.

Local governance and AI working files such as `AGENTS.md`, `.project-rules/`, `docs/ai-prompts/`, `docs/private/`, `.codex/`, `.cursor/`, `.claude/` and `.ai/` are intentionally excluded from Git.
