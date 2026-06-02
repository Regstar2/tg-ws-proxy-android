# Adaptive Auto routing (v1.5.1)

Auto mode is adaptive in the Android fork. It does not blindly follow a fixed desktop order on every connection.
It uses recent route health, network profile, DC direction, cooldowns, last-good route, and an **Auto strategy** preset to choose the most likely working path.
Manual modes remain deterministic.

## Route types

- `direct_ws` — direct Telegram WebSocket
- `cf_worker_ws` — Cloudflare Worker WebSocket
- `cf_proxy_ws` — Cloudflare proxy WebSocket (domain pool)
- `tcp_fallback` — direct TCP passthrough when allowed

## Auto strategy presets

Strategy presets **only affect Auto** and **Direct + fallback routes**. Manual modes ignore strategy.

| Strategy | Purpose |
|----------|---------|
| **Balanced** (default) | Normal Direct priority; Worker preferred after Direct 302/timeouts; CF after Worker or by health |
| **Prefer Direct** | Extra Direct score bonus; Worker/CF as fallback when Direct is healthy |
| **Prefer Worker** | Worker start bonus; falls back to Balanced if Worker domain is empty |
| **Prefer CF proxy** | CF bonus; CF health/cooldown and 429/403/5xx lower score quickly |
| **Fast failover** | Shorter cooldowns, higher consecutive-failure penalty, faster switch to last-good |

Prefer Worker does **not** force Worker-only. **Worker only** remains the strict mode with no Direct/CF fallback.

## Network profile

The app builds a lightweight network profile without storing raw SSID:

- **Wi-Fi** — `WIFI` + hashed identifier
- **Mobile** — `MOBILE` + hash of MCC/MNC / operator name when available
- **Unknown** — safe fallback profile

Profiles are passed to the native runtime as `@network_profile_*` tokens. At most **20** profiles are kept; older profiles are removed by last activity (current profile is not removed during an active session from the UI path).

## Route statistics and scoring

Per profile, route type, DC, and media flag the runtime tracks success/failure, latency, cooldown, and last-good route (12h TTL).

Scoring uses explicit **RouteScoreWeights** in Go (`tgwsroute/adaptive_strategy.go`):

- Base scores per route type (strategy and network type modifiers)
- Success bonus, last-good bonus (ignored if route is in cooldown or stale)
- Failure and consecutive-failure penalties (higher under Fast failover)
- Latency penalty
- Routes in **cooldown are skipped**, not merely penalized
- Unavailable routes (empty Worker domain, exhausted CF pool) are **not candidates**
- TCP fallback has a low base score when other routes exist

## Failure classification

Failures are classified for scoring and diagnostics, for example:

`WS_302`, `WS_429`, `WS_403`, `WS_5XX`, `WS_TIMEOUT`, `TCP_TIMEOUT`, `DNS_FAILURE`, `TLS_FAILURE`, `WORKER_EMPTY_DOMAIN`, `WORKER_WS_FAILURE`, `CF_POOL_EXHAUSTED`, `CF_DOMAIN_COOLDOWN`, `TELEGRAM_DC_UNKNOWN`, `TELEGRAM_IPV6_BLOCKED`, `CLIENT_EOF`, `CONTEXT_CANCELLED`, `UNKNOWN`.

- **CLIENT_EOF** and **CONTEXT_CANCELLED** (proxy stop) do not heavily penalize routes
- **WS_302** on Direct triggers DC-scoped cooldown/blacklist behavior
- **WS_429** on CF puts the domain in cooldown
- **TCP_TIMEOUT** heavily penalizes TCP fallback

## Last-good route

When a route succeeds on a network profile, it is remembered per DC/media for up to 12 hours.
It receives a score bonus only if still valid and **not** in cooldown.

## Route selection explanation

The diagnostics panel shows a short **selection summary** (up to a few lines): cooldown reasons, CF 429, TCP timeouts, etc.
Detailed scores remain in exported diagnostics / debug output.

## Diagnostics export and GitHub report

Settings → Adaptive routing:

- **Auto strategy** — preset selector
- **Copy diagnostics report** — markdown for GitHub Issues (version, mode, strategy, route stats, cooldowns)
- **Mask domains in report** — default on
- **Include domains in log export** — default off; runtime log export adds an **Adaptive Routing Diagnostics** section

Privacy:

- No raw SSID in exports
- Worker/CF domains masked by default (`nameless-*.workers.dev` style)
- Stats are local-only on device

## Reset

- **Reset current network statistics** — active profile only
- **Reset all route statistics** — all profiles (does not reset CF cache, Worker domain, or connection mode)

## Manual modes

Adaptive scoring applies to **Auto** and **Direct + fallback routes** only.

`Worker only`, `CF only`, `Direct only`, `Worker first`, and `CF first` keep their documented deterministic behavior regardless of Auto strategy.
