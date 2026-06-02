# Release notes v1.8.0

**Maintenance / repository release.** No intentional routing or runtime behavior changes specific to this tag.

`versionName` **1.8.0** · `versionCode` **36**

## Since v1.6.0 (included in this build line)

- Per-network Wi‑Fi / Mobile route policies (`NetworkRoutePolicy`)
- Route toggles: `direct_ws`, `cf_worker_ws`, `cf_proxy_ws`, `tcp_fallback`
- Preferred route and fallback per network type
- Automatic reconfigure on Wi‑Fi ↔ Mobile switch
- Effective route policy diagnostics and route-level probes
- Worker / CF pool metrics in UI and export
- Persistent runtime logs and privacy-safe export
- Route policy tokens enforced in native runtime (1.7.9.2)
- Route kind separated from WebSocket transport in UI (1.7.9.3)
- Stale Direct/Worker events no longer override current route display
- Safer default policies and Recommended preset (1.7.9.1)
- Removed shared default CF domain `pclead.co.uk` — use your domain or built-in/cached pool

## What changed in v1.8.0 (repository only)

- Go runtime moved to `native/tgwsproxy/`
- Documentation layout:
  - `docs/architecture/`
  - `docs/development/`
  - `docs/testing/`
  - `docs/releases/`
  - `docs/assets/screenshots/`
- README updated (structure, routes, build, screenshots)
- `CHANGELOG.md`, release checklist, manual checklist for 1.8
- `.gitignore` hardened: `artifacts/`, `runtime-logs/`, logs, secrets, keystores

## Runtime behavior (v1.8.0)

No new features or routing changes were added for the v1.8.0 tag itself. Behavior matches the 1.7.9.x line already on `main`.

## Install

Install over the previous APK. If Android reports a signature mismatch, uninstall the old APK and install again.

## Build verification

- `cd native/tgwsproxy && go test ./tgwsroute/...` (main package tests may fail on Windows host; use Linux/CI or device build)
- `.\gradlew.bat assembleDebug`
- Release APK: `.\scripts\build-apk.ps1 -Configuration Release` with local keystore env (not in git)
