# Issue #127 — CF Proxy and direct_ws ClientHello TCP split PoC

Status: **INCONCLUSIVE** until a controlled Android A/B test on the affected network.
This is a post-release experiment, not a production DPI-bypass feature.

## Hypothesis and scope

Hypothesis: the form in which outgoing TLS ClientHello is written to a TCP connection can affect the availability of `cf_proxy_ws` **or** `direct_ws` on some networks. The 2026-10-30 log export does **not** demonstrate DPI as the cause of TLS timeouts. Compare one intervention (`tcp_split`) with a separate, otherwise identical PoC control (`baseline`), **independently for each route**. The `direct_ws` arm was added at the repository owner's explicit request on 2026-11-09; this does not broaden the experiment to `direct_tcp`, Worker, or AWG.

No root, Android `VpnService`, system proxy, IP interception, TLS stack fork or code copied from zapret/tpws is used. This implementation is independent and does not incorporate zapret binaries or source code. No new third-party license is introduced.

## Audited transport path

The MTProto frontend invokes the outbound `mtProtoCFProxyConnector.Connect` from `mtproto_ws_route.go`:

1. Select a Cloudflare domain for the Telegram DC from `cfPool.SelectionForDC` and reserve it.
2. Build `cfProxyHost` and call `defaultMtProtoCFProxyDial(ctx, host, "/apiws", prefix)`.
3. `compatibleWebSocketTLSDialer` chooses classical TLS groups (X25519/P-256) and optionally retries the Go-default group profile after a TLS-handshake error. DNS resolution/IP retries are existing behavior.
4. `wsConnectWithCurvesContextDialProfile` uses `net.Dialer.DialContext`, a cancellable TCP connection, Go `crypto/tls.Client` and `HandshakeContext` with deadlines. TLS sets `ServerName=domain`; no custom ALPN is selected.
5. A manually constructed HTTP WebSocket upgrade requests `/apiws`; HTTP 101 returns `RawWebSocket`, passed to `mtProtoWebSocketConn` to transport relay initialization and MTProto frames.

The older `cfProxyDialHost` frontend path also opts into the CF experiment profile. `direct_ws` uses `mtProtoDirectWSConnector.Connect` and `defaultMtProtoDirectWSDial`: it first connects to the Telegram DC target IP with a separate WebSocket SNI domain, and falls back to DNS-resolved domain IPs **only** after a TCP-dial-class failure. `wsConnectViaResolvedDomainWithDial` now accepts an injected dial function so both direct attempts use exactly the same build-selected TLS profile, while existing non-experimental callers still use the original `wsConnect`. `direct_tcp`, Cloudflare Worker, AWG/WARP and other WebSocket callers do not use the PoC profile.

Both PoC routes share `webSocketClientHelloPoCProfile`. Only these two route connectors opt in; this is not a global TLS socket hook. `direct_ws` continues using its original TLS curve preferences (Go defaults), which differ from CF Proxy's existing classical-first/fallback strategy. Compare baseline and split **within** a route, not across routes, to avoid that confound. A direct route can fail on blocked target IPs even if ClientHello is fragmented correctly.

**Pre-existing security discrepancy:** `webSocketTLSConfig` currently sets `InsecureSkipVerify: true`. It does not provide standard certificate/hostname-chain verification on the shipping baseline. This PoC does not silently change the production baseline. **Both experimental build profiles enable Go's standard certificate verification**, keeping certificate validation identical across their A/B comparison. Production certificate hardening requires a separate scoped fix and its own compatibility acceptance; do not present the existing runtime as already certificate-verified.

## Build-only experiment selection

| Build setting | TCP ClientHello writes | Certificate validation | Deployment intent |
| --- | --- | --- | --- |
| unset `TGWS_CF_CLIENTHELLO_POC` | unmodified, both routes | existing production behavior | default application |
| `baseline` | unmodified, both routes | Go standard verification | PoC A/B control for each route |
| `tcp_split` | attempt two writes around first SNI name byte, both routes | same Go standard verification | PoC A/B experiment for each route |

The selector is compiled into the Go library using `cfproxy_poc_baseline` or `cfproxy_poc_tcp_split` build tags. It is **not** a runtime retry strategy, a system-wide network alteration or a production preference. The standard app build has no strategy change; Gradle considers the environment selector a native-build input. Explicit release tasks reject the PoC environment setting. Never distribute the PoC APK as a regular release.

The adapter only inspects the first outbound TLS record written to the connection. It locates an unfragmented ClientHello's SNI extension without storing the domain, and splits the unchanged record byte stream inside the SNI hostname. Malformed, missing-SNI or already-fragmented ClientHello records pass through untouched. Subsequent TLS records and application data go directly to the underlying connection. Partial writes and connection errors are propagated, and the underlying connection continues to own its deadlines and cancellation-by-close.

A successful debug message `CF Proxy PoC: ClientHello tcp_split performed two writes (TCP segments unverified)` (historical adapter wording, also emitted for `direct_ws`) establishes **only** two `net.Conn.Write` operations. Network-level packet segmentation is **not established**; Android/kernel/TCP coalescing can invalidate that part of the hypothesis. No hostname, user IP, TLS secrets or MTProto payload is included in the new message.

`tls_record_fragmentation` is deliberately **NOT_IMPLEMENTED** in this first step. It would require a separate record-aware experiment that correctly reserializes TLS record boundaries while preserving handshake bytes and compatibility, rather than mislabeling TCP write splitting as TLS fragmentation. Do not implement it through a fork of `crypto/tls`.

## Tests and acceptance

### Automated coverage

- ClientHello SNI offset parser with a real Go-generated hello, no-SNI and malformed record pass-through.
- Equality of the complete output bytes versus the original ClientHello; untouched subsequent application writes.
- Partial first/second writes and error propagation; blocked writes with deadlines and context-triggered close.
- Local trusted TLS 1.2/1.3-capable server with strict certificate verification and HTTP 101 WebSocket upgrade, tested with both unmodified and split writes; untrusted-cert negative case.
- Regression tests cover direct_ws primary target success, DNS-fallback reuse of the same dial/profile, and no DNS fallback on TLS handshake errors. Routing does not alter `direct_tcp` or Worker.
- CI runs the normal Go race suite plus builds/tests both explicitly tagged PoC variants. Windows `scripts/ci.ps1` and Android debug compilation are required for this branch. **Never mark PASS before workflow results.**

### Physical A/B test, still required

1. Use one network on which `cf_proxy_ws` and/or `direct_ws` genuinely fails. If a route currently works reliably, label its result **INCONCLUSIVE**, not GO. Preserve network conditions; no DNS overrides, switching networks, changing route policies or changing active endpoint lists mid-series.
2. With the same signing key and without uninstalling the existing package, build the `baseline` PoC APK, install it through the repository's safe signed-device workflow, and run bounded MTProto tests. Preserve local logs **privately**. Keep **only the route under test** enabled (`cf_proxy_ws` or `direct_ws`); disable all other routes for this measurement. Never silently fall back to another route.
3. Repeat on the same network/DC/CF endpoint or explicitly record any endpoint difference, using `tcp_split`. Ideally interleave A/B attempts; if a per-app reinstall or pool cooldown is involved, record that limitation. Compare at least five controlled attempts per profile before making a claim. **Do not exhaust CF pools by spawning mass reconnects.**
4. For **each route separately**, capture an anonymized tuple: route, profile, network class, DC, fixed target and SNI/domain pseudonyms, whether the primary IP or DNS fallback connected, timeout budget, TCP connect success, TLS success/timeout/error category, cert validation, HTTP 101, connection elapsed milliseconds, MTProto uplink/downlink bytes, actual backend, reconnect outcome. A fixed-IP failure must be distinguished from a TLS timeout. Report aggregated counts, not raw domain names, IPs, tokens or MTProto payloads.
5. Demonstrate Telegram can connect and exchange text **both directions**, send and download media, and reconnect without leaking late TLS attempts. Independently run the negative control on a normally reachable TLS endpoint/network. A bare TLS success or HTTP 101 is insufficient.
6. If possible, confirm the actual TCP packet boundaries via a *private*, consented packet capture on the testing network. Otherwise mark `TCP segmentation: NOT_VERIFIED` even when two Write calls are logged.
7. Confirm routing and health/cooldown behavior remains consistent with completed Issues #125 and #126. Restore the ordinary build afterward by clearing the PoC environment variable.

The signed installer `scripts/issue-135-rebuild-device.ps1 -SkipInstrumentedTests` can preserve the existing installation with the **original** signing certificate. Verify one connected authorized device and a clean branch first. Never uninstall/reset application data to resolve a signature mismatch; never upload user diagnostic ZIPs or sensitive unredacted captures publicly.

### Result sheet

| Measure | CF baseline | CF tcp_split | Direct WS baseline | Direct WS tcp_split |
| --- | --- | --- | --- | --- |
| Attempts / same endpoint and DC | NOT_RUN | NOT_RUN | NOT_RUN | NOT_RUN |
| TCP success | NOT_RUN | NOT_RUN | NOT_RUN | NOT_RUN |
| TLS success / timeout / other | NOT_RUN | NOT_RUN | NOT_RUN | NOT_RUN |
| Certificate verification | Required, NOT_RUN | Required, NOT_RUN | Required, NOT_RUN | Required, NOT_RUN |
| HTTP 101 | NOT_RUN | NOT_RUN | NOT_RUN | NOT_RUN |
| Real MTProto duplex text | NOT_RUN | NOT_RUN | NOT_RUN | NOT_RUN |
| Upload + download media | NOT_RUN | NOT_RUN | NOT_RUN | NOT_RUN |
| Repeat reconnect | NOT_RUN | NOT_RUN | NOT_RUN | NOT_RUN |
| Actual TCP segmentation | NOT_VERIFIED | NOT_VERIFIED | NOT_VERIFIED | NOT_VERIFIED |

For `direct_ws`, record whether TCP fails *before* any ClientHello is sent. In that case the split cannot fix the connection; IP-level blocking and TLS/SNI filtering are different hypotheses.

**Decision: INCONCLUSIVE.** The adapter enables a measured comparison; this issue cannot be closed solely with compilation/unit tests. GO requires a reproducible full-route gain with no negative-control regressions. NO-GO requires comparable evidence of no useful gain or unacceptable TLS/route regressions. Keep the PR unmerged while device acceptance is missing.
