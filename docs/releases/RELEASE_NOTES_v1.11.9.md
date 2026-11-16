# TgWsProxy Android v1.11.9 — Cloudflare Proxy TLS/SNI fix

`versionName 1.11.9` · `versionCode 65` · stable patch · Android 8.0+ / ARM64 (`arm64-v8a`)

Cloudflare Proxy now uses the base domain for TLS SNI while preserving `kwsN.<base-domain>` in HTTP WebSocket Host and the `/apiws` path. The fix applies to MTProto, SOCKS5 and route diagnostics. Direct WS, Worker and route policy are unchanged. Adapted from [malordin/tg-ws-proxy-android](https://github.com/malordin/tg-ws-proxy-android/commit/a98fd27c384452b8b0e78a4273abf005077aad6b) (#156, PR #157).

[Полное описание на русском](https://github.com/Regstar2/tg-ws-proxy-android/blob/v1.11.9/docs/releases/v1.11.9.md) · [Full release notes in English](https://github.com/Regstar2/tg-ws-proxy-android/blob/v1.11.9/docs/releases/v1.11.9_EN.md)

## Verification

The implementation passed Go/Worker and Windows CI on PR #157 ([Actions #43934207261](https://github.com/Regstar2/tg-ws-proxy-android/actions/runs/43934207261)) and on the integration commit ([Actions #43935254597](https://github.com/Regstar2/tg-ws-proxy-android/actions/runs/43935254597)). The owner confirmed that the Cloudflare Proxy test build works on Android. Signed v1.11.9 artifact verification, exact-tag CI and operator-specific acceptance are separate release gates.

## Known limitations

Operator-specific report #154 remains open. Notification reconnect bug #155 is not fixed. Native WebSocket certificate/hostname validation is disabled in pre-existing code (#158), not introduced by this change. Successful TLS/HTTP 101 is not proof of full Telegram media delivery.

## Installation

After publication, use the official `TgWsProxy-Android-v1.11.9-arm64-v8a.apk` and matching `.apk.sha256`. Update v1.11.8 using the same signing certificate without uninstalling to preserve settings.

[CHANGELOG](https://github.com/Regstar2/tg-ws-proxy-android/blob/v1.11.9/CHANGELOG.md) · [Compare v1.11.8...v1.11.9](https://github.com/Regstar2/tg-ws-proxy-android/compare/v1.11.8...v1.11.9).
