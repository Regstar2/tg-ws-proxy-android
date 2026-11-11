# Issue #139 — In-app APK update acceptance

**Stage:** PR #144; core update verified on a physical Xiaomi and CI passed at tested HEAD. Extended device acceptance remains partially unverified.

## Startup detection regression (PR #144)

- [ ] Startup makes an asynchronous GitHub release check; when successful, the result is reused on the Updates screen and across process restarts for up to 6 hours.
- [ ] A failure is not silently labelled up-to-date. Network failures are retried after 5 minutes (manual “Check for updates” always forces a request).
- [ ] Installing the release-signed `1.11.7-debug` build with versionCode 63 does **not** prompt for stable `v1.11.7` (also versionCode 63). A genuinely newer `v1.11.8` must still be offered.
- [ ] Startup dialog appears only for a newer compatible release and with notifications enabled and not snoozed. “Later” snoozes for 24 hours; “Disable reminders” remains in the dialog below the two primary actions.
- [ ] Run `AppUpdatePopupIssue139Test` on a safe test installation to verify the dialog and callbacks even before a new public GitHub release exists.
- [ ] Verify manual refresh bypasses the cached result without starting a duplicate request or touching the running proxy.

## Quick-install path from startup popup (PR #144)

- [ ] On a release-signed build below the official release version, launch the app with update reminders enabled. Tap **Download and install** on the startup popup and confirm that the **Updates screen does not appear** during a successful download.
- [ ] The download runs independently from the popup and shows progress in an Android notification when notifications are permitted. Cancel via the notification and verify no installer launches or unverified APK remains. A second tap while downloading must not start another request.
- [ ] With **Install from TgWsProxy** already granted and the app visible, the **Android system package installer** opens directly after SHA-256, signer and version checks, without a second in-app confirmation. Android still asks the user to confirm installation.
- [ ] With install-source permission missing, the flow opens Updates **only after verification**, automatically opens Android's setting for TgWsProxy, and after the user grants permission and returns opens the **same already verified APK** in the system installer. No re-download.
- [ ] If permission is denied, the app remains on the permission screen and does not relaunch Settings in a loop. Manual retry is available.
- [ ] If the user backgrounds the app before verification completes, the app must **not attempt a background activity launch**; a notification (when authorized) lets the user return, then install. If Android has disabled notifications, manually returning to the app resumes the pending installation while the process remains alive.
- [ ] If the network, APK hash, signer, or system installer fails, the Updates screen shows the failure and still allows a safe retry without disrupting proxy settings.
- [x] Run `AppUpdateQuickDeliveryPolicyTest` and `AppUpdateInstallPermissionPolicyTest` as part of Android JVM unit tests (local Gradle success and GitHub Windows CI success at the tested HEAD). **The on-device notification, denied-permission and complete lifecycle scenarios remain unchecked.** A process-scoped download is best-effort while backgrounded; it does not persist across process death and cannot claim durable WorkManager delivery.

## Source and build gates

- [x] Windows CI: `scripts/ci.ps1` (Go, Android unit tests, lint, assembleDebug) — GitHub Actions run [#43076343661](https://github.com/Regstar2/tg-ws-proxy-android/actions/runs/43076343661), on `498b516fb32421bca2936e6f8e56f22ba93a8b12`.
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
7. On Android 8+/9+, after APK verification and an explicit tap of “Install”, verify that a missing install-from-this-source permission **automatically opens** Android's `ACTION_MANAGE_UNKNOWN_APP_SOURCES` screen scoped to `io.github.regstar2.tgwsproxy`. No manual second tap on “Open settings” is needed.
   - Grant permission and return: the already verified APK opens directly in the **system package installer**, without re-downloading or another in-app confirmation; Android still requires confirmation of the installation itself.
   - Deny permission and return: stay on “Permission required”; the app must **not reopen Settings automatically** or launch an installer. The user can manually press “Open settings” to try again. Proxy and other settings remain usable.
   - Grant permission before attempting an update: after confirmation the installer opens directly, without showing the permission settings screen.
   - Unavailable or blocked Settings activity: show the explicit system-settings error without opening any unverified APK.
   - Run `AppUpdateInstallPermissionPolicyTest` for the grant/deny/return decision matrix; this is not a substitute for the physical-device flow.
8. Confirm Android's own installer dialog; test Cancel without data loss. Repeat, confirm install, restart the application and record updated installed versionCode.
9. Verify saved settings and route policies, Workers and AWG/WARP remain unchanged; check Telegram MTProto connectivity before and after and that service startup is not blocked by update checks.
10. Test old com.amurcanov.tgwsproxy installs separately: this app has a different applicationId and cannot be updated in place.
11. On Android 9 / BlackView BV5900 repeat if device available (#128).

## Automated test evidence — 2026-11-11

- GitHub Actions [CI run #43076343661](https://github.com/Regstar2/tg-ws-proxy-android/actions/runs/43076343661) on source commit `498b516fb32421bca2936e6f8e56f22ba93a8b12`: **SUCCESS** for Worker transport regression (Go race tests and Node.js Worker handler tests) and Windows CI (`scripts/ci.ps1`). The issue-100-specific signed-APK job was correctly **SKIPPED**, as it targets another PR.
- Local Windows Android run at the same source commit: `./gradlew.bat --offline --build-cache :app:testDebugUnitTest :app:lintDebug :app:assembleDebugAndroidTest` — **BUILD SUCCESSFUL in 1m 6s**. The AndroidTest APK was compiled and packaged, **not installed/executed as instrumentation**.
- On-device core flow: user reported successful signed-debug `1.11.6-debug` / code 62 to official stable `v1.11.7` / code 63 update on Xiaomi; afterward `adb shell dumpsys package io.github.regstar2.tgwsproxy` showed `versionCode=63`, `versionName=1.11.7`. The user later stated that the app was working and requested documentation/merge; this is user acceptance feedback, **not evidence that each distinct negative-path scenario below was exercised**.
- A local Windows Defender alert (`Trojan:Win64/Resmus.HM!MTB`) prevented execution of a generated `mtproxyfrontend.test.exe` in a previous full local CI attempt; the same repository's native tests passed in GitHub Actions. The specific Defender detection has not been investigated or established as false positive. Do not disable security tools to reproduce it.
- **Still not directly evidenced:** two independently release-**built** signed APKs used for update acceptance; saving and verifying actual user route/Worker/AWG/WARP preferences across upgrade; fresh permission denial/grant; process background/notification cancellation; corrupt metadata/APK and network failure UI; direct instrumentation execution on device; Android 9 edge-case (#128). The exact security-related release gates in Issue #139 remain in force; do not mark these PASS by inference.

## Device evidence — 2026-11-11 (partial)

- **Source tested:** `12f87dbdd6a97ffd029fcdc6f0965de12e45ef0d`, signed **debug** APK built from current PR source with temporary `versionCode=62` / `versionName=1.11.6-debug`, existing release keystore (`TGWS_SIGNED_WORKER_SMOKE=1`); temporary Gradle edits restored. Windows `:app:assembleDebug`: **BUILD SUCCESSFUL in 1m 26s** (36 tasks: 15 executed, 21 up-to-date). The APK was rejected for regular downgrade over installed code 63; subsequent downgrade/install was performed separately, but no complete successful install log was recorded in chat.
- **Quick update smoke:** user explicitly confirmed the in-app update flow as working ("да норм"). Afterward the connected Android device returned `versionCode=63 minSdk=26 targetSdk=35` and `versionName=1.11.7` from `adb shell dumpsys package io.github.regstar2.tgwsproxy`. **PASS for observed final installed version and user acceptance of the main path.**
- **Not demonstrated:** both endpoints as release-build APKs (source was signed debug), automatic permission handoff on fresh permission, user denial/no settings loop, background notification/cancel, corrupt APK and network failures, prior user settings/worker/AWG/WARP preservation, and Android 9 smoke. No screenshots or sanitized device logs were provided for these cases.
- **CI:** full project CI passed on the then-current source HEAD `498b516fb32421bca2936e6f8e56f22ba93a8b12`; if further commits are added, re-run CI for the new HEAD. Passing tests do not replace unverified mandatory device/release-build acceptance.

## Environment-specific constraints

- No automatic background install, root or Accessibility.
- The downloader follows Android's HttpURLConnection system network/proxy selection without a hidden explicit Direct retry. The application has no verified unified System/Direct/Custom outgoing-HTTP proxy selector yet; compare network behavior against NETWORK_PROXY_STANDARD before closing #139.
- Signature key rotation is not supported automatically: mismatch fails closed until Android signing lineage verification is implemented.
- Interrupted updates never reuse an APK from a prior process; app-private .part and previous verified cache entries are cleaned.

Record actual test command, HEAD, device serial (redacted), source/target versionCode, result and evidence. **Do not close #139 or merge #144 as complete before these mandatory checks.**
