# Android Quick Settings tile smoke test — issue #109

## Environment

Use an Android 8+ arm64-v8a device or emulator with the debug APK. Record Android version, OEM skin, network type, and APK commit. CI JVM tests do not validate SystemUI integration.

## Acceptance steps

1. Install APK, edit Quick Settings, manually add TgWsProxy. Verify readable localized label and monochrome icon.
2. Clear app data, then tap the tile. Expected: no Activity opens, no proxy starts and the tile stays inactive with a setup label. Configure the app manually for first use.
3. Configure MTProto or SOCKS5 in MainActivity and start once. Wait for a confirmed `RUNNING` status. Close Activity. Tap tile to stop; verify tile inactive and foreground notification removed.
4. Tap again to start. Verify no Activity opens, a single ProxyService foreground notification appears, tile becomes active only after frontend success.
5. Start/stop via MainActivity and foreground notification. Reopen Quick Settings and verify the same state.
6. Rapidly tap the tile; repeat while frontend is starting and reconnecting. Verify a single native runtime, no duplicate notifications, no stale active state.
7. With the proxy confirmed as running from the main screen, stop it and tap the tile without changing network or route settings. Expected: the previously successful runtime starts even if the old preference fingerprint was invalidated by unrelated background updates. Repeat after Wi-Fi/mobile, route policy or MTProto configuration changes: the tile must reject a stale runtime. With AWG/WARP in use, do one successful app start on this version to record the profile modification timestamp; changing that profile afterward must prevent a stale tile start.
8. Test no network, frontend port conflict, and OS foreground-service denial (if reproducible). Tile must never show active for a failed start. Reopen app to inspect error.
9. Kill the app process (avoid force-stop for service tests), reopen the shade, reboot with autostart enabled and disabled, then compare tile against the real service and notification.
10. Repeat on Android 8, 13 and 14+ where available; test MIUI/HyperOS separately. Record unsupported or untested variants rather than claiming compatibility.

An invalid or missing configuration never opens MainActivity implicitly. The user can open the app separately to reconfigure.

## Diagnosing an inactive tile

After pressing the tile, a short message explains whether the proxy needs initial setup, a fresh confirmation from the app, or failed to start. A stale v1 confirmation may need a one-time normal start in the app; after that v2 confirmation ignores automatic Worker health and CF cache updates without allowing changed endpoints or routing options. On a supported device, capture only the safe tile log tag:

```powershell
adb logcat -c
# Tap the TgWsProxy Quick Settings tile, then run:
adb logcat -d -v threadtime -s ProxyQuickSettingsTile:V ProxyTileStartPolicy:V '*:S'
```

`TILE_START_REJECTED reason=SETUP_REQUIRED`: no usable previously successful start, or an incomplete saved runtime.
`CONFIRMATION_MISSING`: persisted runtime exists but cannot be safely trusted; start once from the app.
`FRONTEND_INVALID`: current MTProto/frontend settings are not valid.
`NETWORK_CHANGED`: saved runtime network tokens differ from the current network.
`ROUTE_POLICY_CHANGED`: saved route-policy tokens differ from the current effective policy.
`AWG_CHANGED`: selected AWG profile path or recorded modification time differs.
`CONFIG_CHANGED`: the stored v1/v2 confirmation does not match current settings (including Worker endpoints); do not reuse the stale runtime.
`VALIDATION_FAILED`: storage/validation failed or a signature could not be computed. A separate `TILE_VALIDATION_FAILED` line can include **exception class only**, not values. This is **not** a declaration that settings changed.
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
4. Negative cases: after a confirmed start and stop, change network Wi-Fi↔mobile, routing preference, Worker endpoint/selection, MTProto port/secret or AWG profile where available. Expect no `TILE_START_REQUESTED` and a meaningful rejection code; start from the app to confirm changed settings.
5. Tap rapidly and during STARTING/RECONNECTING, and trigger an intentional frontend error where safe. Expect a single native runtime, no duplicate service notification, and never ACTIVE while the runtime is not RUNNING.
6. Test foreground notification controls and MTProto start directly from the app for regressions. Long press is issue #114, not a completion criterion for #122.

The available JVM tests do **not** prove Android 16 SystemUI integration. Leave #122 open until device acceptance is confirmed. Safe collection tags: `ProxyQuickSettingsTile` and `ProxyTileStartPolicy`; review and redact exported artifacts before external sharing.
