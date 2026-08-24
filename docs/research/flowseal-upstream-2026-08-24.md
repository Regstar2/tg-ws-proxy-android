# Flowseal upstream review — 2026-08-24

## Purpose

This note records the selective upstream review for TGWSProxyAndroid v1.10.13.
The goal is runtime stability, not a wholesale replacement of the Android Go
runtime with Flowseal's Python implementation.

## Baseline and comparison range

The previous repository audit recorded Flowseal commit
`e9b74d7d7dcc3beceab4d34d18d91b8ec9fdf098` as the reference baseline.
On 2026-08-24, `Flowseal/tg-ws-proxy` `main` is 31 commits ahead of that
baseline, with latest reviewed commit
`b2a8074c59c52cabde7fe295280b614cc6c01fce` from 2026-08-13.

Primary runtime commits reviewed:

| Commit | Upstream change | Android decision |
| --- | --- | --- |
| `8ac52f69` | automatic direct WS pool rotation | Already represented by Android `WsPool.rotate` / maintenance behavior; no direct port. |
| `a0545cca` | do not retry an already-fronted connection after timeout | Flowseal-specific fronting state does not map cleanly to the current Android MTProto route adapter; not ported. |
| `47b8db18` | direct WS refill backoff and no refill while an IP is blocked | Android already has direct-IP cooldown and separate pool maintenance. No risky shared-pool rewrite in this patch; the analogous duplicate-refill problem in the opt-in Worker preconnect path is addressed separately. |
| `ecf3d6f3` | Worker fallback splitter correction | Current Android Worker MTProto path constructs its packet splitter after relay-init in `mtProtoWebSocketConn`; no literal port. |
| `41e97c6a` | hard limit Worker pool | Android already caps Worker WS preconnect per key and keeps it disabled by default. |
| `aee473c9` | CF Worker pool refactor to pool by DC, try available domains, and avoid refill-on-miss | Pool-by-DC is **not** ported because Android routes can vary by Worker domain, destination and media state. The safe part is ported: session cache misses no longer start a duplicate background refill while the foreground path is already dialing. |
| `2995ae74` | preserve user CF domains | Android has its own persisted domain/pool model; no port. |
| `0d459995` | generic fixes: Worker first-success break, WebSocket fragmentation/reassembly, 16 MiB frame/message guards, tests/lint | Fragmentation/reassembly and 16 MiB guards are ported for the MTProto WebSocket data plane. First-success behavior already existed in Android and is now covered by a regression test. Python packaging/lint changes are not applicable. |
| `b2a8074c` | version bump | No runtime behavior to port. |

Desktop/macOS UI, PyInstaller, Docker, translation and packaging commits in the
same 31-commit range were reviewed as non-runtime/non-Android changes and are
not imported.

## Ported behavior

### 1. Fragmented WebSocket message handling

Flowseal now reassembles continuation frames before returning a WebSocket
message. The Android `RawWebSocket.Recv()` path previously returned only text or
binary frames and ignored continuation frames, so a fragmented upstream message
could be truncated or lost.

v1.10.13 adds an MTProto-specific bounded frame adapter around the existing
`RawWebSocket`. It:

- preserves the existing Android dial/TLS/routing implementation;
- accepts binary, text and continuation data frames with Flowseal-compatible
  tolerant reassembly semantics;
- keeps ping/pong handling between fragments;
- closes the underlying connection on rejected/failed receive paths;
- applies the adapter centrally in `mtProtoWebSocketConn`, so direct WS,
  Cloudflare proxy WS and Cloudflare Worker WS MTProto routes use the same
  receive semantics.

The legacy shared `RawWebSocket` implementation is intentionally not rewritten
in this release, limiting the blast radius to the MTProto frontend introduced in
the 1.10.x line.

### 2. 16 MiB frame/message protection

The adapter rejects an individual WebSocket frame larger than 16 MiB before
allocating its payload buffer. It also rejects a fragmented message whose
accumulated payload exceeds 16 MiB. These limits mirror current Flowseal
behavior.

Focused tests use a smaller injected limit to verify both guard paths without
allocating large buffers during the unit suite.

### 3. Worker preconnect miss behavior

Android's Worker preconnect remains disabled by default. When explicitly
enabled, `GetForSession` now behaves like the useful part of the current
Flowseal Worker-pool refactor:

- cache hit: return the preconnected socket and schedule a replacement refill;
- cache miss: record the miss but do **not** start another background dial,
  because the current request immediately performs a foreground Worker dial.

This avoids two parallel attempts for the same Worker/destination after a miss.
The existing `Get` method remains unchanged for other internal pool operations.

### 4. Stop after first successful Worker

No production logic change was required. Both the MTProto Worker connector and
the older failover function already return immediately after a successful
Worker connection. A regression test now uses two configured candidates and
asserts that the second candidate is never dialed after the first succeeds.

## Deliberately not ported

### Pool-by-DC

Flowseal can key its CF Worker idle pool by DC because its fallback destination
for that pool is much less variable. TGWSProxyAndroid additionally tracks
Worker domain, destination IP and media state and has Android-specific
destination scoring/media logic. Collapsing those sockets into a DC-only bucket
could reuse a WebSocket opened with the wrong `/apiws?dst=...` target. The
existing Android-specific key is therefore retained.

### Worker preconnect default

The opt-in preconnect mechanism remains disabled by default. Existing project
history already records Worker Pool as a diagnostics/development path, and this
review found no evidence that changing the default is safe.

### Upstream 429 Worker exhaustion reporting

The reviewed upstream implementation currently short-circuits the 429 failure
reporting path with an unconditional return/TODO. Dead or disabled logic is not
ported.

### Flowseal fronting and desktop-specific changes

Flowseal's fronting retry state, desktop UI, macOS, PyInstaller, Docker,
translation and packaging changes are not equivalent to the Android runtime and
are outside this release.

## 2026-08-24 instability evidence

The repository and Issue #2 do not contain the original runtime diagnostics/log capture
from the unstable 2026-08-24 session, so there is no confirmed single root cause.
The owner later reported that the updated build was tested in normal use and worked.
That is treated as a manual smoke-test signal, not as proof that every network/domain
failure mode is eliminated.

The changes above address concrete protocol/pool gaps identified by comparison
with Flowseal. If instability recurs, capture at least:

- active frontend and configured/selected/actual route;
- DC/media classification and Worker destination mode;
- fallback reason and connection close reason;
- WS/Worker pool hit/miss/refill counters;
- DNS/TCP/TLS/WebSocket stage failures;
- network transition around Wi-Fi/mobile switching.

## Verification

Automated validation on the original implementation branch completed successfully for:

- native Go tests;
- Android unit tests;
- debug APK build.

The owner also reported a successful manual smoke test of the resulting proxy build.
Issue #2 can therefore be closed as implemented and smoke-tested, while any future
network/domain-specific instability should be tracked as a new reproducible issue with
diagnostics rather than keeping this upstream-sync task open indefinitely.
