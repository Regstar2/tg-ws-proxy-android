# Issue #139 — In-app APK update acceptance

**Stage:** implementation in PR #144; no physical-device result yet.

## Startup detection regression (PR #144)

- [ ] Startup makes an asynchronous GitHub release check; when successful, the result is reused on the Updates screen and across process restarts for up to 6 hours.
- [ ] A failure is not silently labelled up-to-date. Network failures are retried after 5 minutes (manual “Check for updates” always forces a request).
- [ ] Installing the release-signed `1.11.7-debug` build with versionCode 63 does **not** prompt for stable `v1.11.7` (also versionCode 63). A genuinely newer `v1.11.8` must still be offered.
- [ ] Startup dialog appears only for a newer compatible release and with notifications enabled and not snoozed. “Later” snoozes for 24 hours; “Disable reminders” remains in the dialog below the two primary actions.
- [ ] Run `AppUpdatePopupIssue139Test` on a safe test installation to verify the dialog and callbacks even before a new public GitHub release exists.
- [ ] Verify manual refresh bypasses the cached result without starting a duplicate request or touching the running proxy.

## Source and build gates

- [ ] Windows CI: scripts/ci.ps1 (Go, Android unit tests, lint, assembleDebug).
- [ ] GitHub Releases metadata correctly selects a signed ARM64 APK only (stable/prerelease policy, exact official asset URL).
- [ ] Verify SHA-256 from release asset digest, paired .sha256, and reject disagreement.
- [ ] Verify full APK cryptographic signature with apksig, installed signer identity, exact applicationId, increasing versionCode, Android minSdk and device architecture.
- [ ] Confirm URL redirects stay on trusted HTTPS GitHub release CDN hosts and reject disallowed URL, MIME and oversize responses.
- [ ] Test corrupt download, 403/404/429, lack of space, offline, interrupted read, cancelled and repeated requests.

## Physical device — signed APK (blocking)

1. Start with an official release-signed APK, applicationId io.github.regstar2.tgwsproxy. Record OS version, ABI, installed versionCode and APK SHA-256.
2. Configure nondefault proxy routing, Cloudflare Workers and AWG/WARP profiles; export safe/nonsecret evidence of existing settings.
3. Publish a newer **release-signed** candidate using the **same certificate** and the same applicationId, with a larger versionCode and the official paired .sha256. Do not use a debug-signed candidate.
4. Launch app, confirm the nonblocking startup dialog when reminders are on. Verify Later postpones the same prompt by 24 hours; Disable reminders persists across restart and may be reversed on the Updates screen.
5. Open Settings → Updates, check the installed version, release version, collapsible release notes and top-right link. Confirm only one main bottom action appears and remains anchored across available, downloading, preparing, ready, failed and up-to-date.
6. Tap Download and install. Record download progress and verify the APK passes hash and signer validation; confirm no installer is launched before checks finish.
7. On Android 8+/9+ test when install-from-this-source is disabled, grant it in Android Settings, return and continue. Reject permission as a separate scenario; app/proxy must remain usable.
8. Confirm Android's own installer dialog; test Cancel without data loss. Repeat, confirm install, restart the application and record updated installed versionCode.
9. Verify saved settings and route policies, Workers and AWG/WARP remain unchanged; check Telegram MTProto connectivity before and after and that service startup is not blocked by update checks.
10. Test old com.amurcanov.tgwsproxy installs separately: this app has a different applicationId and cannot be updated in place.
11. On Android 9 / BlackView BV5900 repeat if device available (#128).

## Environment-specific constraints

- No automatic background install, root or Accessibility.
- The downloader follows Android's HttpURLConnection system network/proxy selection without a hidden explicit Direct retry. The application has no verified unified System/Direct/Custom outgoing-HTTP proxy selector yet; compare network behavior against NETWORK_PROXY_STANDARD before closing #139.
- Signature key rotation is not supported automatically: mismatch fails closed until Android signing lineage verification is implemented.
- Interrupted updates never reuse an APK from a prior process; app-private .part and previous verified cache entries are cleaned.

Record actual test command, HEAD, device serial (redacted), source/target versionCode, result and evidence. **Do not close #139 or merge #144 as complete before these mandatory checks.**
