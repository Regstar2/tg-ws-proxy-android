# MTProto Go Viability

## Sources checked

- `external/upstreams/flowseal-tg-ws-proxy`
- `external/upstreams/amurcanov-tg-ws-proxy-android`
- `native/tgwsproxy`

## Commits

- Flowseal/tg-ws-proxy:
  `e9b74d7d7dcc3beceab4d34d18d91b8ec9fdf098`
- amurcanov/tg-ws-proxy-android:
  `caea7b2b11e0fce6665f970213acb765ca430f6b`

## Relevant Go files

Upstream legacy Go reference:

- `external/upstreams/amurcanov-tg-ws-proxy-android/old_src_golang/tg-ws-proxy.go`

Relevant pieces found there:

- local MTProto listener: `runProxy`
- Android-facing start/stop: `StartProxy`, `StopProxy`
- secret handling: `proxySecret`, `SetSecret`, `GetSecretWithPrefix`
- MTProxy client handshake: `handleClient`
- init decrypt and DC/media extraction: `handleClient`
- relay init generation: `handleClient`
- MTProto packet splitting: `MsgSplitter`, `newMsgSplitter`
- direct WebSocket route: `connectDirectWS`
- Cloudflare proxy fallback: `cfproxyFallback`
- TCP fallback: `tcpFallback`

Current project Go runtime:

- `native/tgwsproxy/tg-ws-proxy.go`
- `native/tgwsproxy/init_session.go`
- `native/tgwsproxy/routing.go`
- `native/tgwsproxy/worker_reencrypt.go`
- `native/tgwsproxy/tgwsroute/route.go`

Relevant pieces already present:

- SOCKS5 listener and current Android CGO exports.
- route policy parsing through `parseRuntimeConfig`.
- route chain and policy filtering through `runRouteChain`.
- direct WS, CF proxy, Worker, and TCP fallback routes.
- `dcFromInit`, `patchInitDC`, `MsgSplitter`, and init/session metadata.
- `bridgeWSMeta.upTransform` / `downTransform`, currently used for Worker
  re-encryption.
- Worker URL/path building and destination mapping.

## Relevant Rust files used only as reference

- `external/upstreams/amurcanov-tg-ws-proxy-android/src/lib.rs`
- `external/upstreams/amurcanov-tg-ws-proxy-android/src/proxy.rs`
- `external/upstreams/amurcanov-tg-ws-proxy-android/src/crypto.rs`

The Rust runtime confirms the current upstream direction and mirrors the legacy
Go concepts, but it is not selected for v1.10.5 implementation work.

## Telegram docs checked

- https://core.telegram.org/proxy
- https://core.telegram.org/mtproto
- https://core.telegram.org/mtproto/description
- https://core.telegram.org/mtproto/transports
- https://core.telegram.org/mtproto/mtproto-transports
- https://core.telegram.org/api/links
- https://core.telegram.org/tdlib/docs/classtd_1_1td__api_1_1proxy_type_mtproto.html

Relevant confirmations:

- MTProto proxies require server, port, and secret.
- MTProxy links use `t.me/proxy?server=<server>&port=<port>&secret=<secret>`
  and `tg://proxy?server=<server>&port=<port>&secret=<secret>`.
- TDLib models MTProto proxy secrets as hexadecimal strings.
- MTProxy obfuscation uses a 16-byte secret; a 17-byte secret variant can carry
  a transport selector byte such as `dd` for padded intermediate.
- WebSocket transport requires `Sec-WebSocket-Protocol: binary` and treats
  WebSocket frames as a byte stream; MTProto transport framing still owns packet
  boundaries.

## Existing Go MTProto pieces

Current project Go runtime already contains several MTProto-adjacent pieces:

- `dcFromInit`: extracts protocol/DC/media from an already obfuscated Telegram
  init packet.
- `patchInitDC`: updates DC bytes in an obfuscated init.
- `MsgSplitter`: splits encrypted MTProto payloads before sending over
  WebSocket.
- `workerReencryptContext`: generates a relay init and transforms client/relay
  byte streams for Worker media fixes.
- `runRouteChain`: dispatches direct, Worker, CF proxy, and TCP fallback routes
  under the current policy model.

The upstream legacy Go runtime contains the pieces the current project lacks:

- MTProxy listener that accepts Telegram MTProxy clients directly.
- secret-derived AES-CTR decrypt/encrypt setup for client-side MTProxy traffic.
- relay init generation for the outbound Telegram side.
- bidirectional stream transform between client-secret obfuscation and relay
  obfuscation.

## Missing Go MTProto pieces

- A separate local MTProto listener/frontend selected only by
  `LocalProxyFrontendType.MTPROTO_EXPERIMENTAL`.
- A small MTProxy handshake module adapted from upstream reference code.
- A Go runtime config surface for MTProto host/port/secret that does not reuse
  the SOCKS5 route token string as secret or DC config.
- A route-session model that can carry:
  - the outbound relay init;
  - logical DC/media;
  - client-to-relay transform;
  - relay-to-client transform.
- TCP fallback transform support. `bridgeWS` already has transform hooks;
  `bridgeTCP` currently does not.
- Tests for generated relay init, secret validation, DC/media extraction,
  transform round-trip, and route dispatch without touching SOCKS5.
- Android/JNA adapter that calls a real Go MTProto entry point only after the Go
  frontend exists.

## Required changes for TGWSProxyAndroid

Recommended v1.10.6 implementation shape:

- Add Go files under `native/tgwsproxy/`, for example:
  - `mtproxy_frontend.go`
  - `mtproxy_handshake.go`
  - `mtproxy_session.go`
  - `mtproxy_frontend_test.go`
- Keep existing `StartProxy` for SOCKS5 untouched.
- Add a separate internal Go start path for MTProto; expose it to Android only
  after tests pass.
- Reuse current route kinds and route policy logic.
- Add explicit stream-transform support for all routes rather than duplicating
  route code.
- Keep MTProto disabled by default and fail closed through the existing adapter
  until the frontend is complete.

## Integration points

- Android domain/config: `MtProtoProxyConfig` and
  `MtProtoRuntimeConfigMapper`.
- Frontend selection: `LocalProxyFrontendType.MTPROTO_EXPERIMENTAL`.
- Runtime adapter: `MtProtoRuntimeAdapter`.
- Go route backend: `runRouteChain`, `cfWorkerFallback`,
  `cfProxyFallbackWithPool`, `tcpFallback`, `bridgeWS`, `bridgeTCP`.
- Diagnostics: existing secret masking and route diagnostics can be extended
  later, but no report should include a full MTProto secret.

## Risks

- HIGH: importing old Go wholesale would fork a second runtime and bypass recent
  route-policy/worker-pool fixes.
- HIGH: a wrong client-secret transform would produce a proxy that starts but
  corrupts Telegram traffic.
- HIGH: route changes require manual mobile and Wi-Fi tests for direct, Worker,
  CF proxy, and TCP.
- MEDIUM: `ee` fake TLS is supported by Flowseal but not selected for the
  minimal Go path.
- MEDIUM: TCP fallback needs transform hooks before MTProxy can use it.
- MEDIUM: old upstream Go is legacy; use it as reference, not as a blind copy.
- LOW: license is compatible because the current project and amurcanov fork are
  GPLv3.

## Decision

Status: GO_MTPROTO_VIABLE

Reason:

Sufficient Go/reference code exists to implement a local MTProto frontend
without rewriting the entire MTProto protocol from scratch. The current Go
runtime already owns the route backend, so a Go-native frontend is the most
direct way to preserve SOCKS5/WS as default while adding MTProto as an
experimental separate frontend.

Next version:

v1.10.6 - Go MTProto Frontend Skeleton

- adapt the upstream Go/Rust/Flowseal handshake references into isolated Go
  functions;
- add tests before wiring startup;
- add stream-transform support to route bridges;
- keep `MTPROTO_EXPERIMENTAL` unsupported until the skeleton passes focused
  tests.
