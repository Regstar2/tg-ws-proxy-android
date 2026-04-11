# Original vs Android Diff

Updated: 2026-04-10

## Scope

Compared:

- Original project: `Flowseal/tg-ws-proxy` (Python)
- Current Android fork: `amurcanov/tg-ws-proxy-android` (Go + Android UI)

Focus areas:

- WS path selection
- fallback logic
- cooldown logic
- pool refill/reuse
- missing DC handling
- Cloudflare Proxy path

## Android fork map

### Runtime

- `handleClient()` in `tg-ws-proxy.go`
  - Parses SOCKS5 connect, extracts Telegram init, maps DC, selects WS or TCP fallback.
- `wsDomains()` in `tg-ws-proxy.go`
  - Builds `kws*.web.telegram.org` domains.
- `wsConnect()` in `tg-ws-proxy.go`
  - Pinned IP + domain/SNI WSS connection.
- `WsPool.Get/refill/connectOneWS()` in `tg-ws-proxy.go`
  - Prewarms and reuses WS connections.
- `tcpFallback()` in `tg-ws-proxy.go`
  - Direct TCP fallback to destination Telegram IP.
- `ipToDC` and `dcOverrides` in `tg-ws-proxy.go`
  - Static IPv4 DC mapping and overrides.

### Android UI / diagnostics

- `MainActivity.kt`
  - UI, DC IP settings, report folder, upstream test button.
- `UpstreamDiagnostics.kt`
  - Transport reachability tests for DC IPs and `kws*`.

## Original project map

### Runtime

- `_handle_client()` in `proxy/tg_ws_proxy.py`
  - Parses MTProto obfuscation handshake, extracts `dc`, tries WS, then fallback.
- `_ws_domains()` in `proxy/tg_ws_proxy.py`
  - Builds `kws*.web.telegram.org` domains, with only `DC203 -> DC2` override.
- `_WsPool.get/_refill/_connect_one()` in `proxy/tg_ws_proxy.py`
  - Prewarms and reuses WS connections.
- `do_fallback()` in `proxy/bridge.py`
  - Chooses between CF proxy and direct TCP fallback.
- `_cfproxy_fallback()` in `proxy/bridge.py`
  - Connects to `kws{dc}.{cf_domain}` and bridges through CF.
- `_tcp_fallback()` in `proxy/bridge.py`
  - Direct TCP fallback to default DC IP map.

### Config / UI

- `proxy/config.py`
  - Default `cfproxy=True`, `cfproxy_priority=True`, default encoded CF domain pool.
- `docs/CfProxy.md`
  - Documents CF mode and required `kws1..kws5,kws203` DNS records.
- `ui/ctk_tray_ui.py`
  - Has CF proxy settings and CF connectivity test.

## Key behavioral differences

### 1. Original has a dedicated `CfProxy` branch; Android fork does not

Original:

- Missing DC or WS failure goes into `do_fallback()`
- `do_fallback()` can try:
  - `CF proxy first`
  - or `TCP first`
- `CF proxy` targets `wss://kws{dc}.{cf_domain}/apiws`

Android fork:

- Missing DC goes to direct TCP passthrough/fallback
- WS failure goes to direct TCP fallback
- No `CfProxy` domain path exists in runtime

Why it matters:

- This is the biggest gap explaining why the original can partially survive mobile tethering while Android direct path does not.

### 2. Original handles `DC not in config` as structured fallback; Android treats it as direct passthrough

Original:

- `DC1 not in config -> fallback`
- then tries `CfProxy`, then TCP if configured by priority

Android fork:

- unknown or not configured DC logs:
  - `mapped_dc=... configured=false -> TCP passthrough`
  - `unknown DC... -> TCP passthrough`

Why it matters:

- The original still gives missing DCs a chance through CF.
- The Android fork currently drops straight into a path that is already known to be blocked on mobile.

### 3. Original has `CfProxy` domain pool and automatic domain rotation

Original:

- Default encoded domain pool in `proxy/config.py`
- Optional user domain
- Background refresh from GitHub
- Tracks `active_cfproxy_domain`

Android fork:

- No CF domain pool
- No CF domain selection state
- No CF connectivity tester in runtime settings

Why it matters:

- The original can move between CF domains or use a custom domain.
- The Android fork has no equivalent escape hatch.

### 4. Original exposes `CfProxy` controls in UI; Android fork exposes only direct DC IP settings

Original:

- Enable/disable CF proxy
- CF priority
- User CF domain
- CF connectivity test

Android fork:

- DC IPs
- pool size
- transport sweep tool
- no CF mode settings

Why it matters:

- The original gives the user a first-class way to route around blocked direct paths.
- The Android fork currently forces all traffic through direct Telegram endpoints.

### 5. Pool tuning differs, but this is secondary

Original:

- `WS_POOL_MAX_AGE = 120.0`

Android fork:

- `wsPoolMaxAge = 60.0`

Why it matters:

- This may affect churn and reuse behavior.
- It does not explain the mobile-network failure as strongly as the missing `CfProxy` branch.

### 6. DC override policy differs

Original:

- Only `DC203 -> DC2`

Android fork:

- `DC203 -> DC2`
- `DC1 -> DC2` was added locally during debugging

Why it matters:

- This can influence domain selection and `kws*` routing.
- It is still lower priority than the absence of `CfProxy`.

## Ranked top differences

1. No `CfProxy` runtime path in Android fork.
2. `DC not in config` goes to direct passthrough in Android instead of structured CF/TCP fallback.
3. No CF domain pool, no active domain rotation, no custom domain support in Android.
4. No Android UI/config for `CfProxy` enable/priority/domain/test.
5. Smaller pool age / different override behavior, which may affect stability but not the main mobile-network dead end.

## Practical conclusion

The original Windows proxy is not proving that "direct Telegram path is fine". It is proving something narrower:

- direct path is unstable or partially blocked,
- but `CfProxy` sometimes keeps sessions alive long enough to work.

That makes `CfProxy` the next rational implementation target in the Android fork.

## Recommended next sequence

1. Add `CfProxy` diagnostics only.
2. Add `CfProxy preferred` and `CfProxy only` modes.
3. Add IPv4-preferred resolution only for CF proxy hostnames.
4. Add Android UI/config for CF domain selection and CF test.
5. Only after that, decide whether plain TCP MTProto probing is still needed.
