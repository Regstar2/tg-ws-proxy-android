# TgWsProxy Android v1.10.14 — release candidate

This release candidate stabilizes the working MTProto Worker transport from the Issue #29 investigation without merging the full experimental commit chain into `main`.

## What is included

- Fresh HTTPS chunk relay for MTProto Worker traffic. The Android/native side sends bounded short HTTPS requests while a Cloudflare Durable Object keeps the corresponding Telegram TCP stream.
- Shared Durable Object relay hub (`relay-hub-v1`): multiple independent MTProto sessions now share one Durable Object instance while retaining separate Telegram sockets, ACK/reorder state, downstream queues, backpressure, and lifecycle state.
- Stabilized upload profile: 12 KiB chunks, sliding window 3, ordered ACKs, bounded retry/backoff, HOL hedge for the oldest unacknowledged upload, and downstream queue backpressure.
- Adaptive downstream long polling per MTProto session: active sessions use 6 s polls, three consecutive empty polls raise the wait to 12 s, eight raise it to 20 s, and the first valid payload immediately returns the session to 6 s. The native relay logs empty/payload poll counts, effective average wait, retries, timeouts, requests per MiB, and estimated idle requests per minute for A/B measurements.
- Sticky Worker pool for `ROUND_ROBIN`: a stable opaque MTProto session id selects one primary Worker for the lifetime of that session. Chunks from one session are never split between Worker hosts.
- Quota-aware Worker failover. Worker v9 keeps the v7 behavior that converts Cloudflare Durable Objects Free Tier duration exhaustion into a recoverable 503 response with reset metadata and raises the downstream long-poll ceiling to 20 s while retaining payload wakeups.
- Hub diagnostics expose the relay/hub revisions and log active/opened/closed/reaped session counts plus per-session cumulative byte counters without logging payloads or opaque session ids.
- Short cooldown for unclassified Worker 5xx responses to prevent reconnect storms while still allowing automatic recovery.
- Worker preconnect disabled for the MTProto Worker route and strict preservation of explicit Worker-only route policy.
- Go race tests and Worker handler tests in pull-request CI.

## Device evidence before stabilization PR

The source checkpoint used for this release candidate is `c59f860` from PR #51. On-device testing confirmed that a v7 Worker with exhausted Durable Objects quota was automatically removed from the candidate set and new MTProto sessions failed over to a healthy v7 Worker. The healthy Worker carried bidirectional MTProto traffic and multi-megabyte uploads through the chunk relay.

The shared v8 Durable Object hub is covered by Worker unit tests, but its real Cloudflare duration reduction and A/B throughput still require deployment/device verification. The adaptive v9 downstream polling profile is also unit-tested but still requires the direct `*.workers.dev` network A/B from Issue #55 before release acceptance.

## Known limitations

- The Worker route is currently slower than direct connectivity and still produces HOL hedges/timeouts under sustained bulk traffic.
- Cloudflare Durable Objects quotas remain an operational constraint. The shared hub reduces concurrent Durable Object instances by design, but the actual billed-duration improvement is not yet measured.
- Some relay sessions can still terminate with HTTP 502 and require further error classification and transport tuning.
- The Worker route remains non-default. This PR is a stabilization baseline for further work, not a claim that Issue #29 is fully solved.

## Release gate

Do not merge or publish v1.10.14 until CI passes on the clean stabilization branch and a final Android smoke test confirms normal Telegram messages, media transfer, Worker failover, and no reconnect storm. Issue #53 additionally requires an A/B Worker check showing no material throughput regression and lower Durable Object pressure with concurrent sessions. Issue #55 additionally requires a direct `*.workers.dev` A/B confirming lower idle downstream request churn without worse message latency, sustained download throughput, timeout rate, or HTTP 502 rate.
