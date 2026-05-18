# Original vs Android Diff

Updated: 2026-05-18

## Scope

Compared projects:

- Runtime origin: `Flowseal/tg-ws-proxy`
- Android origin: `amurcanov/tg-ws-proxy-android`
- Current repository: Android fork with local runtime and UI fixes

This file tracks high-level behavior that matters for the current Cloudflare Proxy baseline.

## What changed since the first comparison

The first audit found that the Android fork did not have a practical Cloudflare Proxy runtime branch. That is no longer true for this working tree.

Current fork now has:

- `CF proxy`, `CF first`, and `CF only` runtime modes.
- Cloudflare fallback to `wss://kws{dc}.<domain>/apiws`.
- Hostname-first Cloudflare dialing, followed by resolved IP candidates when needed.
- DNS/TCP/WS diagnostic logs for Cloudflare attempts.
- UI settings for Cloudflare mode and domain.
- Bridge first-exit logging for close-reason diagnosis.
- WS pool warmup disabled in CF-first and CF-only modes to reduce direct-path noise.

## Runtime behavior notes

### Cloudflare path

The useful Android path now matches the important part of Flowseal's strategy:

- derive the Telegram DC from the client request/init data;
- route the session to `kws{dc}.<cf_domain>`;
- bridge the client socket to the Cloudflare WebSocket;
- fall back only when the selected CF route cannot be established.

The default domain remains `pclead.co.uk`, but it is a shared endpoint and can return `429 Too Many Requests`. A user-owned Cloudflare domain is still the better long-term setup.

### Direct WS domains

The current override policy is back to the safer baseline:

- `DC203 -> DC2`

The temporary debug override:

- `DC1 -> DC2`

was removed because it could make logs and direct WS domain selection misleading, for example mapping DC1 traffic to `kws2.*`.

### Bridge close handling

The bridge now records the first primary close reason before cancelling the paired copy loop. Secondary `net.ErrClosed` / EOF-like errors should no longer hide the primary cause.

Useful log categories:

- `bridge first-exit primary ...`
- `bridge secondary ...`
- `CF proxy closed: reason=...`

## Added in 1.3.0-worker (vs Flowseal v1.7.0 baseline)

- Cloudflare Worker route: `wss://<domain>/apiws?dst=...&dc=...&media=...`
- Connection modes: Auto, Direct + fallback routes, Worker first, CF first, Worker only, CF only, Direct only
- CF domain pool with manual-domain priority and per-domain cooldown
- In-app connectivity tests (Direct / Worker / CF / TCP / all)
- See [docs/cloudflare-worker.md](docs/cloudflare-worker.md) and [docs/CONNECTION_MODES.md](docs/CONNECTION_MODES.md)

## Remaining differences from Flowseal runtime

- No Fake TLS masking (TODO in docs).
- Android has foreground service, Compose UI, log export, theme/language settings, and mobile-specific UX that the desktop/runtime project does not need.
- Direct path remains available, but it is not the primary working route for tested mobile networks.

## v1.3.1 routing hardening

- `Worker only` and `CF only` now block direct TCP passthrough for Telegram-like IPv4/IPv6 destinations when the destination is not mapped to a DC.
- Telegram mapping includes the observed `149.154.175.55 -> DC1` edge case.
- CF pool health now cools down `429`, `403`, `5xx`, and repeated timeout/TLS/WebSocket failures; a manual domain can temporarily yield to the built-in pool while unhealthy.
- Android routing intentionally keeps mobile-specific `Worker first`, `CF first`, `Worker only`, `CF only`, `Auto`, and `Direct + fallback routes` modes even though Flowseal desktop v1.7.0 removed the separate CF-priority surface.
- Fake TLS and GitHub-backed CF-domain auto-update remain out of scope for v1.3.1.

## v1.3.2 CF domain pool

- Manual CF domains remain supported, but the Android fork now carries a built-in fallback pool derived from the Flowseal upstream list.
- Per-domain health tracks success/failure counts, last reason, cooldown, and latency.
- `429`, `403`, `5xx`, and transport/setup failures move a bad domain out of the hot path so `Auto`, `CF first`, and fallback modes do not keep retrying the same endpoint forever.
- The diagnostics UI now exposes manual vs built-in domain status and offers a cooldown reset action.
- GitHub-backed upstream refresh and Fake TLS are still intentionally deferred to `v1.4.0` and later work.

## v1.4.0 CF domain auto-update

- The Android fork now downloads the Flowseal upstream CF-domain list into a persistent cache.
- Selector priority is explicit: `manual -> cached_upstream -> built_in`.
- Upstream refresh is non-critical: failed, empty, or invalid downloads keep the old cache, and the built-in list remains available.
- Settings now expose cached upstream counts, last update status, a manual refresh action, and a 24-hour auto-update throttle.
- Fake TLS and GitHub pinned TLS fallback remain intentionally out of scope for this release.

## Practical conclusion

The current Android fork is no longer blocked on "missing CfProxy". The active question is now operational stability:

- Does `CF first` / `CF only` remain usable for real Telegram traffic on mobile networks?
- Does the default domain hit Cloudflare limits too often?
- Is a custom domain workflow needed before a public release?

Until Cloudflare Proxy is proven insufficient, avoid spending more time on random direct Telegram IP/path selection.
