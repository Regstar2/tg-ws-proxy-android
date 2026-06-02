# TGWSProxyAndroid v1.8.0

## Summary

Maintenance/release cleanup build after the 1.6–1.7 routing work.

This release finalizes repository structure, documentation, release notes, screenshots, build paths, and test/release checklists. It does not intentionally change runtime or routing behavior compared with the already implemented 1.7.9.x fixes.

## Since v1.6.0

- Added per-network Wi-Fi/Mobile route policies.
- Added route toggles for Direct WebSocket, Cloudflare Worker, Cloudflare Proxy, and TCP fallback.
- Added preferred route and fallback controls per network type.
- Added automatic reconfigure when switching Wi-Fi ↔ Mobile.
- Added effective route policy diagnostics.
- Added route-level probe reports.
- Added Worker/CF pool metrics.
- Added persistent runtime logs and safer export.
- Fixed route policy token handling in native runtime.
- Fixed route display: route kind is now separated from transport type.
- Prevented stale Direct/Worker events from overriding current UI route state.
- Removed `pclead.co.uk` as the default Cloudflare proxy domain (use your own domain or built-in/cached pool).

## What changed in v1.8.0

- Moved Go runtime to `native/tgwsproxy/`.
- Reorganized documentation:
  - `docs/architecture/`
  - `docs/development/`
  - `docs/testing/`
  - `docs/releases/`
  - `docs/assets/screenshots/`
- Updated README with current structure, screenshots, route terminology, and build commands.
- Added/updated release checklist and manual testing checklist.
- Hardened `.gitignore` for artifacts, runtime logs, secrets, and keystores.
- Confirmed `versionName 1.8.0`, `versionCode 36`.

## Runtime behavior

No intentional routing/runtime behavior changes were made specifically for v1.8.0.

The important route/runtime fixes from the late 1.7.x line are included:

- disabled route policy entries should not be selected;
- `routeKind` is separated from WebSocket transport;
- stale route events should not override the active route for the current policy generation.

## Recommended update path

Install over the previous build.  
If Android rejects installation because of signature mismatch, uninstall the previous APK and install again.

## APK

Attach **`tgwsproxy-release.apk`** (signed release build).  
Local signing: copy `release-signing.env.example` → `release-signing.env` (gitignored), then `.\scripts\build-apk.ps1 -Configuration Release`.

## Verification checklist

- Go `tgwsroute` tests: passed (`native/tgwsproxy`).
- Android `assembleDebug` and `lint`: passed.
- Android `testDebugUnitTest`: failed on this Windows host (Gradle test worker `ClassNotFoundException` — environment issue, not an app assertion failure). Re-run on Linux/CI or Android Studio if needed.
- Signed release APK built with local `tgwsproxy-release.jks` via `release-signing.env`.

## Known limitations

- This app is not a VPN.
- It only proxies Telegram traffic configured through SOCKS5.
- Worker route requires a configured Cloudflare Worker.
- Shared or rate-limited Cloudflare proxy domains may fail depending on network conditions.
