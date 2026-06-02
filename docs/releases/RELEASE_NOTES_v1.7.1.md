# Release notes v1.7.1

## Added

- Added network change monitoring while the proxy is running.
- Added automatic proxy reconfigure when active network type changes.
- Added ACTION_RECONFIGURE for ProxyService.
- Added shared runtime config factory for start and reconfigure paths.
- Added network profile change detection tests.

## Behavior

When the proxy is running, switching between Wi-Fi and Mobile can now rebuild the effective route policy and restart the native runtime with the correct route configuration.

The foreground service stays active during reconfigure.

Manual reconnect still uses the last saved runtime config.

Wi-Fi/Mobile route policies from v1.7.0 are applied automatically on the next network switch or proxy start.

## Next

- Show effective route policy in diagnostics.
- Export effective route policy in runtime logs.
- Add detailed reconfigure reason/status to the app UI.
