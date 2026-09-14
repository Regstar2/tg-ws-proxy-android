# TgWsProxy Android v1.10.14

Release metadata: `versionName 1.10.14`, `versionCode 52`.

This release stabilizes the MTProto Worker path investigated in Issue #29 and replaces the failing long-lived `workers.dev` WebSocket data path with a short fresh-HTTPS chunk relay.

## Worker update required

If you used the Worker route with an earlier TgWsProxy version, updating the Android APK alone is not enough.

For v1.10.14, redeploy the Worker using the current chunk-relay code and Durable Object configuration from this repository:

- `scripts/cloudflare-worker/chunk-relay-status-worker.js` — current Worker entry point;
- `scripts/cloudflare-worker/wrangler.chunk-relay.jsonc` — current `CHUNK_RELAY` / `ChunkRelaySession` Durable Object configuration.

The previous long-lived WebSocket Worker transport is not the v1.10.14 MTProto Worker data path.

For regular use, deploying **multiple Worker instances/domains** and adding them to the TgWsProxy Worker pool is recommended. `ROUND_ROBIN` selection remains sticky per MTProto session, so one live session stays on one Worker while independent sessions can be distributed across the pool. Per-domain quota circuit breaking can temporarily skip an exhausted Worker for new sessions.

Deployment and architecture guide: [`docs/cloudflare-worker.md`](../cloudflare-worker.md).

## Worker transport

- MTProto Worker traffic uses bounded fresh HTTPS requests while a Cloudflare Durable Object keeps the corresponding Telegram TCP stream alive.
- Upload uses the verified `12 KiB / window=3` profile with ordered sequence acknowledgements, bounded retries, primary/global request limits and HOL hedging for the oldest unacknowledged upload.
- Worker revision `chunk-relay-mtproto-v10` uses a shared `relay-hub-v1` Durable Object so several independent MTProto sessions share one DO instance while keeping separate Telegram sockets, ACK/reorder state, downstream queues and lifecycle state.
- Relay session cleanup tracks client activity separately from Telegram payload activity. Orphaned sessions are reaped after the 60 s timeout plus 5 s scheduling grace without closing healthy idle sessions that continue polling.
- `ROUND_ROBIN` selection is sticky per opaque MTProto session id: one session stays on one Worker, while separate sessions can be distributed across the enabled pool.
- Durable Objects quota exhaustion is returned as recoverable HTTP 503 with quota-reset metadata. Native routing opens a per-domain circuit breaker and skips the exhausted Worker for new sessions until reset.
- Relay failures are status-aware and expose safe machine-readable error classes. Terminal session loss reconnects cleanly; transient failures remain bounded-retryable; non-success responses cannot win retry/hedge races merely because the transport call returned no local error.

## Downstream efficiency and stability

- Adaptive downstream long polling uses 6 s while active, 12 s after three consecutive empty polls and 20 s after eight empty polls, returning immediately to 6 s when payload arrives.
- Worker downstream payloads are coalesced for up to 8 ms to produce real responses up to the 12 KiB ceiling instead of forwarding each small TCP read as a separate HTTP response.
- The native client limits reading an already-started HTTP 200 downstream body to 2500 ms. A stalled body is closed and the same ACK is retried through the fresh-request path instead of waiting for the full 10/16/24 s long-poll deadline.
- The rejected HTTP/1.1 keep-alive experiment is not included. Device A/B showed repeated transport errors and MTProto churn; v1.10.14 keeps fresh TCP/TLS requests.

## Acceptance evidence

- Normal Telegram connection, messages and media were verified on Android through the Worker path.
- The `12 KiB / window=3` upload scheduler is covered by regression tests and on-device transfer testing.
- Shared RelayHub acceptance exercised concurrent sessions and repeated multi-MiB transfers without a sustained-throughput regression; observed Durable Objects duration pressure dropped substantially compared with the previous per-session DO model.
- Orphan reaper acceptance confirmed healthy polling sessions survive the timeout boundary and abandoned sessions are reaped after the configured grace period.
- 12 KiB downstream coalescing produced predominantly ~12 KiB payload responses, materially improving throughput and reducing requests per MiB compared with the previous small-read behavior.
- The 2500 ms HTTP 200 body deadline recovered from real downstream stalls without visible sequence corruption or media-session teardown.
- Worker quota/circuit failover and reconnect-storm protection were verified during the Issue #29 stabilization work.

## CI and regression coverage

Pull-request CI includes native Go tests with race detection, Cloudflare Worker Node tests, Android unit tests, debug APK assembly and release-readiness checks. The final release branch must pass CI again after the `1.10.14 / 52` metadata bump.

## Known limitations

- Worker transport remains slower than direct connectivity and is not promoted to the default route.
- Fresh HTTPS requests intentionally trade raw throughput for reliability on networks where a long-lived `workers.dev` connection stalls.
- Cloudflare Durable Objects quotas remain an operational constraint even though the shared hub and orphan cleanup substantially reduce quota pressure.

## Release status

Final Android device smoke testing passed. Telegram connection, messages and media were verified through the Worker path. Issues #29 and #33 are closed and PR #52 has been merged.

Worker transport is functional in v1.10.14, with lower throughput than direct connectivity remaining a known limitation.
