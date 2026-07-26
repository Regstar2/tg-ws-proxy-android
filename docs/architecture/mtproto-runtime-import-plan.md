# MTProto Runtime Import Plan

## Selected source

Primary candidate: `amurcanov/tg-ws-proxy-android` Rust runtime.

Flowseal remains the behavior reference only. Its active runtime is Python
desktop/server code and has no Android JNI/FFI bridge.

The `old_src_golang/` source in `amurcanov/tg-ws-proxy-android` is not selected.
The upstream README states that the core was rewritten in Rust and the Go core
is no longer maintained.

The current TGWSProxyAndroid Go runtime is not a direct MTProto local-proxy
frontend. It already parses MTProto traffic after SOCKS5 CONNECT and owns the
current route backend, but it does not expose a compatible local MTProto
listener/start API. It should remain the default SOCKS5/WS backend.

## Source commit

- Flowseal/tg-ws-proxy: `e9b74d7d7dcc3beceab4d34d18d91b8ec9fdf098`
- amurcanov/tg-ws-proxy-android: `caea7b2b11e0fce6665f970213acb765ca430f6b`

Local recheck:

- `external/upstreams/flowseal-tg-ws-proxy`: clean working tree on `main`,
  up to date with `origin/main`.
- `external/upstreams/amurcanov-tg-ws-proxy-android`: clean working tree on
  `main`, up to date with `origin/main`.

## License impact

The current project is GPLv3. The amurcanov Android fork is GPLv3 and therefore
license-compatible with the current project license.

Flowseal is MIT. Flowseal source may be used as reference or incorporated into
the GPLv3 project with preserved MIT attribution/license notices.

Before shipping a Rust-based MTProto binary, perform a dependency license review
for the Cargo dependency graph from upstream `Cargo.lock`.

## Files/directories needed

The v1.10.5 native build probe may import a minimal Rust runtime source set into
a dedicated project-owned vendor location, not into production Go runtime files:

- `Cargo.toml`
- `Cargo.lock`
- `src/lib.rs`
- `src/proxy.rs`
- `src/ws.rs`
- `src/crypto.rs`
- `src/cfproxy.rs`
- `src/config.rs`
- `src/balancer.rs`

Reference-only Android/Kotlin files:

- `app/src/main/java/com/amurcanov/tgwsproxy/NativeProxy.kt`
- `app/src/main/java/com/amurcanov/tgwsproxy/ProxyService.kt`
- `app/src/main/java/com/amurcanov/tgwsproxy/ProxyController.kt`
- `app/src/main/java/com/amurcanov/tgwsproxy/ui/ConnectionTab.kt`

Reference-only build file:

- `build_so.bat`

Current probe destination:

- `third_party/mtproto-runtime/` for the selected Rust source snapshot;
- local patch metadata in `third_party/mtproto-runtime/patches/`;
- no copied prebuilt `.so` files.

Recommended future production destination, after a successful build spike:

- `native/mtproto-rust/` for Rust source;
- a separate shared library name such as `libtgwsmtproto.so`;
- a Kotlin adapter such as `MtProtoNativeProxy` behind
  `LocalProxyFrontendType.MTPROTO_EXPERIMENTAL`.

v1.10.4 note: the Android adapter boundary now exists as
`MtProtoRuntimeAdapter`, `MtProtoRuntimeConfig`, `MtProtoRuntimeConfigMapper`,
and `UnsupportedMtProtoRuntimeAdapter`. It is adapter-only and lets a build
probe fail closed without enabling a fake runtime.

## Files/directories explicitly not imported

- Flowseal Python runtime (`proxy/`, `ui/`, `utils/`, desktop launchers).
- Full upstream Android app UI, Gradle project, resources, and settings store.
- `old_src_golang/` from the amurcanov fork.
- Any prebuilt `.so` artifacts.
- Upstream `local.properties`.
- Upstream Gradle files in this gate version.
- Current production `native/tgwsproxy/` files.

## Build requirements

The selected source requires:

- Rust toolchain with Cargo;
- Android NDK;
- `cargo-ndk`;
- Rust Android targets, at least:
  - `aarch64-linux-android`;
  - `armv7-linux-androideabi`;
- Gradle integration that builds Rust before APK packaging;
- deterministic output into Android `jniLibs`;
- a non-conflicting shared library name.

Upstream `build_so.bat` is not directly usable as a project build step. It
contains a placeholder SDK path, installs `cargo-ndk` imperatively, builds only
two ABIs, and writes `libtgwsproxy.so`, colliding with the current Go runtime
library name.

This no longer blocks importing a minimal, guarded source snapshot for the
v1.10.5 build probe. It still blocks enabling a production MTProto runtime until
the probe produces a reproducible non-conflicting Android build.

## Android integration method

Use the existing frontend abstraction from v1.10.1:

- keep `SOCKS5` as default;
- add a future `MtProtoLocalProxyFrontend` implementation only after the native
  Rust library is reproducibly built;
- load the MTProto library through a separate Kotlin/JNA or JNI adapter;
- keep the current `NativeProxy` Go ABI untouched;
- fail closed if the MTProto native library is absent or the start call returns
  an error.

This should be an adapter/wrapper import, not a wholesale upstream Android app
merge.

## JNI/FFI/API surface

Upstream Rust exports these relevant C ABI functions:

- `StartProxy(host, port, dcIps, secret, verbose): int`
- `StopProxy(): int`
- `SetPoolSize(size)`
- `SetCfProxyCacheDir(cacheDir)`
- `SetCfProxyConfig(enabled, priority, userDomain)`
- `SetSecret(secret)`
- `GetStats(): char*`
- `GetSecretWithPrefix(): char*`
- `FreeString(char*)`

Upstream Android uses JNA in `NativeProxy.kt` and loads a library named
`tgwsproxy`. A future import must rename the library and expose a separate
adapter so it cannot collide with the current Go `NativeProxy`.

## Runtime start/stop API

Candidate start mapping:

- host: `MtProtoProxyConfig.host`
- port: `MtProtoProxyConfig.port`
- secret: `MtProtoProxyConfig.secret`
- verbose: current diagnostics/logging setting or fixed experimental verbose
  value
- dcIps: generated from the future MTProto DC override settings, not from the
  current SOCKS5 runtime token string

Candidate stop mapping:

- `StopProxy()` on frontend stop/service stop;
- parse `GetStats()` only as a limited MTProto status source until a structured
  status API exists.

Known return codes from source:

- `0`: started;
- `-1`: already running;
- `-3`: bind/listen failure.

## Config mapping

Already available in current project:

- `LocalProxyFrontendType.MTPROTO_EXPERIMENTAL`
- `MtProtoProxyConfig.host`
- `MtProtoProxyConfig.port`
- `MtProtoProxyConfig.secret`
- `MtProtoProxyConfig.enabled`
- `MtProtoProxyConfig.experimentalAcknowledged`

Not yet available:

- MTProto DC override model compatible with upstream `dcIps`;
- CF proxy domain/cache mapping for the Rust runtime;
- route policy mapping for direct, CF proxy, Worker, and TCP;
- structured MTProto status mapping for diagnostics.

The current `ConnectionRuntimeConfig` token string must not be passed directly
to the Rust `dcIps` argument. It is a Go SOCKS5 runtime config, not an MTProto
runtime config.

## Logging mapping

Upstream Rust logs to Android log tag `TgWsProxy` and stderr-like output through
its internal macros. A future adapter should:

- prefix MTProto logs so they can be distinguished from SOCKS5 runtime logs;
- sanitize secrets before persistent storage or report export;
- avoid logging the raw MTProto secret or generated `tg://proxy` link with full
  secret.

## Route hook availability

No injectable dialer, outbound connection hook, route callback, or shared route
backend API was found in the selected Rust runtime.

Observed route behavior is internal to Rust:

- direct WSS via Telegram `kws*.web.telegram.org`;
- Cloudflare proxy fallback controlled by Rust global config;
- TCP fallback to configured/default DC IP;
- no Cloudflare Worker route implementation found;
- no route-policy token parser compatible with the current
  `NetworkRoutePolicy`/`ConnectionRuntimeConfig` model.

This blocks route-integrated import. It does not block a guarded native build
probe because v1.10.5 does not enable route selection, UI, or worker-pool
integration. Any local-only MTProto experimental backend must be explicitly
documented as route-limited and must not claim parity with the current SOCKS5/WS
backend.

## Risks

- HIGH: shared library name collision with current Go `libtgwsproxy.so`.
- HIGH: no verified Rust/cargo-ndk Gradle build integration in this repository.
- HIGH: no route hook or shared route backend integration.
- HIGH: no Cloudflare Worker route in the selected Rust runtime.
- MEDIUM: upstream `GetStats()` is a text summary, not structured diagnostics
  parity with current `GetProxyStatus()`.
- MEDIUM: Rust dependency licenses still need a Cargo dependency review before
  shipping.
- MEDIUM: upstream build script covers only two ABIs and hardcodes SDK/NDK path
  discovery.
- LOW: Flowseal Python behavior remains useful as reference, but is not an
  Android import candidate.

## Decision

- [x] READY_TO_IMPORT
- [ ] BLOCKED_LICENSE
- [ ] BLOCKED_BUILD_SYSTEM
- [ ] BLOCKED_NO_RUNTIME_HOOK
- [ ] REFERENCE_ONLY

Decision: ready to import a minimal, disabled/guarded Rust source snapshot for
the v1.10.5 native build probe only.

This does not make MTProto route-integrated or production-ready. Route
integration remains `LIMITED_ROUTE_INTEGRATION` until a shared route backend,
route hook, or explicit local-only product decision exists.

If Rust, Android NDK, `cargo-ndk`, or Android targets are unavailable, v1.10.5
must stop as `BLOCKED_NATIVE_TOOLCHAIN`, keep Gradle/native integration disabled
or guarded, and keep the adapter on `UNSUPPORTED` / `NATIVE_LIBRARY_MISSING`.
