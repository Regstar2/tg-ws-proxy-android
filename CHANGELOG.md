# Changelog

All notable user-facing changes are listed here. Detailed notes for older releases: [docs/releases/](docs/releases/).

## 1.8.2

- Route Probe Core: structured route diagnostics (`RouteProbeRunner`, step-based DNS/TCP/TLS/HTTP/WebSocket checks).
- `RouteDiagnosticsRepository` and metrics-card **Check routes** button; results in UI and logcat only (no routing changes).
- Unit tests for probe status aggregation.

## 1.8.1

- Runtime route truth: UI separates configured mode, selected route, active route, last success/failure, and fallback reason.
- Go runtime exports structured route state via `GetProxyStatus` and logs route lifecycle events (`Route selected`, `Route connect started`, `Fallback activated`, etc.).
- Connection metrics card shows a compact route status block (strings in resources, EN/RU).

## 1.8.0

- Repository cleanup: Go runtime moved to `native/tgwsproxy/`.
- Documentation layout: `docs/architecture/`, `docs/development/`, `docs/testing/`, `docs/releases/`, `docs/assets/screenshots/`.
- README updated (structure, build, screenshot paths).
- `.gitignore` hardened for `artifacts/`, `runtime-logs/`, logs, secrets, keystores.
- Added `docs/development/repository-structure.md`, `docs/testing/manual-checklist-1.8.md`.
- Removed default Cloudflare domain `pclead.co.uk` (manual domain empty by default; built-in/cached pool).
- No intentional runtime or routing behavior changes for the v1.8.0 tag itself (1.7.9.x fixes included).

## 1.7.9.3

- Fixed route status showing generic “websocket” instead of actual route kind.
- Separated route kind from transport type in runtime/UI stats.
- Current route uses active sessions for the current policy generation; stale direct/worker events no longer override UI.
- Improved diagnostics logs (`routeKind`, `transportType`, `policyGeneration`).

## 1.7.9.2

- Route policy tokens from Android are applied in Go runtime; disabled routes (especially worker) are no longer selected.
- Policy acts as absolute filter on adaptive selection and fallbacks.

## 1.7.9.1

- Safer default route policies for mobile/Wi‑Fi; migration for users who did not customize policies.
- “Recommended” preset in route policy UI.

Earlier versions: see [docs/releases/](docs/releases/).
