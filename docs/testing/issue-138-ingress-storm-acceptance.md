# Issue #138 — Fake TLS loopback ingress storm

## Observed baseline (2026-11-05)

During Issue #135 device acceptance, the reported archive contained 11,801
`fake tls client hello expected` lines in approximately 34 seconds. The same
capture showed seven outbound `direct_tcp → awg_warp` fallback connections,
including four completed bidirectional bridges. A rejected **local input**
is not evidence of an AWG transport failure. The sender cannot be attributed
to Telegram from a loopback address and ephemeral port alone.

The root source of the reconnect storm remains **UNCONFIRMED**. A second
installed app/process or a stale/mismatched proxy link is a hypothesis, not
an established cause.

## Code-level finding and mitigation

Before Issue #138, `handleConnection` used `updateRouteTruthFor` for a
rejected inbound handshake even though `OutboundConnector.Connect` had not
been called. That updated the generic `last_error` and could make a
working AWG route look faulty. Rejected inbound handshakes now only update
Fake TLS ingress diagnostics; they do not overwrite confirmed outbound route
truth or errors. They never initiate fallback.

Logarithmic per-rejection-class sampling bounds diagnostic log volume for a
reconnect storm (samples 1, 2, 4, 8, ... per class), while the cumulative
`handshake_rejected` counter is exposed in native MTProto status. The
`fake_tls_accepted`, `fake_tls_redirected`, `fake_tls_rejected`,
`fake_tls_probe` and `fake_tls_last_error` counters remain available.
The `ingress_class` of a non-ClientHello first record is one of
`plain_http`, `malformed_tls_record` or `unrecognized_binary`; no
raw bytes, full secrets, proxy links or packet payloads are logged.

These changes protect routing truth and diagnostic log volume. They do
**not** establish who produced the connections or guarantee the external
client stops reconnecting.

## Automated regression checks

Run the existing `scripts/ci.ps1` on Windows. Native focus:

```powershell
Set-Location -LiteralPath "native/tgwsproxy"
go test ./mtproxyfrontend -run 'TestIssue138' -count=1
go test ./mtproxyfrontend -race -run 'TestIssue138' -count=1
```

The tests cover:
- classification of HTTP/malformed TLS/unrecognized ingress without revealing bytes;
- hundreds of invalid obfuscated attempts, bounded logs and preserved confirmed AWG route state;
- concurrent invalid Fake TLS HMAC ClientHello probes followed by an authenticated EE handshake;
- legacy DD obfuscated init with the configured secret;
- classification and counters for distinct failure classes.

The full Windows CI also builds Android APK and runs its current JVM/lint
checks. Go race availability depends on the chosen OS/toolchain; report it
separately rather than treating an unrun check as green.

## Fully automated Android reproduction (no Telegram changes)

The PowerShell runner now exercises the **real running Android local
listener** through a temporary ADB TCP forward, independently of Telegram.
It first records an untouched **Baseline** (to detect spontaneous reconnects)
and then deliberately sends 64-byte unauthenticated binary handshakes in
**Inject**. All injected connections are known to originate from this test
harness, not from Telegram.

Preconditions: one authorized ADB device, TgWsProxy already installed and
running, MTProto listener in Fake TLS mode, and the actual configured
listener port. The runner neither installs APKs nor starts/reconfigures the
proxy. It never reads the MTProto secret.

Run a bounded default smoke (both phases, 35 seconds each, up to 40 attempts/s):

```powershell
Set-Location -LiteralPath "<local-clone-of-tg-ws-proxy-android>"
.\scripts\issue-138-reproduce-storm.ps1 -Mode Both -DurationSeconds 35 -RatePerSecond 40 -Port 1443
```

To reproduce **the historical 11,801 failures in roughly 34 seconds**
more closely, invoke the higher-pressure mode deliberately (only on your
own test device and while prepared for temporary local proxy slowdown):

```powershell
.\scripts\issue-138-reproduce-storm.ps1 -Mode Both -DurationSeconds 34 -RatePerSecond 350 -Port 1443
```

Rate is a **target/upper limit**, not a guaranteed observed result.
The runner measures attempted connections, received HTTP 301 redirects,
failure count and actual injection rate. Ten consecutive failures stop
injection rather than endlessly hammering a missing listener.

Artifacts: `artifacts/issue138/repro-YYYYMMDD-HHMMSS.zip`. Every phase
contains isolated streaming logcat (current app PID only, without clearing
Android logs), PID/socket snapshots, available CPU snapshots and JSON
analysis. The script removes its own temporary ADB forward even on failure.
The parser recognizes both the historical per-connection log format and
the new sampled `class_count` format; the rate of log entries must
not be mistaken for the actual TCP connection rate after sampling.

CI runs the **offline** parser regression fixture with the reported
11,801 entries/34 s, sampled exponential counters, good AWG bidirectional
bridge and crash/ANR negative examples. CI does **not** run the ADB
reproducer on a remote/public runner.

Interpretation:
- A high **Baseline** rejection rate suggests an external app or local
  configuration is still reconnecting, but does not identify that app.
- Successful **Inject** redirects prove malformed local connections can
  cause the observed rejection class and that the listener remains up;
  they cannot establish the source of the original 2026-11-05 storm.
- Actual backend and duplex are proven only by logs with
  `actual_backend=awg_warp` and both `up_bytes`/`down_bytes` positive.
  If no legitimate Telegram session occurs during the test, that gate is
  **NOT VERIFIED**, not PASS.
- CI/native Go tests continue to check correctly authenticated DD/EE
  handshake behavior. The injected script deliberately does not simulate
  a real authenticated Telegram connection or a full AWG network route.

## Device follow-up — 2026-11-12

User PowerShell 5.1 report: offline parser regression tests PASS.
34-second Baseline on the installed Android APK: zero handshake rejection
log lines, 19 outbound MTProto connections, one completed AWG duplex bridge,
zero no-route/crash/ANR markers, stable application PID 3481.

The Inject phase then returned ten consecutive invalid outcomes and aborted
without its own per-phase summary in the previous version of the harness.
The existing user archive is `repro-20261112-113920.zip`, but its content
was not attached for inspection. Do **not** conclude the device listener is
broken, or that the issue is resolved, from the old harness exception.

The amended harness preserves `Inject-failures.txt`, `Inject-result.json`,
`Inject-logcat.txt`, `Inject-listeners-before.txt` and the ADB forwarding
mapping on an early stop. It logs response class, a sanitized HTTP status line
or the exception type/message and reports actual rather than requested time
for an aborted phase.

For diagnosis, first check the **currently running** MTProto listener is on
port `1445`, and the configured masking domain is non-empty (Fake TLS).
Do a short isolated test before repeating a 350/s pressure run:

```powershell
.\scripts\issue-138-reproduce-storm.ps1 -Mode Inject -DurationSeconds 10 -RatePerSecond 10 -Port 1445
```

An HTTP 301 is expected for the deliberately invalid 64-byte binary payload
in Fake TLS mode with masking domain set, as implemented by the native
`writeFakeTLSRedirect`. If a different frontend is active or the local
listener is bound to another port, the test's expected HTTP 301 is inapplicable.
The runner does not install the branch's new APK, so the installed release
version must be checked separately. Neither a healthy Baseline nor a
synthetically induced rejected connection proves the original reconnecting
client's identity.

## Read-only Xiaomi evidence capture

Use the **actual MTProto port** from the app settings, not a guessed value.
Run the script in PowerShell in the cloned repository while the app and
Telegram are active. Use the documented default port only if still configured:

```powershell
Set-Location -LiteralPath "<local-clone-of-tg-ws-proxy-android>"
.\scripts\issue-138-ingress-attribution.ps1 -DurationSeconds 35 -Port 1443
```

The script requires exactly one ADB device, makes no package/data changes,
captures process listings, both known application IDs' PIDs, a sequence of
socket snapshots (if unrooted Android permits it), and filtered app logcat.
It writes `artifacts/issue138/ingress-*.zip`. It intentionally does **not**
collect secret values, VPN profiles, packet payloads, or clear logcat.
Review the archive for sensitive user/device details before sharing.

An unrooted device may not expose process/UID ownership of sockets via
`ss`. If socket ownership cannot be established, the sender is still
**UNKNOWN**; do not infer it from `127.0.0.1`.

## Device acceptance matrix

Repeat the capture separately for each scenario, preserving the working
configuration between runs:

1. **Current EE link** generated by this exact app installation, Telegram
   updated, same local port and secret. Expect authenticated Fake TLS
   sessions and genuine MTProto bidirectional bridges.
2. **Stale EE / wrong secret / mismatched port**. Expect explicit ingress
   rejection or no listener connection, with no false AWG failure.
   Never attach or paste the full secret/link.
3. **Existing DD link with the same raw secret**. Expect authenticated
   obfuscated compatibility in Fake TLS mode, not a rejection storm.
   A DD link with a different secret must be rejected.
4. **Unrelated loopback client / malformed ClientHello**. Expect isolated
   counters, sampled categorized logs, no outbound route selection.
5. **Under rapid invalid reconnect pressure**, repeat legitimate EE/MTProto
   text and media traffic; record socket count, CPU impact, actual backend,
   positive `up_bytes` and `down_bytes`, and any ANR/crash.
6. **Second installed package / process**. Capture both PIDs, then test
   only authorized configuration changes (do not uninstall or clear either
   application merely for the test). Confirm which process owns each listener
   and, only with reliable OS evidence, the connecting socket owner.

Also rerun `scripts/issue-135-route-switch-smoke.ps1` under the documented
acceptance conditions to confirm TCP→AWG fallback and duplex, independently
of ClientHello ingress diagnosis.

**Completion gate:** source of the storm is evidenced (or a reproducible
runtime defect is isolated), an appropriate root-cause fix is tested, current
Telegram succeeds in the relevant route scenario, and legitimate device
sessions stay healthy under malformed connection pressure. Until then, keep
Issue #138 and any related PR open; do not claim the storm is eliminated.
