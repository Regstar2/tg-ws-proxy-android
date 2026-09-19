# Changelog

All notable user-facing changes are listed here. Detailed notes for older releases: [docs/releases/](docs/releases/).

## Unreleased
- WARP/AWG profile validation now requires a real Telegram MTProto `req_pq_multi` → `resPQ` round-trip, preventing generic-WARP false positives that leave Telegram sessions with zero downstream bytes.
- WARP/AWG profile details now allow editing both the profile name and the saved `.conf` parameters for imported and automatically generated profiles; changed configs are revalidated and reset to `Not checked` before reuse.
- Automatically generated Consumer WARP profiles now default to numbered names such as `WARP 1`, `WARP 2`, and so on instead of reusing one fixed name.
- WARP/AWG provisioning now has a dedicated bootstrap Worker policy independent from the Telegram `cf_worker_ws` Worker Pool, with custom endpoints tried before the optional built-in pool.
- Added settings to disable built-in provisioning Workers completely and to add, validate, enable/disable, check and delete custom HTTPS bootstrap endpoints.
- Bootstrap health now identifies `service=warp-bootstrap` and revision `warp-bootstrap-v1`; redirects, credentials, query strings and arbitrary endpoint paths are rejected.
- Consumer WARP provisioning now prefers an already full-duplex-validated saved AWG/WARP profile as a bootstrap transport to the fixed Cloudflare Consumer API, with the selected working profile tried first, then other working profiles, direct API, and Worker fallback.
- Every new automatic profile still generates a new local WireGuard keypair and independent registration; provisioning no longer clones a stored Consumer registration when fresh registration fails.
- The native existing-AWG bootstrap is restricted to the fixed `api.cloudflareclient.com` reachability, registration, and activation operations and has no direct-socket fallback.

## 1.11.0-beta.1 - 2026-09-17
- Added the `awg_warp` MTProto backend using an app-local userspace AmneziaWG/WARP stack; it does not use Android `VpnService`, root, or a system TUN interface.
- Added Settings → Cloudflare → WARP / AmneziaWG profile management with automatic Consumer WARP creation, manual `.conf` import, explicit selection, profile details, validation, and deletion.
- Consumer WARP keypairs are generated locally; private keys remain in app-private storage and are not sent to the registration service or written to support logs.
- Automatic provisioning validates the generated config, probes real bidirectional application traffic through the AWG tunnel, and saves a profile only after a candidate succeeds twice (`2/2`).
- Added bounded AWG transport autotuning across endpoint and `Jc`/`Jmin`/`Jmax`/`I1` variants instead of assuming one fixed obfuscation preset.
- Added a restricted Cloudflare Worker bootstrap path for Consumer WARP registration when direct Android TLS access to `api.cloudflareclient.com` fails; arbitrary upstream forwarding is not exposed.
- Registration rate limiting no longer triggers rapid repeated POST attempts; provisioning can reuse an already saved automatic Consumer WARP registration seed for autotuning when fresh registration is temporarily unavailable.
- Real-device acceptance on Android 14 verified automatic registration, autotune `2/2`, `actual_backend=awg_warp`, `fallback_used=false`, bidirectional MTProto traffic, and media traffic.
- Known limitation: Consumer WARP registration depends on an external, stability-sensitive API. A fresh install may require the Worker bootstrap when direct registration is unavailable; using an existing AWG/WARP tunnel for a new independent registration is tracked separately in #86.

## 1.10.14 - 2026-09-14
- MTProto Worker traffic now uses a fresh-HTTPS chunk relay instead of one long-lived `workers.dev` WebSocket while preserving the Telegram TCP session inside Cloudflare Durable Objects.
- Verified upload profile: 12 KiB chunks, sliding window 3, ordered sequence acknowledgements, bounded retries, primary/global request limits and HOL hedging for the oldest unacknowledged upload.
- Worker revision `chunk-relay-mtproto-v10` uses shared `relay-hub-v1`: multiple independent MTProto sessions share one Durable Object instance without sharing Telegram sockets, seq/ACK state, downstream queues or lifecycle state.
- `ROUND_ROBIN` assigns a stable primary Worker per opaque MTProto session id; chunks from one session stay on the same Worker while separate sessions distribute across the enabled pool.
- Durable Objects quota exhaustion is mapped to recoverable HTTP 503 responses with reset metadata; native per-domain circuit breakers skip exhausted Workers until reset and prevent reconnect storms.
- Relay failures are status-aware: terminal session loss reconnects cleanly, transient failures remain bounded-retryable and non-success HTTP responses cannot win hedge races just because the local transport call returned no error.
- Orphaned relay sessions are reaped using client activity rather than Telegram payload activity, with a 60 s timeout plus 5 s scheduling grace so healthy idle sessions remain connected while polling.
- Downstream polling adapts from 6 s to 12 s and 20 s during idle periods, then immediately returns to 6 s when payload arrives.
- Downstream TCP reads are coalesced for up to 8 ms into real responses up to 12 KiB, materially reducing request pressure and improving media throughput compared with forwarding ~4 KiB reads individually.
- A stalled HTTP 200 downstream body is bounded by a separate 2500 ms read deadline; the same ACK is retried over the fresh-request path instead of waiting for the full long-poll deadline.
- The HTTP/1.1 downstream keep-alive experiment was rejected after Android device regression and is not part of the release.
- Worker preconnect remains disabled for the MTProto Worker route, and an explicit Worker-only policy does not silently add direct/TCP fallback routes.
- PR CI includes Go race tests, Cloudflare Worker tests, Android unit tests, debug APK assembly and release-readiness checks.
- Known limitation: Worker transport remains slower than direct connectivity and still depends on Cloudflare Durable Objects quotas; it is not promoted to the default route.