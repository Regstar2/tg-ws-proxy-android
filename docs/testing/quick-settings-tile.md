# Android Quick Settings tile smoke test — issue #109

## Environment

Use an Android 8+ arm64-v8a device or emulator with the debug APK. Record Android version, OEM skin, network type, and APK commit. CI JVM tests do not validate SystemUI integration.

## Acceptance steps

1. Install APK, edit Quick Settings, manually add TgWsProxy. Verify readable localized label and monochrome icon.
2. Clear app data, then tap tile without opening the app. Expected: launch MainActivity; no local proxy starts, tile remains inactive.
3. Configure MTProto or SOCKS5 in MainActivity and start once. Wait for a confirmed `RUNNING` status. Close Activity. Tap tile to stop; verify tile inactive and foreground notification removed.
4. Tap again to start. Verify no Activity opens, a single ProxyService foreground notification appears, tile becomes active only after frontend success.
5. Start/stop via MainActivity and foreground notification. Reopen Quick Settings and verify the same state.
6. Rapidly tap the tile; repeat while frontend is starting and reconnecting. Verify a single native runtime, no duplicate notifications, no stale active state.
7. Deliberately invalidate SOCKS5 runtime or MTProto preferences, change routing policy, switch Wi-Fi/mobile or alter AWG profile. Tap from stopped. Expected: open app for review, no fallback to outdated routing tokens.
8. Test no network, frontend port conflict, and OS foreground-service denial (if reproducible). Tile must never show active for a failed start. Reopen app to inspect error.
9. Kill the app process (avoid force-stop for service tests), reopen the shade, reboot with autostart enabled and disabled, then compare tile against the real service and notification.
10. Repeat on Android 8, 13 and 14+ where available; test MIUI/HyperOS separately. Record unsupported or untested variants rather than claiming compatibility.

On Android 14+ an invalid configuration opens MainActivity via `startActivityAndCollapse(PendingIntent)`; older supported versions use the Intent overload.

## Build and JVM tests

```powershell
.\gradlew.bat :app:testDebugUnitTest :app:assembleDebug
```

The native preBuild task runs on the repository's supported Windows toolchain. Do not merge on an unknown/failed CI or device acceptance gate.
