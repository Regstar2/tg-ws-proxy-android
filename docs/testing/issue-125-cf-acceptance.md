# Issue #125 — CF Proxy acceptance on Android

This is a **device acceptance plan**, not evidence that the tests have passed.
Run against the exact commit of PR #129, with a release-key-signed debug APK.
Keep local credentials, domain lists, Telegram message contents and raw log exports private.

## Preparation

1. Use `scripts/build-signed-worker-smoke.ps1 -Install` from the PR worktree.
   Despite its historical name, it builds the current signed debug application
   with the original release key, preserving application data when the signing
   certificate matches. Never uninstall the application as a workaround.
2. Confirm `adb shell dumpsys package com.amurcanov.tgwsproxy` reports
   the installed package and that Telegram is configured to use this local proxy.
3. Disable other proxy/VPN paths for the control run. Record current route policy
   and restore it after testing.
4. Enable temporary runtime logging, then keep the detailed export private.
   Public findings should contain counters and reason codes, never raw logs.

## Real-network matrix

| Phase | Configuration and actions | Expected observations |
| --- | --- | --- |
| A — CF-only baseline | Wi-Fi; only `cf_proxy_ws`, fallback disabled. Start proxy; Telegram send and receive messages and load and upload photo/file. Repeat on cellular if available. | Connected route has `actual_backend=cf_proxy_ws`, `fallback_used=false`, positive `up_bytes` and `down_bytes`. Verify message and media contents in Telegram; byte counters alone are insufficient. |
| B — CF-only outage | On the same network, temporarily block access to *all configured CF endpoints* using a controlled network outage; do not publish actual domains. Attempt reconnect repeatedly; then allow the pool to reach cooldown. | `cfproxy_unavailable` is bounded and contains DC-scoped eligible/cooldown/in-flight/retry details. No direct, TCP, Worker or AWG connection is initiated under CF-only policy. No repeated TLS dials while all domains are in cooldown/in-flight. |
| C — recovery | Restore the same network and wait for the reported cooldown, without clearing settings, switching backend or restarting the proxy. | A CF endpoint becomes eligible and Telegram connects and exchanges messages in both directions. DC1 and DC2 health stay independent. |
| D — permitted fallback | Enable fallback and explicitly permit one available alternative route; induce a controlled CF failure again. | A permitted alternative may be attempted, and diagnostics distinguish `selected_backend` from `actual_backend`. No unpermitted route appears. A failed alternative is not counted as a successful fallback. |
| E — regression | Restore original route policy and network; test Worker (configured transport), AWG/WARP if configured, stop/start and normal Telegram usage. | No new crashes, stale policy or loss of saved settings. |

For a meaningful C/D result, the network restriction must affect only the
selected Cloudflare endpoints; a full loss of Internet connectivity does **not**
prove that a fallback route works. If suitable controlled network access is not
available, record `NOT_RUN` rather than `PASS`.

## Report

For each phase record: APK version, exact Git commit, Wi-Fi/cellular, route policy,
duration, inbound MTProto sessions, successful CF TLS/WebSocket connections,
`cfproxy_unavailable` count, `retry_after_seconds`, actual backend, permitted
fallback result, positive up/down byte sessions, and direct Telegram
message/media outcome. Distinguish `PASS`, `FAIL`, and `NOT_RUN`.

The frontend dispatches one outbound connection decision per accepted local
Telegram connection; the connector can try multiple eligible CF endpoints and
at most one Go-default TLS fallback per selected domain. Therefore repeated
`cfproxy_unavailable` events do not by themselves identify whether Telegram
or the local runtime initiated the repeated local connections. Establish the
source using time-correlated incoming-session and outbound-attempt counts;
otherwise explicitly mark that root cause as unknown.

Do not merge #129 or close #125 based only on CI or byte counters: the Issue
requires actual Android testing of outage, policy-limited fallback and recovery.
