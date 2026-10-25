# Quick Settings tile long-press — Issue #114

## Prerequisites

- Install the candidate debug APK on an Android 14 Xiaomi/HyperOS device.
- Configure and successfully start TgWsProxy at least once so a short tile tap can reuse the confirmed configuration.
- Add the **TgWsProxy** tile to the Quick Settings panel if it is not present.

## Device smoke checklist

1. **Proxy stopped, app closed:** close the app UI (do not uninstall), expand Quick Settings and long-press the TgWsProxy tile. Expected: MainActivity opens on the main page, not Android *App info*; the proxy remains stopped.
2. **Proxy stopped, app already open on Settings:** keep the app on its Settings page, open Quick Settings and long-press the tile. Expected: the existing MainActivity returns to its main page; no start/stop request.
3. **Proxy running, app closed:** start the proxy, leave/close only the app UI (do not force-stop the package because this can stop the service), then long-press the tile. Expected: main page opens and the running proxy remains running.
4. **Proxy running, app open on Settings:** repeat the long-press. Expected: main page opens without changing proxy state.
5. **Short tap, both states:** tap the tile once with the proxy running and once with it stopped. Expected: one stop and one start request respectively; no extra toggle after long-press.
6. **Launcher:** open the application normally from its icon. Expected: MainActivity still opens.
7. **Repeat/cold start:** repeat the stopped-case long-press after the app process has been closed; record the firmware version and whether SystemUI honored the tile preferences action.

Optional observation on a connected PC:

```powershell
adb logcat -c
adb logcat -v time -s ProxyQuickSettingsTile:I
```

A long-press must not produce `TILE_START_REQUESTED` or `TILE_STOP_REQUESTED` in this log. These messages are only expected from short taps. Record the actual result and any OEM-specific differences.

**Acceptance status:** NOT RUN — requires an actual Xiaomi/Android 14 device. A successful merged-manifest test or emulator intent resolution alone does not establish SystemUI long-press behavior.
