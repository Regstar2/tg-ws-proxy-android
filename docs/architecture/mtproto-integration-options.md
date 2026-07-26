# MTProto Integration Options

## Option A - import Android native runtime

Use amurcanov's Rust `cdylib` as the candidate MTProto runtime and wrap it
behind a project-local adapter.

Expected shape:

- keep the current SOCKS5/WS Go runtime as the default backend;
- build the Rust runtime from source instead of copying upstream `.so` files;
- give the MTProto library a distinct library name to avoid colliding with the existing `libtgwsproxy.so`;
- expose only a small internal adapter API for start, stop, stats, secret/link, and configuration;
- run it on a separate local MTProto port, likely `127.0.0.1:1443`, while SOCKS5 remains on the current default port;
- keep user-visible strings in Android resources when UI is added in a later version.

Pros:

- real upstream MTProto implementation;
- Android ABI and service lifecycle already exist as reference;
- aligns with the requirement to add a separate experimental frontend/backend.

Cons:

- requires Rust, NDK, and cargo-ndk build integration;
- current app native bridge targets the Go runtime ABI, not the Rust ABI;
- no route hook exists for current route policy integration;
- diagnostics/status parity would need adapter work.

Status: recommended candidate, but LIMITED until the native build and adapter
are verified.

## Option B - wrap existing current runtime if it already supports MTProto

The current runtime already contains MTProto-aware pieces inside the SOCKS5/WS
bridge path, but the product frontend is still SOCKS5. No local MTProto proxy
listener compatible with Telegram `tg://proxy` / `t.me/proxy` links is exposed
as a supported backend.

Pros:

- stays in Go and keeps the existing build/toolchain;
- can reuse current route policies, adaptive stats, worker pool, and diagnostics if the frontend can be added cleanly.

Cons:

- as-is, it does not provide the separate MTProto frontend requested for v1.10.x;
- turning internal bridge helpers into a public MTProto listener would touch high-risk runtime code;
- this path still requires mobile and Wi-Fi route testing across direct, worker, cf_proxy, and tcp.

Status: UNSUPPORTED as a drop-in option. It may become viable only after a
dedicated Go MTProto frontend is designed and tested.

## Option C - keep MTProto as local-only experimental mode

Introduce MTProto as a local experimental mode with its own backend, link, port,
status, and stop/start lifecycle. Do not connect it to the current route policy
system in the first implementation step.

Pros:

- smallest behavioral blast radius;
- keeps SOCKS5/WS default path unchanged;
- allows testing the real MTProto frontend before route-policy coupling;
- easier to mark as experimental in UI and diagnostics.

Cons:

- route behavior would initially be limited to the upstream runtime's own direct/CF/TCP choices;
- current `worker_ws` route is not available in amurcanov upstream;
- status and diagnostics would be less complete than the current Go backend.

Status: safest first implementation path after the audit, provided the Rust
runtime can be built reproducibly.

## Option D - route integration through shared RouteBackend

Create a shared internal route backend interface used by both SOCKS5 and
MTProto frontends. The frontend would parse client protocol state, then ask the
shared backend to select and open `direct_ws`, `worker_ws`, `cf_proxy_ws`, or
`tcp_fallback`.

Pros:

- unifies route policy, adaptive stats, worker pool, and diagnostics;
- avoids duplicating route behavior between SOCKS5 and MTProto modes;
- gives a long-term architecture for multiple frontends.

Cons:

- no `RouteBackend` abstraction exists in the current source tree;
- upstream MTProto runtimes do not expose an injectable dialer/outbound hook;
- requires careful boundaries between UI, domain, data, and infrastructure;
- high test cost because route behavior changes are network-sensitive.

Status: long-term target, not a v1.10.0 implementation step.

## Rejected options

- Fake MTProto implementation: rejected because it would not be a real Telegram MTProto proxy.
- Replace the default SOCKS5/WS runtime with MTProto: rejected because SOCKS5/WS must remain the default working mode.
- Copy upstream prebuilt `.so` files: rejected until there is a reproducible source build and license/dependency review.
- Modify upstream code directly inside `external/upstreams`: rejected because upstream clones are audit inputs only.
- Treat `websocket` as a route kind: rejected; it is a transport, while route kinds remain `direct_ws`, `worker_ws`, `cf_proxy_ws`, and `tcp_fallback`.

## Recommended path

1. Keep v1.10.0 as audit/documentation only.
2. Use Flowseal as behavior reference.
3. Use amurcanov's Rust Android runtime as the import candidate.
4. Prototype a separate internal `MtprotoBackend` adapter that can start/stop an experimental local MTProto runtime without touching the default SOCKS5/WS path.
5. Verify Rust/NDK/cargo-ndk builds from source and avoid importing prebuilt `.so` files.
6. Add route-policy integration only after a shared route backend or explicit outbound hook is designed.
7. Before any route behavior change, run manual tests on mobile and Wi-Fi for direct, worker, cf_proxy, and tcp.

Current status: LIMITED. Upstream source acquisition succeeded, but direct route
integration is not supported by audited upstream hooks.
