# Architecture

High-level view of TGWSProxyAndroid. For file locations see [repository-structure.md](../development/repository-structure.md).

## Flow

```text
Telegram app
    -> local proxy frontend (default: MTProto Proxy 127.0.0.1:1443)
        -> Android ProxyService (foreground)
            -> Go runtime (libtgwsproxy.so)
                -> route selection + pools
                    -> direct_ws | cf_worker_ws | cf_proxy_ws | tcp_fallback
```

## Layers

### Android UI and settings

- Jetpack Compose (`MainActivity.kt`): start/stop proxy, metrics card, route policy UI, diagnostics, logs.
- Preferences: shared local port, frontend type, connection mode, worker domain,
  CF domains, per-network policies, notifications, language.
- MTProto settings are stored separately from the SOCKS5 runtime config. They
  use local host `127.0.0.1`, shared default port `1443`, and a generated
  16-byte / 32-hex raw secret. Diagnostic reports must include only masked
  MTProto secrets.
- The MTProto settings UI exposes the frontend switch, local config, masking
  domain, Fake TLS passthrough, masked secret status, and Telegram proxy link
  actions. Generated links use the official `t.me/proxy` / `tg://proxy`
  parameter shape with `dd` or `ee` transport prefixes.

### Foreground service

- `ProxyService.kt` runs the selected local proxy frontend, polls `GetProxyStatus()`
  for the SOCKS5/WS runtime, updates notification and `ProxyRuntimeState`.
- Network changes can trigger `ACTION_RECONFIGURE` with updated policy tokens.
- `LocalProxyFrontendType` selects the local frontend protocol. The default
  frontend is `MTPROTO_EXPERIMENTAL`; the pref value is kept for migration
  compatibility, while UI labels show `MTProto Proxy`.
- `Socks5LocalProxyFrontend` wraps the existing Go/JNA `NativeProxy` calls without changing runtime arguments.
- `MtProtoLocalProxyFrontend` loads `MtProtoProxyConfig`, maps it into
  `MtProtoRuntimeConfig`, checks the local port before start, writes masked logs,
  and delegates to `MtProtoRuntimeAdapter`.
- `NativeMtProtoRuntimeAdapter` calls the Go `StartMtProtoProxy` listener. A
  validated MTProxy handshake can enter the route chain and report selected vs
  actual backend for `direct_ws`, `cf_proxy_ws`, `cf_worker_ws`, or direct TCP.
  The tested primary path is MTProto over `cf_proxy_ws`.

### Runtime config (Android → Go)

- `ConnectionRuntimeConfig.buildRuntimeTokens()` emits `@key=value` tokens: connection mode, CF flags, worker domain, `@route_*`, `@preferred_route`, adaptive stats, network profile.
- Go `parseRuntimeConfig()` applies tokens and enforces `NetworkRoutePolicy` when `@route_*` are present.

### Go runtime

- SOCKS5 listener, MTProto init parsing, per-DC routing.
- MTProto listener in `mtproxyfrontend`, with upstream-derived
  handshake/session transforms, Fake TLS, masking-domain passthrough, and route
  connectors for direct WS, CF Proxy, Worker WS, and direct TCP.
- Route chains (`routing.go`), adaptive ordering (`adaptive_bridge.go` + `tgwsroute/`).
- Pools: direct WS, Worker WS, CF domain pool with health/cooldown.
- Status export (`proxy_status_bridge.go`) for Android metrics.

### Route stats

- Adaptive store: successes/failures, cooldown, last-good per profile/DC/media.
- Historical stats may include old routes; **current route** in UI must come from active allowed sessions (see `noteActiveRoute` / policy generation).

### Logs

- Go: `logInfo` / `logDebug` → logcat `TgWsProxy`.
- Android: optional persistent file sink, export zip, diagnostics markdown.

## Terminology

### Route kind (what path is used)

| Kind | Description |
|------|-------------|
| `direct_ws` | Direct WebSocket to Telegram `kws{dc}.web.telegram.org` |
| `cf_worker_ws` | WebSocket via user’s Cloudflare Worker |
| `cf_proxy_ws` | WebSocket via Cloudflare proxy host `kws{dc}.<domain>/apiws` |
| `tcp_fallback` | TCP to DC IP:443 |

### Strategy / mode (preference order)

Legacy connection modes sent to Go as `connection_mode`:

| Mode | Typical use |
|------|-------------|
| `auto` | Adaptive selection + policy filter |
| `direct_with_fallback` | Direct first, then fallbacks |
| `worker_first` | Worker first, then fallbacks |
| `cf_first` | CF proxy first, then fallbacks |
| `worker_only` / `cf_only` / `direct_only` | Single-route style modes |

Per-network **NetworkRoutePolicy** (Android) defines which route kinds are allowed and preferred; Go must treat it as an absolute filter.

### Transport type (how bytes move)

| Transport | Used by |
|-----------|---------|
| `websocket` | `direct_ws`, `cf_worker_ws`, `cf_proxy_ws` |
| `tcp` | `tcp_fallback` |

**Important:** `cf_proxy_ws` uses WebSocket transport but is **not** the same as `direct_ws`. Do not display “WebSocket” alone as the current route.

## Policy generation

When route policy changes, Go bumps `PolicyGen`. Sessions from older generations must not update current route display or current stats (see skip logs in adaptive/status bridge).

## Related docs

- [CONNECTION_MODES.md](CONNECTION_MODES.md) — mode tables (some version notes may be legacy)
- [ADAPTIVE_ROUTING.md](ADAPTIVE_ROUTING.md) — Auto scoring
- [CF_DOMAIN_POOL.md](CF_DOMAIN_POOL.md) — CF domains
- [cloudflare-worker.md](cloudflare-worker.md) — Worker setup
