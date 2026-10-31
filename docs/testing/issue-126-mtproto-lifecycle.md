# Issue #126 — MTProto CF TLS cancellation and route-generation acceptance

## Evidence and privacy

The report dated 2026-10-30 shows CF TLS failures after frontend stop and after AWG/WARP startup. It does **not** prove that AWG traffic used CF or that every late event was an active zombie session. Never commit or attach the original user log export, Telegram session secrets, WARP keys, or raw device logs to a public issue.

## Changes under test

- Stop closes pending MTProto frontend client sockets and cancels the per-run context.
- CF TCP dial, DNS resolution, TLS handshake and WebSocket upgrade use the cancelled context. Closing sockets interrupts blocked TLS operations without waiting for their full timeout.
- A single route-decision snapshot with `route_generation` drives attempted-backend telemetry for the session; late frontend results cannot overwrite the new frontend's state.
- CF cancellations should release reservations without creating a new cooldown. Existing successful CF health and DC separation must remain intact.
- Route policies, fallback permissions and both Worker transports are unchanged.

## Reproducible local checks

1. On the same Windows checkout, run `scripts/ci.ps1`. On a Go-capable host, run `go test -race ./...` from `native/tgwsproxy`; Windows CI uses portable subpackage tests and Android NDK compilation.
2. Build the debug APK from this branch with `scripts/build-apk.ps1 -Configuration Debug`. If upgrading a release-signed installed application, use the existing release signing key and the repository's explicit signed-debug opt-in; do not uninstall the application to bypass a signature mismatch.
3. Install the build with `adb install -r`, launch the app, and collect filtered logcat to a local non-public file.
4. Choose an available Cloudflare Proxy route; while requests are outstanding and CF TLS fails/times out, stop the MTProto frontend. Restart immediately with `awg_warp`. Repeat with Worker Chunk Relay and Worker Legacy WebSocket as separate baseline modes and a policy-permitted fallback setup.
5. Compare `session_id`, `route_generation`, `selected_backend`, `attempt_backend`, `actual_backend` and `fallback_used`. An old CF generation may report a final cancellation, but must not claim that AWG selected CF as a fallback. New AWG attempts must select AWG.
6. Check that pending CF attempts settle promptly after stop, domains are again eligible or appropriately on pre-existing cooldown, and no new CF cooldowns are attributed to canceled old attempts. Repeat stop/start and verify existing healthy sessions are not terminated by health-only updates.

## Acceptance matrix

| Check | Expected | Status |
| --- | --- | --- |
| CF stalled dial/TLS plus stop | Prompt cancellation, no retained reservation | Automated regression added; CI pending |
| Late frontend callback | No overwrite of restarted frontend status | Automated regression added; CI pending |
| Route generation truth | One snapshot per request, no false AWG-to-CF fallback | Automated regression added; device pending |
| CF direct/Worker both transports/fallback/AWG | No change in route policy or legitimate connections | Device pending |
| Real Android stop/reconfigure during TLS timeout | Private device log confirms causality and shutdown behavior | Device pending |

A PASS in unit tests cannot replace the device check. The original 185 failures could include legitimate in-flight operations prior to this fix. Only repeatable timestamps and `route_generation` on the new build distinguish cancelled legacy activity from a new route.
