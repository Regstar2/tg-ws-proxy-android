# TgWsProxy Android v1.11.0-beta.1

[Русский](RELEASE_NOTES_v1.11.0-beta.1.md) · **English**

## About this release

`v1.11.0-beta.1` is a public beta of the new userspace AWG/WARP route and the in-app WARP/AWG profile workflow. The beta is intended to exercise the new route on more Android devices and networks before `v1.11.0` stable.

The update remains compatible with the existing application configuration. AWG/WARP is an additional route and does not replace the existing Cloudflare Proxy, Worker, or direct paths. The application still does not create an Android `VpnService` or route all device traffic.

## Highlights

- Added `awg_warp`: Telegram traffic can use a userspace AmneziaWG/WARP stack without root, a system TUN interface, or Android `VpnService`.
- Settings → Cloudflare → WARP / AmneziaWG can automatically create a Consumer WARP profile, import `.conf`, validate, select, inspect, and delete profiles.
- Automatic provisioning generates the keypair locally, runs bounded transport autotuning, and persists a profile only after two successful full-duplex probes.
- When direct Consumer WARP API access is unavailable, the application can use a restricted Worker bootstrap without exposing an arbitrary HTTP proxy.

## Changes

### Added

- Multi-profile app-private storage for WARP/AWG configs with a separate selected profile id.
- Automatic Consumer WARP registration/activation and AWG config generation.
- Strict network validation requiring a fresh handshake, tunnel TX/RX, and real application downstream through the userspace AWG transport.
- Bounded autotuning across up to 16 endpoint and `Jc` / `Jmin` / `Jmax` / `I1` candidates; a passing candidate is confirmed by a second full-duplex probe (`2/2`).
- Profile list, profile creation, and profile details pages under Cloudflare settings.
- Manual `.conf` import as a fallback.
- A restricted Worker bootstrap for `api.cloudflareclient.com` when direct Android TLS access to the Consumer WARP API is unavailable.

### Changed

- HTTP `429` during WARP registration no longer causes a burst of rapid repeated POST attempts.
- When a fresh registration is temporarily unavailable, autotuning can reuse a previously saved automatic Consumer WARP registration seed without sending its private key anywhere.
- `PrivateKey` is hidden in the UI by default and excluded from support-safe diagnostics and logs.

## Installation and updating

### New installation

After publication, download `TgWsProxy-Android-v1.11.0-beta.1-arm64-v8a.apk` from GitHub Releases. Android 8.0+ and `arm64-v8a` are supported.

### Updating

The planned update path is to install the beta APK over `v1.10.14` signed with the same release key. Existing application settings and saved profiles should remain intact.

A final signed-APK upgrade smoke test for the actual release artifact must be completed before the tag is created. If Android reports an incompatible signature, do not uninstall the application without accounting for the fact that uninstalling removes app-private settings.

The built-in Updates screen checks the official GitHub Releases feed and opens the release page; the application does not perform a silent self-update.

## Verification

| Check | Environment | Result |
|---|---|---|
| PR CI for #84 implementation | GitHub-hosted Windows + native/Worker tests | Passed |
| Automatic Consumer WARP registration + activation | Android 14, Xiaomi 11 Lite 5G NE | Passed |
| Bounded AWG autotune | Android 14 | Candidate confirmed `2/2` |
| Telegram MTProto over `actual_backend=awg_warp` | Android 14 | Passed, `fallback_used=false` |
| Bidirectional Telegram traffic and media | Android 14 | Passed |
| Final signed APK built from tag | — | Not yet tested; required pre-tag gate |
| Release APK upgrade over v1.10.14 | — | Not yet tested; required pre-tag gate |

## Known issues

- Consumer WARP registration relies on an external stability-sensitive API whose availability and rate limits are outside the application's control.
- Direct TLS to `api.cloudflareclient.com` previously stalled on the target Android/Wi-Fi network, so fresh provisioning may require the Worker bootstrap.
- Creating a new independent Consumer WARP registration through an already working AWG/WARP tunnel without Worker is tracked separately in #86 and is not part of this beta.
- `awg_warp` has been tested on a limited set of devices and networks; the beta exists to broaden compatibility coverage before stable.

## Assets

The release workflow is expected to publish exactly two files:

- `TgWsProxy-Android-v1.11.0-beta.1-arm64-v8a.apk` — signed Android APK;
- `TgWsProxy-Android-v1.11.0-beta.1-arm64-v8a.apk.sha256` — SHA-256 checksum.

The actual file size and SHA-256 are recorded only after the release workflow builds the final artifacts.

## Links

- [CHANGELOG](../../CHANGELOG.md)
- [Issue #84](../../issues/84)
- [Issue #86](../../issues/86)
- [Previous release v1.10.14](../../releases/tag/v1.10.14)
