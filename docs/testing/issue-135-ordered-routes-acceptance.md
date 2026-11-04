# Issue #135 — ordered routes acceptance (v1.11.7)

## Automated checks

- Android JVM: `NetworkRoutePolicyEditorTest`, `NetworkRoutePolicyRepositoryTest`,
  `EffectiveRoutePolicyResolverTest`, `ConnectionRuntimeConfigTest`,
  `MtProtoAwgRuntimeOwnershipTest`.
- Native Go: `go test ./...` and `go test -race ./...` under `native/tgwsproxy`.
- Android: `gradlew :app:testDebugUnitTest :app:lintDebug :app:assembleDebug`.
- Instrumentation: test the rendered Wi-Fi and mobile profile tabs, arrows, route toggles,
  reset confirmation, restart persistence and accessibility labels.
- A green unit-test suite does **not** prove real MTProto transport success.

## Device acceptance matrix

Use a debug-enabled Android handset, a working Telegram MTProto proxy account,
and valid Worker/Cloudflare/AWG configurations where applicable.

1. Start MTProto on Wi-Fi. Observe exact order in runtime log (`route_order`,
   `selected_backend`, `attempt_backend`, `actual_backend`, `fallback_used`,
   `route_generation`). Verify Telegram loads **text and media in both directions**.
2. Move CF Proxy down and Worker up with arrows, and then drag. Keep the frontend
   running. New sessions must attempt the *new* top route; sessions established
   before the edit must continue without a service restart or socket closure.
3. Disable a middle route and verify new sessions never dial it, even when it is
   healthy or was previously the last-good route. Re-enable it: it should append
   as the final active route. Confirm at least one active route remains.
4. Force failures of each of five candidates and observe exactly the declared
   fallback order. A single-route profile must fail without hidden TCP/Direct.
5. Select AWG without an imported or valid tunnel configuration. Verify the
   route fails/skips with an explicit diagnostic; the next configured route
   should still work. Verify direct bypass never occurs unless listed.
6. Switch Wi-Fi → mobile → Wi-Fi with **different saved orders** while maintaining
   at least one existing connection. Verify new sessions use the corresponding
   profile; old sessions must not be forcibly closed.
7. Force-stop/relaunch the app, then reboot the phone. Verify orders,
   disabled routes and network isolation persist.
8. Reset *only* the Wi-Fi profile after the confirmation dialog; mobile must
   remain unchanged.
9. Upgrade from v1 preferences (including Worker-first, CF-only, custom v1 policy,
   and stale/unknown routes). Verify migration does not enable previously disabled
   candidates. Restore v1 backup in test environment for repeated runs.
10. Run the SOCKS5 frontend separately and check whether its route behavior
    matches MTProto. If not, record explicitly that the native ordered selector
    is MTProto-only.

## Evidence required before merge

Attach a CI run link with Go/JVM/Android checks, a device-run log excerpt with
actual uplink and downlink byte counters for both text and media, build SHA,
Android version, and clear PASS/FAIL for each matrix item. Remove sensitive
endpoints, proxies, session identifiers and credentials from evidence.

**Do not merge** without device checks and validation of both MTProto traffic
directions. This document describes required checks; it is not a claim
that these checks have already passed.
