# Issue #127 — Direct WS reliability; CF Proxy PoC reverted

**Status: DRAFT.** Direct reliability code is committed but updated Go/Android
tests and physical device acceptance have not yet been executed. This is not
yet a fix confirmed on the user's network.

## Why the CF experiment was removed

The user requested restoration of the pre-experiment Cloudflare Proxy path
after observing that Telegram often displayed "Connecting to proxy".
The previous ClientHello A/B used build tags (baseline and tcp_split) that
also changed TLS certificate verification on experimental builds. Those
runs did not isolate one variable. More importantly, existing physical
logs already showed that ordinary CF Proxy could establish genuine
bidirectional MTProto connections without any ClientHello splitting.

All experimental CF hooks, profiles and source files were removed.
The following files were restored byte-for-byte from current main:
- native/tgwsproxy/tg-ws-proxy.go
- native/tgwsproxy/routing.go
- scripts/build-native-android.ps1
- app/build.gradle.kts
- .github/workflows/trusted-ci.yml

The current change to native/tgwsproxy/mtproto_ws_route.go affects
the Direct WS connector only; the CF Proxy connector is unchanged.
The ordinary CF runtime has an existing TLS certificate verification
weakness (InsecureSkipVerify). Reverting the experiment restores the
old setting, but does **not** resolve this separate security defect.

The "Connecting to proxy" UI symptom does not by itself identify
CF Proxy as the root cause. The original release or existing debug APK
is not updated merely by committing a change to GitHub.

## Previous Direct Wi-Fi findings

Physical Direct-only A/B 2026-11-09, anonymized summary:
- baseline: 102 route-truth attempts, 88 TCP dial timeout log lines,
  37 failed Direct sessions labeled direct_ws_ip_timeout, no successful
  Direct connections, no TLS handshake/WS upgrade error log lines.
- tcp_split: 102 route-truth attempts, 81 TCP timeout log lines,
  33 failed Direct sessions, no successful Direct connections and
  zero split-write markers.
- Both phases showed roughly 34 thousand rejected local MTProto handshakes.
  Their precise cause was not identified; these are not successful sessions.

Result: a TLS ClientHello intervention cannot fix a TCP dial
timeout that occurs before TLS begins. Whether the network filters
the destination address or it is otherwise unreachable remains unknown.
The high rejected-handshake count motivated removing unnecessary
Telegram restarts from the device diagnostic.

## Revised Direct-only implementation

1. The MTProto Direct WS connector now carries its original request
   context through TCP, DNS and TLS, so cancellation during a pending
   dial or stop is not silently discarded.
2. The route has a 15-second total budget. When an alternate target
   is available, each candidate gets at most 7 seconds; otherwise the
   target gets up to 10 seconds.
3. At most two concurrent TCP/WS dials are allowed per DC+target.
   TCP-stage timeouts, unreachable errors and refused connections
   receive exponential cooldown up to five minutes. TLS errors
   do not trigger a TCP cooldown. A new policy generation can reset
   stale idle health state.
4. When the configured IP is the existing built-in default for DC2
   or DC4, Direct WS can try Flowseal's 149.154.167.220 as an
   alternative. User-overridden IPs are left unchanged; Telegram
   test DCs do not use this alternative. This IP is not asserted
   reachable on the user's network.
5. If the old default IP is cooled down, the Direct connector
   can still attempt its independent alternative. The existing
   route policy remains authoritative: strict Direct-only never
   silently switches to CF/Worker/AWG.
6. Cloudflare Proxy, Worker, direct TCP, TLS config, WebSocket
   framing, and native build tags remain unchanged from main.

## Tests and device safety

- New Go unit coverage checks candidate selection, manual overrides,
  legacy cooldown interaction, cancellation, concurrency caps,
  TCP-stage classification, and cooldown reset.
- Existing project CI still requires go test -race ./..., host
  scripts/ci.ps1 and Android Gradle debug build/lint/tests.
- The debug-only Issue127 Direct receiver and
  scripts/issue-127-direct-wifi-ab.ps1 now perform one bounded
  Direct smoke instead of CF baseline/split. No Telegram force-stop
  or restart, no app uninstall and no data reset. The synthetic
  loopback socket is held open for 18 seconds; Android broadcast
  returns immediately after scheduling it.
- The device script restores the original Wi-Fi route preferences
  in finally even on diagnostic failure, but leaves the newly
  installed original-key-signed DEBUG build in place; it does not
  reproduce the original release APK. Its output is not proof of
  Telegram message/media delivery.
- Keep raw logs private; existing offline log summarizer handles
  the older baseline/tcp_split datasets only.

## Outstanding acceptance

**NOT_RUN:** full Go race suite, Windows PowerShell CI, signed
Android build, and new physical Direct-only smoke on the user's Wi-Fi.
**NOT_VERIFIED:** actual accessibility of 149.154.167.220,
message sending/receiving, media upload/download, safe reconnects,
and whether the reported CF Proxy UI symptom remains after
reinstalling the ordinary build.

Do not merge or close Issue #127 while the mandatory tests remain
missing. The goal is a measured Direct recovery without changing the
previously functional Cloudflare route.
