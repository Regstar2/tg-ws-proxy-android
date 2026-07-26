# MTProto Runtime Decision

## Decision

Use the existing Go runtime as the primary MTProto implementation path.

Status: GO_MTPROTO_VIABLE

Rust remains a fallback/reference candidate, not the next implementation step.

## Rationale

- The current production route backend is already in Go.
- The current Android app already builds and loads `libtgwsproxy.so` from Go.
- The current route backend has direct WS, Worker WS, CF proxy WS, TCP fallback,
  policy filtering, diagnostics, and route state.
- Upstream legacy Go plus Flowseal/Rust references provide enough MTProxy
  handshake and stream-transform behavior to avoid a from-scratch protocol
  rewrite.
- Avoiding Rust/cargo-ndk avoids the native toolchain blocker and avoids a
  second native library lifecycle.

## Non-Goals

- Do not replace the default SOCKS5/WS frontend.
- Do not import the full old Go runtime as a parallel production runtime.
- Do not enable MTProto startup until a real Go frontend exists.
- Do not implement `ee` fake TLS in the first Go frontend skeleton.
- Do not claim route parity until direct, Worker, CF proxy, and TCP are tested
  on mobile and Wi-Fi.

## Architecture Direction

```text
Telegram app
    -> local frontend
        -> SOCKS5 frontend (default, existing)
        -> MTProto frontend (experimental, future)
            -> Go route backend
                -> direct_ws | cf_worker_ws | cf_proxy_ws | tcp_fallback
```

The MTProto frontend should produce a route session containing:

- logical DC and media flag;
- outbound relay init;
- client-to-relay stream transform;
- relay-to-client stream transform;
- masked diagnostic fields.

The route backend should remain shared. Route kind is still not transport.

## Required Backend Changes

- Add isolated MTProxy handshake/secret parsing functions.
- Add a separate Go MTProto listener lifecycle.
- Add transform support to all route bridges, including TCP fallback.
- Reuse `runRouteChain` rather than duplicating route selection.
- Add tests before Android startup calls a real MTProto entry point.

## Rust Position

Rust is not rejected as source material. It remains useful as reference and as a
possible fallback if Go becomes blocked.

Rust is not selected for the next implementation because:

- the local Rust toolchain was unavailable in the previous native probe;
- the Rust shared library name collides with the current Go runtime unless
  patched;
- Rust route integration would still need adapter work to reach the current Go
  route backend;
- a Go frontend can use the existing route backend directly.

## Compatibility

- Default frontend remains SOCKS5.
- Existing Android `NativeProxy` ABI remains the SOCKS5/WS path.
- MTProto config remains disabled by default.
- Missing or incomplete MTProto runtime must return `UNSUPPORTED` or
  `NATIVE_LIBRARY_MISSING`, not fake success.

## Next Version

v1.10.6 should implement only a tested Go MTProto frontend skeleton:

- no UI polish;
- no route behavior changes for SOCKS5;
- no Rust build integration;
- tests first for handshake, secret, relay init, transforms, and route dispatch.
