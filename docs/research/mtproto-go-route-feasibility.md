# MTProto Go Route Integration Feasibility

## Existing SOCKS5/WS route pipeline

The production Go runtime enters routing from the SOCKS5 client handler in
`native/tgwsproxy/tg-ws-proxy.go`:

1. The handler parses the SOCKS5 CONNECT destination and port.
2. It reads the 64-byte Telegram obfuscated init packet.
3. `newInitSession` combines the requested destination with DC/media metadata
   extracted by `dcFromInit` or inferred from the destination IP.
4. The handler either attempts direct WebSocket or calls `runRouteChain`.
5. `runRouteChain` dispatches `cf_worker_ws`, `cf_proxy_ws`, and
   `tcp_fallback` under the current route policy. Direct WebSocket is handled
   by the caller.

The route backend is therefore real and working, but its entry point is a
concrete, package-private function coupled to the SOCKS5-derived `initSession`.
It is not an injectable dialer or stream factory.

Current route coverage:

- `direct_ws`: selected and connected in the SOCKS5 client handler.
- `cf_worker_ws`: selected by `runRouteChain` and implemented by
  `cfWorkerFallback`.
- `cf_proxy_ws`: selected by `runRouteChain` and implemented by
  `cfProxyFallbackWithPool`.
- `tcp_fallback`: selected by `runRouteChain` and implemented by
  `tcpFallback`.
- Auto/fallback policy: implemented by route ordering, filtering, cooldown,
  and adaptive state in `routing.go` and `tgwsroute`.
- Worker pool/failover: implemented inside the current Go runtime and remains
  specific to the existing route path.

## MTProto outbound flow

The experimental runtime in `native/tgwsproxy/mtproxyfrontend/runtime.go`
currently:

1. Validates host, port, and raw 16-byte/32-hex secret.
2. Opens the local TCP listener.
3. Accepts a connection.
4. Closes it immediately with `OUTBOUND_UNSUPPORTED`.

It does not yet read or decrypt the 64-byte MTProxy client init. It therefore
does not produce the data required by the route backend.

The audited Flowseal, amurcanov Rust, and amurcanov legacy Go implementations
all perform additional work before outbound routing:

- decrypt the client init using the configured proxy secret;
- validate and identify the MTProto transport;
- extract the signed DC ID and media flag;
- generate a separate relay init for Telegram;
- create persistent client-to-relay and relay-to-client AES-CTR transforms;
- resolve the target DC host/IP and port;
- pass the relay init and transforms into the selected outbound bridge.

The official Telegram transport documentation confirms that the DC ID is
encoded at offset 60 in the obfuscated initialization payload for MTProxy
connections, and that the AES-CTR state must be reused for the connection.
These values cannot be guessed or reconstructed by the route selector.

Sources:

- https://core.telegram.org/proxy
- https://core.telegram.org/mtproto/mtproto-transports
- `external/upstreams/flowseal-tg-ws-proxy/proxy/tg_ws_proxy.py`
- `external/upstreams/flowseal-tg-ws-proxy/proxy/bridge.py`
- `external/upstreams/amurcanov-tg-ws-proxy-android/src/proxy.rs`
- `external/upstreams/amurcanov-tg-ws-proxy-android/old_src_golang/tg-ws-proxy.go`

## Available metadata

Available in the current SOCKS5/WS path:

- requested Telegram destination host/IP and port;
- DC ID and media flag from init parsing or IP mapping;
- original obfuscated init packet;
- selected route and fallback events;
- worker session ID and worker destination diagnostics;
- route success/failure and byte counters.

Available in the current MTProto frontend:

- local bind host and port;
- secret fingerprint;
- connection count;
- local listener state.
- validated target DC ID and media flag from the client handshake;
- MTProto transport type;
- generated relay init;
- stateful client-to-relay and relay-to-client transforms;
- selected/actual backend, fallback flag, and route reason.

Still missing:

- integration with the shared SOCKS5 route policy;
- direct WS, Worker, and CF proxy adapters;
- fallback event ownership beyond the explicit no-fallback direct TCP backend.

## Candidate hook

`mtproxyfrontend.OutboundConnector` now accepts a DC-aware request containing:

- DC ID and media flag;
- target host/IP and port;
- transport type;
- relay init;
- bidirectional stream transforms;
- bidirectional stream transforms.

The boundary is connected to one main-package adapter:

- selected backend: `direct_tcp`;
- target: the existing canonical IP for the extracted DC, port 443;
- first write: generated relay init;
- stream bridge: client-secret traffic is transformed into the relay stream in
  both directions;
- fallback: disabled and reported as `false`.

The adapter has a real producer and consumer. A local end-to-end test exercises
listener, handshake, connector, both transforms, and response delivery.

## Missing hook

The remaining missing hook is between `OutboundConnector` and the shared
SOCKS5 route policy. The first backend intentionally bypasses `runRouteChain`
and selects only direct TCP, so it cannot silently enter Worker, CF proxy, or
TCP fallback.

Required future change:

1. Complete manual Telegram tests for direct TCP on mobile and Wi-Fi.
2. Add one transform-capable WebSocket adapter before considering shared route
   policy integration.
3. Define fallback stream-state rules before enabling more than one backend.
4. Keep Worker pool and CF proxy unchanged until their adapters have focused
   tests.

## Risks

- HIGH: treating the client MTProxy init as the existing relay init would send
  secret-derived client traffic to Telegram unchanged and corrupt the stream.
- HIGH: resetting or cloning AES-CTR state at the wrong point breaks all
  subsequent packets.
- HIGH: routing without a validated DC can connect to the wrong Telegram
  endpoint.
- MEDIUM: direct WS, CF proxy, Worker, and TCP currently have different first
  packet and transform behavior.
- MEDIUM: retry/fallback must recreate or preserve stream state deliberately;
  it cannot reuse partially consumed transforms blindly.
- LOW: the direct TCP adapter has no fallback and does not alter the stable
  SOCKS5/WS route selector.

## Decision

Status: GO_ROUTE_HOOK_LIMITED

The Go MTProto frontend now produces a real DC-aware outbound request and one
direct TCP adapter consumes it. The hook is limited because it does not yet
enter the shared route policy and no alternate backend/fallback is enabled.
SOCKS5/WS behavior remains unchanged.
