# MTProto Upstream Audit

## Sources

### Flowseal/tg-ws-proxy
- URL: https://github.com/Flowseal/tg-ws-proxy
- Commit: e9b74d7d7dcc3beceab4d34d18d91b8ec9fdf098
- License: MIT
- Languages: Python; packaging files for PyInstaller, Docker, and Python wheels.
- Runtime type: desktop/local MTProto proxy runtime that accepts Telegram MTProto proxy traffic and relays it over WebSocket/TCP.
- Build system: `pyproject.toml` with Hatchling; PyInstaller specs in `packaging/`; Dockerfile.
- Notes: Main runtime is in `proxy/tg_ws_proxy.py`, with transport helpers in `proxy/bridge.py`, `proxy/raw_websocket.py`, `proxy/pool.py`, `proxy/config.py`, and `proxy/utils.py`. There is no Android JNI/FFI bridge in this repository.

### amurcanov/tg-ws-proxy-android
- URL: https://github.com/amurcanov/tg-ws-proxy-android
- Commit: caea7b2b11e0fce6665f970213acb765ca430f6b
- License: GPLv3, with Flowseal MIT text included.
- Languages: Kotlin/Java Android app, Rust native runtime, legacy Go source under `old_src_golang/`.
- Runtime type: Android local MTProto proxy frontend backed by a Rust `cdylib`.
- Build system: Gradle Kotlin DSL for Android; Rust Cargo; `build_so.bat` uses `cargo-ndk` to build `arm64-v8a` and `armeabi-v7a` shared libraries.
- Notes: Rust runtime entry point is `src/lib.rs`; core proxy flow is in `src/proxy.rs`; Android bridge is `app/src/main/java/com/amurcanov/tgwsproxy/NativeProxy.kt` using JNA. README states the Go core was rewritten in Rust and the Go core is no longer maintained.

## Architecture Summary

Both upstreams implement a local MTProto proxy frontend, not a SOCKS5 frontend.
Telegram connects to a local MTProto proxy port, sends the MTProto obfuscated
init packet, and the runtime extracts DC information from that packet before
opening an outbound route to Telegram.

Flowseal is the current desktop/reference implementation:

- local default is `127.0.0.1:1443`;
- a 16-byte hex secret is generated or supplied;
- Telegram link is generated as `tg://proxy?...&secret=dd...`, with optional `ee...` fake TLS secret;
- DC ID comes from decrypted bytes 60..62 of the MTProto init packet;
- direct WSS uses `kws{dc}.web.telegram.org` and `kws{dc}-1.web.telegram.org`;
- fallback order is Cloudflare Worker, Cloudflare proxy, then direct TCP fallback when configured/available;
- Cloudflare proxy domains are loaded from defaults and optionally refreshed from Flowseal's GitHub list.

amurcanov ports the same architecture into Android:

- local default is `127.0.0.1:1443`;
- Kotlin stores bind IP, port, DC override fields, CF settings, pool size, and secret;
- `ConnectionTab.kt` builds `https://t.me/proxy?server=...&port=...&secret=dd...`;
- `ProxyController.kt` passes `dc:ip` overrides and the secret to `ProxyService`;
- `ProxyService.kt` calls the Rust library through `NativeProxy.kt`;
- Rust `StartProxy(host, port, dcIps, secret, verbose)` starts a Tokio listener;
- Rust `handle_client` reads and decrypts the 64-byte MTProto init, extracts proto/DC/media, builds a relay init, and chooses direct WSS or fallback.

The current TGWSProxyAndroid app is different: its production frontend is a
SOCKS5 listener on the Go runtime. `ConnectionRuntimeConfig.kt` emits route
policy tokens (`@route_direct_ws`, `@route_worker_ws`, `@route_cf_proxy_ws`,
`@route_tcp_fallback`, `@preferred_route`) for the current Go runtime. A
separate MTProto mode should therefore be introduced as a distinct experimental
frontend/backend instead of replacing the default SOCKS5 path.

## MTProto Runtime Entry Points

Flowseal:

- `proxy/tg_ws_proxy.py`
  - `_try_handshake()` decrypts the MTProto init with the local secret and extracts proto/DC/media.
  - `_generate_relay_init()` creates the outbound Telegram init packet.
  - `_handle_client()` is the per-client runtime entry.
  - `main()` parses CLI settings and configures host, port, secret, DC overrides, CF domains, pool size, and fake TLS.
  - `asyncio.start_server()` starts the local listener.
- `proxy/bridge.py`
  - `MsgSplitter` splits MTProto packets into WebSocket frames.
  - `do_fallback()` tries `cf_worker`, `cf`, and `tcp`.
  - `_cfproxy_worker_fallback()`, `_cfproxy_fallback()`, and `_tcp_fallback()` implement fallback transports.
- `proxy/utils.py`
  - `ws_domains()` maps DC/media to Telegram WSS hostnames.
  - `get_link_host()` resolves link host when listening on `0.0.0.0`.

amurcanov:

- `src/lib.rs`
  - `StartProxy()` is the Android-facing native start entry.
  - `StopProxy()` cancels runtime tasks and closes pooled connections.
  - `SetPoolSize()`, `SetCfProxyCacheDir()`, `SetCfProxyConfig()`, `SetSecret()`, `GetStats()`, and `GetSecretWithPrefix()` are exported native control/status calls.
- `src/proxy.rs`
  - `run_proxy()` starts the accept loop.
  - `handle_client()` reads/decrypts MTProto init, extracts DC/media/proto, creates relay init, and chooses routing.
  - `connect_direct_ws()` tries direct WSS.
  - `do_fallback()` tries Cloudflare proxy first when enabled, then TCP fallback.
  - `tcp_fallback()` connects to DC IP on port 443.
- Android bridge:
  - `NativeProxy.kt` loads `libtgwsproxy.so` through JNA and maps Rust exports.
  - `ProxyService.kt` owns foreground service lifecycle and native start/stop.
  - `ProxyController.kt` builds native start arguments from settings.
  - `ConnectionTab.kt` builds the Telegram proxy link.

## Android Integration Points

The Android candidate is amurcanov's Rust runtime plus a thin adapter around its
JNA ABI. The useful integration references are:

- `NativeProxy.kt`: shows the native call shape and string ownership pattern.
- `ProxyService.kt`: shows a foreground-service owner for the MTProto runtime.
- `ProxyController.kt`: shows how settings become native start arguments.
- `ConnectionTab.kt`: shows how an MTProto `t.me/proxy` link is formed.
- `build_so.bat` and `Cargo.toml`: define Rust/NDK build expectations and ABI targets.

Direct import is not ready as a drop-in replacement:

- the shared library name is also `libtgwsproxy.so`, which conflicts with this project's current Go runtime library;
- current app `NativeProxy.kt` maps the Go runtime ABI, not the amurcanov Rust ABI;
- current app defaults to SOCKS5, while amurcanov defaults to local MTProto;
- current app has route policies and status export that are richer than amurcanov's native ABI;
- Rust toolchain and `cargo-ndk` integration are not yet part of this project's build.

## Route Integration Hooks

Flowseal:

- No injectable dialer or route hook was found.
- Direct WSS calls `RawWebSocket.connect(target, domain, ...)`.
- Cloudflare proxy fallback calls `RawWebSocket.connect(domain, domain, ...)`.
- Cloudflare Worker fallback calls `RawWebSocket.connect(worker_domain, worker_domain, path=/apiws?dst=...&dc=...)`.
- TCP fallback calls `asyncio.open_connection(dst, 443)`.
- Route order is embedded in runtime code.

amurcanov:

- No injectable dialer or outbound route hook was found.
- Direct WSS uses `connect_direct_ws(target, domains, timeout)`.
- CF fallback is gated by `CFPROXY_ENABLED` and selected domain pool state.
- TCP fallback connects directly to the DC IP on port 443.
- No Cloudflare Worker route was found in the Rust runtime; search found no `cf_worker` / `workers.dev` route implementation.
- Current native ABI does not expose route-policy tokens, current route state, or per-route route backend callbacks.

Current TGWSProxyAndroid:

- Route kinds exist as `direct_ws`, `worker_ws`, `cf_proxy_ws`, and `tcp_fallback`.
- Policy tokens are produced by `ConnectionRuntimeConfig.kt`.
- The current Go runtime applies these policies through its own route selection path.
- No shared `RouteBackend` abstraction exists in the current source tree.

Conclusion: route integration is possible only after an adapter or new shared
backend interface is designed. It should not be claimed as already supported.

## License Compatibility

- Current TGWSProxyAndroid is GPLv3.
- amurcanov is GPLv3 and includes Flowseal MIT license text. GPLv3 is compatible with the current project license.
- Flowseal MIT code can be used as reference and can be incorporated into a GPLv3 project if attribution/license notices are preserved.
- Rust dependencies require a separate dependency license review before importing source or shipping binaries.
- Prebuilt upstream `.so` files should not be imported directly; a reproducible source build should be established first.

## Import Candidate

amurcanov's Rust runtime is the Android import candidate because it already has:

- Android-facing native ABI;
- foreground-service usage reference;
- Kotlin settings and Telegram link reference;
- Rust Android build script reference;
- current Flowseal-derived MTProto frontend behavior.

Flowseal should be used as the canonical behavior reference, not as the Android
runtime import, because it is Python desktop code without Android FFI.

## Risks

- LIMITED: route integration is not available as an upstream hook.
- LIMITED: amurcanov does not appear to implement the current app's `worker_ws` route.
- LIMITED: native build integration for Rust/cargo-ndk is not yet verified in this repository.
- LIMITED: Android ABI/name collision must be solved before importing any Rust shared library.
- LIMITED: status/diagnostics parity with current `GetProxyStatus()` does not exist in amurcanov's ABI.
- HIGH: replacing the default SOCKS5 runtime would break the branch goal; MTProto must stay separate and experimental.
- HIGH: importing prebuilt `.so` files would make provenance and reproducibility weak.
- HIGH: route behavior must not change without mobile and Wi-Fi manual testing across direct, worker, cf_proxy, and tcp.

## Decision

- [x] Use amurcanov Android runtime as candidate
- [x] Use Flowseal only as reference
- [ ] Use neither; implementation blocked

Status: LIMITED. Upstreams were downloaded and audited, but route integration
is not directly supported by the audited upstream APIs. The next phase should
prototype a separate experimental MTProto backend behind an adapter, without
touching the default SOCKS5/WS runtime path.

## v1.10.3 Runtime Import Gate

Local upstream recheck:

- `external/upstreams/flowseal-tg-ws-proxy`: clean working tree on `main`,
  up to date with `origin/main`, HEAD
  `e9b74d7d7dcc3beceab4d34d18d91b8ec9fdf098`.
- `external/upstreams/amurcanov-tg-ws-proxy-android`: clean working tree on
  `main`, up to date with `origin/main`, HEAD
  `caea7b2b11e0fce6665f970213acb765ca430f6b`.

Gate decision:

- Selected source remains the amurcanov Rust runtime.
- Flowseal remains reference-only Python desktop/server code.
- The upstream `old_src_golang/` core is not selected because the upstream
  README says the core was rewritten in Rust and Go is no longer maintained.
- License is not blocked: current project is GPLv3, amurcanov is GPLv3, and
  Flowseal MIT is compatible with attribution.
- Import is `READY_TO_IMPORT` only for a minimal, disabled/guarded v1.10.5
  native build probe. It is not approved for production runtime startup or
  route-integrated MTProto.
- Rust/cargo-ndk build integration is still unverified, the upstream build
  script uses a conflicting `libtgwsproxy.so` output name, and the Rust runtime
  has no route hook/shared route backend integration.

Status: READY_TO_IMPORT for build probe + LIMITED_ROUTE_INTEGRATION.
Do not import prebuilt binaries. Do not enable runtime startup until native build
and adapter loading are verified.
