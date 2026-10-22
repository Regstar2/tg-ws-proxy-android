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
7. Upgrade from an older version with a previously successful start and matching saved network/route tokens. Tap while stopped. Expected: start without opening Activity and establish a new confirmation signature. Then change Wi-Fi/mobile, route policy, MTProto preferences or AWG profile. Expected: no launch with stale tokens; tile stays inactive and Activity is not opened automatically.
8. Test no network, frontend port conflict, and OS foreground-service denial (if reproducible). Tile must never show active for a failed start. Reopen app to inspect error.
9. Kill the app process (avoid force-stop for service tests), reopen the shade, reboot with autostart enabled and disabled, then compare tile against the real service and notification.
10. Repeat on Android 8, 13 and 14+ where available; test MIUI/HyperOS separately. Record unsupported or untested variants rather than claiming compatibility.

An invalid or missing configuration never opens MainActivity implicitly. The user can open the app separately to reconfigure.

## Diagnosing an inactive tile

After pressing the tile, a short message explains whether the proxy needs initial setup, a fresh confirmation from the app, or failed to start. On a supported device, capture only the safe tile log tag:

```powershell
adb logcat -c
# Tap the TgWsProxy Quick Settings tile, then run:
adb logcat -d -s ProxyQuickSettingsTile:V '*:S'
```

`TILE_START_REJECTED reason=SETUP_REQUIRED` means no valid previously started configuration exists.
`CONFIG_CHANGED` means that preferences, network profile or routing no longer match the confirmed configuration; start the proxy once from the application.
`TILE_START_REQUESTED` and `TILE_STOP_REQUESTED` indicate dispatch to the existing `ProxyService`, not successful completion. Confirm the foreground notification and real tile state. `TILE_COMMAND_REJECTED` indicates an Android service start failure.
No proxy tokens, secrets or hostnames are logged by the tile.

## Build and JVM tests

```powershell
.\gradlew.bat :app:testDebugUnitTest :app:assembleDebug
```

The native preBuild task runs on the repository's supported Windows toolchain. Do not merge on an unknown/failed CI or device acceptance gate.
