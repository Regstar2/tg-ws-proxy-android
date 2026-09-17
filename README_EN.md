<div align="center">

<img src="icon.png" width="128" alt="TgWsProxy application icon">

# TgWsProxy Android

A local Telegram proxy for Android with MTProto and SOCKS5 frontends and routing through Cloudflare Proxy, direct WebSocket, Cloudflare Worker, userspace AWG/WARP, or TCP.

[Русский](README.md) · **English**

[![Version](https://img.shields.io/badge/source-1.11.0--beta.1-0969DA?style=for-the-badge)](CHANGELOG.md)
[![Android](https://img.shields.io/badge/Android-8.0%2B-3DDC84?style=for-the-badge&logo=android&logoColor=white)](app/build.gradle.kts)
[![ABI](https://img.shields.io/badge/ABI-arm64--v8a-7B61FF?style=for-the-badge)](app/build.gradle.kts)
[![Documentation](https://img.shields.io/badge/docs-open-4C8BF5?style=for-the-badge&logo=readthedocs&logoColor=white)](#documentation)
[![License](https://img.shields.io/github/license/Regstar2/tg-ws-proxy-android?style=for-the-badge&label=license)](LICENSE)

[🌐 Project website](https://regstar2.github.io/projects/tg-ws-proxy-android/) · [Русская версия](https://regstar2.github.io/projects/tg-ws-proxy-android/ru/)

[Quick start](#quick-start) ·
[Documentation](#documentation) ·
[Releases](https://github.com/Regstar2/tg-ws-proxy-android/releases) ·
[Report an issue](https://github.com/Regstar2/tg-ws-proxy-android/issues)

</div>

---

## About

TgWsProxy runs a local proxy on an Android device. Telegram connects through the MTProto Proxy frontend or the compatible SOCKS5 mode, and the native runtime selects an allowed route to Telegram infrastructure.

The primary use case remains **MTProto Proxy → Cloudflare Proxy** on `127.0.0.1:1443`. Version `1.11.0-beta.1` additionally introduces the optional userspace `awg_warp` route over AmneziaWG/WARP. The application does not create a system VPN tunnel or route all device traffic.

## Project status

**Source version:** `1.11.0-beta.1` (`versionCode 53`)  
**Stage:** beta candidate; final signed-APK install/update and device smoke are required before tagging

| Area | Status |
|---|---|
| MTProto Proxy over `cf_proxy_ws` | Primary use case; manually verified on mobile data and Wi-Fi |
| `awg_warp` | Beta: userspace AWG/WARP without `VpnService`; automatic provisioning and Telegram MTProto/media verified on Android 14 |
| SOCKS5 / WebSocket frontend | Implemented as a compatibility mode |
| `direct_ws` and `tcp_fallback` | Implemented; availability depends on the network |
| Cloudflare Worker | Optional route and bootstrap for Consumer WARP provisioning when the direct API is unavailable |
| Worker Pool | Implemented but still slower than direct connectivity and not recommended as the primary route |
| Feedback | Dedicated screen; GitHub Issue Forms without an embedded PAT |
| Updates | Official GitHub Releases check with SemVer and official release-page navigation |

## Features

- local MTProto Proxy with `t.me/proxy` and `tg://proxy` link generation;
- compatible SOCKS5 frontend on the same configurable port;
- `cf_proxy_ws`, `direct_ws`, `cf_worker_ws`, `awg_warp`, and `tcp_fallback` routes;
- in-app WARP/AWG profile management: automatic Consumer WARP provisioning, `.conf` import, selection, validation, and deletion;
- userspace AmneziaWG/WARP routing without root, Android `VpnService`, or a system TUN interface;
- separate route policies for Wi-Fi, mobile data, and unknown networks;
- Fake TLS secrets in `dd<secret>` and `ee<secret><domain_hex>` formats;
- optional probe passthrough to a configured masking domain;
- foreground service, status notification, and a local listener watchdog;
- route diagnostics, runtime status, report export, and configurable logging;
- dedicated Feedback and Updates screens;
- English and Russian UI.

## Screenshots

<p align="center">
  <img src="docs/assets/screenshots/screenshot-main.jpg" width="210" alt="TgWsProxy main screen with local proxy status">
  <img src="docs/assets/screenshots/screenshot-settings-all.jpg" width="210" alt="TgWsProxy settings overview">
  <img src="docs/assets/screenshots/screenshot-settings-connection.jpg" width="210" alt="Local connection settings">
  <img src="docs/assets/screenshots/screenshot-settings-routes.jpg" width="210" alt="Route policies for different networks">
  <br>
  <img src="docs/assets/screenshots/screenshot-settings-cloudflare.jpg" width="210" alt="Cloudflare Proxy and Worker settings">
  <img src="docs/assets/screenshots/screenshot-settings-app.jpg" width="210" alt="Application behavior and appearance settings">
  <img src="docs/assets/screenshots/screenshot-settings-logs.jpg" width="210" alt="Logging and diagnostics settings">
</p>

## Quick start

1. Install an ARM64 APK from [GitHub Releases](https://github.com/Regstar2/tg-ws-proxy-android/releases) when the required version is available, or [build a debug APK](#build).
2. Open TgWsProxy.
3. Keep **MTProto Proxy** as the frontend and port `1443` unless another local service already uses it.
4. Tap **Start proxy**.
5. Tap **Apply in Telegram** and confirm the configuration in Telegram.

When Telegram cannot connect, open the built-in diagnostics and test the selected route separately.

## Requirements

### For use

- Android 8.0 or newer (`minSdk 26`);
- an `arm64-v8a` device;
- Telegram installed;
- a network where at least one allowed route is available.

### For building

- Windows and PowerShell;
- JDK 17;
- Android SDK and Android NDK;
- Go;
- Python 3;
- the Gradle Wrapper included in the repository.

The current native build script uses a Windows NDK toolchain path. The repository does not claim Linux or macOS build support.

## Installation

Check [GitHub Releases](https://github.com/Regstar2/tg-ws-proxy-android/releases) for an APK of the required version. Install a locally built debug APK through ADB:

```powershell
adb install -r app\build\outputs\apk\debug\app-debug.apk
```

Android may require uninstalling the existing application when switching between debug and release signatures. Note that uninstalling may remove local application settings.

## Usage

### MTProto Proxy

1. Select the **MTProto Proxy** frontend.
2. Check the local address and port.
3. Configure a masking domain when needed.
4. Start the service and apply the configuration in Telegram.

Without a masking domain, the link uses a `dd<32 hex chars>` secret. With a configured domain, it uses `ee<secret><domain_hex>`.

### WARP / AmneziaWG

Under **Settings → Cloudflare → WARP / AmneziaWG**, you can create a Consumer WARP profile automatically or import an existing `.conf`. Automatically created profiles pass structural validation, bounded autotuning, and full-duplex network validation before they are persisted. At most one profile is selected at a time.

The `awg_warp` route is used only for connections that Telegram sends to the local proxy frontend. It does not create a system VPN or intercept traffic from other applications.

### SOCKS5 compatibility mode

Configure Telegram manually:

```text
Host: 127.0.0.1
Port: 1443
Username: empty
Password: empty
```

When the port is changed in the application, use the same value in Telegram.

## Operating modes

### Local frontends

| Frontend | Purpose | Limitation |
|---|---|---|
| MTProto Proxy | Primary mode applied through an MTProto proxy link | Uses the application's local port only |
| SOCKS5 / WebSocket | Compatibility with Telegram's manual SOCKS5 configuration | Requires manual host and port entry |

### Routes

| Route kind | Purpose | Limitation |
|---|---|---|
| `cf_proxy_ws` | WebSocket through Cloudflare Proxy domains: `kws{dc}.<domain>/apiws` | Availability depends on external domains and the network |
| `direct_ws` | Direct WebSocket to `kws{dc}.web.telegram.org` | May be blocked or unstable on some networks |
| `cf_worker_ws` | Traffic through a Cloudflare Worker | Requires separate Worker configuration |
| `awg_warp` | Telegram TCP through a userspace AmneziaWG/WARP tunnel | Requires a selected valid WARP/AWG profile; provisioning depends on the external Consumer WARP API |
| `tcp_fallback` | Direct TCP to a Telegram data-center IP on port `443` | This is not a WebSocket route |

WebSocket is a transport. The interface and diagnostics identify the actual path with a separate `route kind`.

## Configuration

Defaults for version `1.11.0-beta.1`:

| Setting | Value |
|---|---|
| Local address | `127.0.0.1` |
| Local port | `1443` |
| Frontend | MTProto Proxy |
| Mobile data | `cf_proxy_ws` only, without fallback |
| Wi-Fi | `cf_proxy_ws` → `direct_ws` → `tcp_fallback` |
| Unknown network | `cf_proxy_ws` only, without fallback |
| Runtime log collection | disabled |
| Persistent file logs | disabled |

`awg_warp` is not added to the default route policy automatically; the user explicitly configures and selects a WARP/AWG profile.

Default-setting migration applies only when the user has not changed route policies manually.

> [!WARNING]
> Enable masking-domain passthrough only for a trusted domain. The application will create real outbound connections to the configured host.

## Architecture

```text
Telegram
   │
   ▼
local MTProto Proxy or SOCKS5 frontend
   │
   ▼
Android ProxyService (foreground service)
   │
   ▼
Go runtime: libtgwsproxy.so
   │
   ├── cf_proxy_ws
   ├── direct_ws
   ├── cf_worker_ws
   ├── awg_warp
   └── tcp_fallback
```

The Android layer uses Kotlin and Jetpack Compose. The native runtime is located in `native/tgwsproxy/`, is built as `libtgwsproxy.so`, and connects to the Android application through a JNA/CGO bridge. `awg_warp` uses a userspace netstack plus `amneziawg-go`; no system TUN/VPN is created.

Detailed design: [docs/architecture/architecture.md](docs/architecture/architecture.md).

## Security

- MTProto secrets, query parameters, and sensitive addresses are masked in the interface, diagnostic reports, and logs where the implementation supports it.
- The WARP/AWG private key is generated/stored locally in app-private storage and is not sent to the Consumer WARP API; only the public key and required registration metadata are sent externally.
- Cloudflare Worker URLs, proxy secrets, keystores, and signing variables must not be published in issues, logs, or commits.
- Release signing uses local environment variables; the keystore is excluded from Git.
- Feedback does not automatically attach runtime logs, proxy credentials, Telegram data, IP addresses, or secrets.
- Update checks use the official GitHub Releases API; the application does not install APKs itself.
- A masking domain changes the Fake TLS handshake shape but does not turn the application into a VPN.

Review every diagnostic report manually before publishing it.

## Privacy

TgWsProxy processes connections that Telegram sends to the local proxy frontend. The application does not create a system VPN tunnel or intercept traffic from other applications.

Depending on the route policy, traffic goes directly to Telegram, through Cloudflare Proxy, through a Cloudflare Worker, or through the selected userspace AWG/WARP profile. Runtime collection and persistent file logging are disabled by default and must be enabled manually for diagnostics.

## Troubleshooting

Built-in diagnostics show:

- configured, selected, and actually active routes;
- DNS, TCP, TLS, HTTP, and WebSocket probe results;
- Cloudflare Proxy and Worker state;
- WARP/AWG profile state, handshake, and support-safe tunnel/application counters;
- Fake TLS statistics;
- recent errors and fallback reasons;
- an exportable diagnostic report.

The runtime uses the `TgWsProxy` logcat tag. Diagnostic probes should not change the active route policy.

## Build

Current toolchain:

| Component | Version or value |
|---|---|
| Android Gradle Plugin | `8.2.2` |
| Gradle Wrapper | `8.2.1` |
| Kotlin | `1.9.22` |
| compileSdk / targetSdk | `35` / `35` |
| minSdk | `26` |
| ABI | `arm64-v8a` |

Build a debug APK:

```powershell
.\gradlew.bat assembleDebug
```

Gradle invokes the native build and icon generation through `preBuild`. Output:

```text
app\build\outputs\apk\debug\app-debug.apk
```

Build and copy the APK to the local `artifacts/` directory:

```powershell
.\scripts\build-apk.ps1 -Configuration Debug
```

Build the final signed release only when the local keystore is configured:

```powershell
.\scripts\release.ps1 -Version v1.11.0-beta.1
```

The script verifies that the tag matches `versionName`, verifies the APK signature, and produces the APK plus SHA-256 in `dist/`.

Build the Go runtime separately:

```powershell
.\scripts\build-native-android.ps1
```

## Testing

Single project CI entry point:

```powershell
.\scripts\ci.ps1
```

It covers Go module verification, native Go tests, Android unit tests, debug APK assembly, and packaged-resource auditing. The release-candidate source also runs the final release audit from CI.

Manual pre-tag validation must cover signed-APK upgrade over `v1.10.14`, proxy start/stop, the regular MTProto/CF route, automatic WARP provisioning, `awg_warp` messages and media, Wi-Fi ↔ mobile switching, reconnect, Feedback/Updates, and review of exported diagnostics for secrets.

Current checklist: [docs/testing/README.md](docs/testing/README.md).

## Documentation

| Task | Document |
|---|---|
| Architecture and data flow | [docs/architecture/architecture.md](docs/architecture/architecture.md) |
| Cloudflare Proxy domain pool | [docs/architecture/CF_DOMAIN_POOL.md](docs/architecture/CF_DOMAIN_POOL.md) |
| Cloudflare Worker setup | [docs/architecture/cloudflare-worker.md](docs/architecture/cloudflare-worker.md) |
| Repository structure | [docs/development/repository-structure.md](docs/development/repository-structure.md) |
| Manual testing | [docs/testing/README.md](docs/testing/README.md) |
| Release preparation | [docs/releases/release.md](docs/releases/release.md) |
| `1.11.0-beta.1` release notes | [docs/releases/RELEASE_NOTES_v1.11.0-beta.1_EN.md](docs/releases/RELEASE_NOTES_v1.11.0-beta.1_EN.md) |
| `1.10.13` final audit | [docs/releases/v1.10.13-final-audit.md](docs/releases/v1.10.13-final-audit.md) |
| Change history | [CHANGELOG.md](CHANGELOG.md) |

## Credits

- [Flowseal/tg-ws-proxy](https://github.com/Flowseal/tg-ws-proxy) — upstream runtime and the main WebSocket routing concept;
- [amurcanov/tg-ws-proxy-android](https://github.com/amurcanov/tg-ws-proxy-android) — the Android wrapper this branch started from;
- [Regstar2/tg-ws-proxy-android](https://github.com/Regstar2/tg-ws-proxy-android) — the current Android implementation and ongoing development.

AI tools were used for selected parts of the code, tests, and documentation. The resulting changes are checked by tests and the release audit; manual device acceptance remains a required final step.

## Limitations

- only the `arm64-v8a` ABI is supported;
- the application is a Telegram proxy, not a system-wide VPN;
- route availability depends on the network and external infrastructure;
- Consumer WARP provisioning depends on an external API; a fresh install may require the Worker bootstrap when direct registration is unavailable;
- `awg_warp` in `1.11.0-beta.1` has been tested on a limited set of devices and networks;
- Worker Pool remains slower than direct connectivity and is not intended for the primary use case;
- port `1443` must be changed when another local service already uses it;
- the native build script targets Windows; Linux and macOS support is not confirmed;
- masking-domain passthrough creates connections to the configured domain;
- an APK is not guaranteed to be available for every version in GitHub Releases.

## License

The project is distributed under the [GNU General Public License v3.0](LICENSE).
