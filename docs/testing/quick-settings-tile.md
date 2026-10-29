# Android Quick Settings tile smoke test — issue #109

## Environment

Use an Android 8+ arm64-v8a device or emulator with the debug APK. Record Android version, OEM skin, network type, and APK commit. CI JVM tests do not validate SystemUI integration.

## Acceptance steps

1. Install APK, edit Quick Settings, manually add TgWsProxy. Verify readable localized label and monochrome icon.
2. Clear app data, then tap the tile. Expected: no Activity opens, no proxy starts and the tile stays inactive with a setup label. Configure the app manually for first use.
3. Configure MTProto or SOCKS5 in MainActivity and start once. Wait for a confirmed `RUNNING` status. Close Activity. Tap tile to stop; verify tile inactive and foreground notification removed.
4. Tap again to start. The tile should promptly display a transitioning subtitle while the current configuration is rebuilt off the main thread; it must remain INACTIVE until the frontend actually reaches RUNNING. Verify no Activity opens and a single ProxyService foreground notification appears.
5. Start/stop via MainActivity and foreground notification. Reopen Quick Settings and verify the same state.
6. Rapidly tap the tile; repeat while frontend is starting and reconnecting. Verify a single native runtime, no duplicate notifications, no stale active state.
7. After one successful app start, stop and tap the tile with unchanged settings. Then stop, switch Wi-Fi/mobile or change route policy, and tap the tile **without starting from the app again**. Expected: a new runtime is built from persisted settings and the current network; the old runtime is never reused. If the new policy is usable, the tile starts. Repeat with a changed Worker endpoint or AWG/WARP profile: the new values must be used and any invalid configuration must be rejected rather than silently falling back.
8. Test no network, frontend port conflict, and OS foreground-service denial (if reproducible). Tile must never show active for a failed start. Reopen app to inspect error.
9. Kill the app process (avoid force-stop for service tests), reopen the shade, reboot with autostart enabled and disabled, then compare tile against the real service and notification.
10. Repeat on Android 8, 13 and 14+ where available; test MIUI/HyperOS separately. Record unsupported or untested variants rather than claiming compatibility.

An invalid or missing configuration never opens MainActivity implicitly. The user can open the app separately to reconfigure.

## Diagnosing an inactive tile

After pressing the tile, a short message explains whether initial setup is required, current settings are invalid, or starting failed. A successful in-app start is required **only for initial setup**, including compatible upgrade installations. Later tile starts always rebuild from persisted preferences and the current network; v1/v2 signatures are not used to reject valid changes. On a supported device, capture only the safe tile log tag:

```powershell
adb logcat -c
# Tap the TgWsProxy Quick Settings tile, then run:
adb logcat -d -v threadtime -s ProxyQuickSettingsTile:V ProxyTileStartPolicy:V ProxyTileTiming:V '*:S'
```

`TILE_START_REJECTED reason=SETUP_REQUIRED`: no previously successful app start, or the new runtime could not be built safely from current settings.
`FRONTEND_INVALID`: the current MTProto/frontend configuration is not valid.
`VALIDATION_FAILED`: storage or fresh config construction threw an exception. `TILE_VALIDATION_FAILED` includes **exception class only**.
Changed network, route policy, Worker endpoints or AWG profiles do **not** cause signature-based rejection. A valid updated configuration must be started; invalid/missing settings must never fall back to `last_runtime_ips`.
The tile should show a transitioning subtitle promptly on tap, without claiming an ACTIVE state until the service reports RUNNING. `TILE_START_PREPARED elapsed_ms=...` reports time spent preparing the fresh config off the UI thread. `FG_NOTIFICATION_SUBMITTED` on the safe `ProxyTileTiming` tag means Android accepted `startForeground()`, **not** that SystemUI has displayed the notification yet.

`TILE_START_REQUESTED` and `TILE_STOP_REQUESTED` indicate dispatch to the existing `ProxyService`, not successful completion. Confirm the foreground notification and real tile state. `TILE_COMMAND_REJECTED` indicates an Android service start failure.
No proxy tokens, network profile identifiers, secrets, IPs, Worker URLs or hostnames are logged by the tile.

## Build and JVM tests

```powershell
.\gradlew.bat :app:testDebugUnitTest :app:assembleDebug
```

The native preBuild task runs on the repository's supported Windows toolchain. Do not merge on an unknown/failed CI or device acceptance gate.

## v1.11.5 / Issue #122 regression acceptance

Keep the original #121 report intact. Confirm Android 16 (ideally Realme GT Neo 6) **on a build with the same signing certificate as the installed 1.11.4 APK**, so the upgrade preserves data. Do not uninstall the app or clear data for the upgrade test. If signing certificates differ, stop: a fresh debug install is not equivalent to the upgrade scenario.

Record only model, Android API, network type (Wi-Fi/mobile/unknown), frontend (MTProto/SOCKS5), enabled route **kind**, whether this is an upgrade/fresh install, and the observed outcome. Do not record network IDs, server addresses, secrets, fingerprints, profiles or raw runtime settings.

1. Confirm version 1.11.4 (60), if available: proxy successfully starts from the app, then stops; observe whether short tile tap is rejected and capture the safe tile tags. This step is `NOT_RUN` if the old signed APK is unavailable.
2. Install the candidate **over 1.11.4 with app data preserved**. Start via MainActivity, wait for actual `RUNNING`, then stop. Close only the Activity. With the network and settings unchanged, short-tap tile. Expect `TILE_START_REQUESTED`, one foreground notification, actual `RUNNING`, and ACTIVE tile. Tap again and expect STOPPED, notification gone and INACTIVE tile. Repeat after recreating the app process (without Android force-stop).
3. Repeat on SOCKS5 if configured, and if possible on a second Android version/device. For fresh install, do not trust default MTProto before a successful in-app start.
4. Freshness: after a successful start and stop, switch network Wi-Fi↔mobile or edit the route policy, Worker endpoint/selection or AWG profile. Do **not** start from the app again; tap the tile. Expect `TILE_START_REQUESTED` and an actual RUNNING service when the modified settings are usable. Verify the new network and route are selected via the application's diagnostics (do not export sensitive tokens). MTProto valid port/secret changes must likewise use the new values. A stale runtime must never be reused.
5. Negative cases: before first successful setup, or with invalid current port, empty runtime/DC settings, invalid MTProto secret or inaccessible AWG profile, tap the tile. Expect a safe `SETUP_REQUIRED`, `FRONTEND_INVALID` or runtime error and no successful start with the older snapshot.
6. Tap rapidly and during STARTING/RECONNECTING, and trigger an intentional frontend error where safe. Expect a single native runtime, no duplicate service notification, and never ACTIVE while the runtime is not RUNNING.
7. Test foreground notification controls and MTProto start directly from the app for regressions. Long press is issue #114, not a completion criterion for #122.

The available JVM tests do **not** prove Android 16 SystemUI integration. Leave #122 open until device acceptance is confirmed. Safe collection tags: `ProxyQuickSettingsTile` and `ProxyTileStartPolicy`; review and redact exported artifacts before external sharing.
