# Foreground notifications (v1.6.0)

TgWsProxy runs as an Android foreground service while the local SOCKS5 proxy is active. Android requires a **visible notification** for foreground services.

TgWsProxy cannot fully remove the notification while the proxy is running. You can make it **compact** or **minimal**, and open Android notification settings for the app channel.

## Notification actions

When the proxy is running:

- **Stop** — stops the proxy service
- **Reconnect** — restops runtime connections without changing settings
- **Open** — opens the app

When stopped:

- **Start** — starts the proxy with the last saved configuration
- **Open** — opens the app

Tap the notification body to open the app (single-top, no duplicate stack).

## Icons

All icons are generated from project root `icon.png` via `scripts/generate-icons.py` (runs on `preBuild`).

| Role | Resource (v2) | Notes |
|------|----------------|--------|
| Launcher | `@mipmap/ic_launcher_tgwsproxy_v2` | Adaptive only — no legacy `ic_launcher.png` |
| Round launcher | `@mipmap/ic_launcher_tgwsproxy_round_v2` | Same foreground as launcher |
| Notification small | `@drawable/ic_notification_small_v2` | Vector monochrome — status bar / MIUI badge |
| Notification large | `@drawable/notification_app_icon_v2` | Full-color from `icon.png` via `BitmapFactory` |

Channel: `tgwsproxy_service_status_v3`. Notification id: `3`.

Do **not** use `PackageManager.getApplicationIcon()` for notification large icon.

### MIUI cache

If the old icon remains after updating:

1. Uninstall the app completely.
2. Reboot the device (recommended on MIUI).
3. Install the new APK (`adb uninstall` then `adb install`, not only `-r` while testing).
4. Start the proxy and confirm channel `tgwsproxy_service_status_v3` in system notification settings.

Audit APK: `powershell -File scripts/audit-apk-icons.ps1`

## Metrics

When enabled in Settings → Notifications:

- **Speed** — rolling average from native byte counters (about every 2–3 seconds)
- **Latency** — last successful route connect latency when available
- **Connections** — runtime session counter from native stats

## Publication stability

The foreground notification keeps ID `3` and channel `tgwsproxy_service_status_v3`
for the lifetime of a service session. Rebuilding it retains the timestamp from
the first `startForeground` call (`Notification.when`); that timestamp is not
displayed in the notification.

The runtime metrics sampler continues to run every 2 seconds in normal/compact
mode or 3 seconds in minimal mode for the app's UI and diagnostics.
Notification publication is separate: an unchanged presentation does not
call `NotificationManager.notify`, and changed periodic content is limited to
one update per 3 seconds. Start, reconnect and other lifecycle status changes
can update immediately; duplicate content remains suppressed. Notification
presentation includes visible text, expanded text, display mode and available
actions. Disabling notification metrics leaves sampling enabled for the app
but prevents new notifications when the displayed presentation is unchanged.

Foreground promotion runs once per active session. Stopping the service
removes the mandatory foreground notification and clears the publication
session, so a later start obtains a new timestamp.

**Xiaomi / Android 14 acceptance (issue #115):** with the proxy running
alongside a VPN that updates its own speed notification, inspect both the status
bar icons and notification cards for several minutes, first with notification
metrics enabled, then with them disabled. Also check reconnect, Stop, and
subsequent Start. Notification ranking is ultimately OS/OEM-controlled,
so this device smoke must pass before closing the issue.

## Display modes

| Mode | Behavior |
|------|----------|
| Normal | Title, status, mode, route, speed, latency, expanded details, actions |
| Compact | Shorter content; compact speed format |
| Minimal | Short status only; fewer actions |

## Channels

- `tgwsproxy_service_status_v2` — low importance, no sound (ongoing status)
- `tgwsproxy_alerts_v2` — optional important errors

Legacy channels `proxy_status` / `proxy_alerts` are deleted on first launch after update (MIUI caches notification artwork per channel id).

## Quick Settings tile (v1.11.3)

The system tile `ProxyQuickSettingsTileService` is a separate Android `TileService`, protected by `BIND_QUICK_SETTINGS_TILE`. It is an active tile: service transitions call `TileService.requestListeningState` and the tile additionally observes `ProxyRuntimeState.uiMetrics` while listening. No independent polling or proxy instance exists.

Only `ProxyServiceStatus.RUNNING` **and** `ProxyService.isRunning == true` render as ACTIVE. STARTING, RECONNECTING, STOPPED, and ERROR never show ACTIVE. In a new process the state starts as STOPPED; saved desired state does not imply a running proxy.

Tile start is fail-closed: `ProxyTileStartPolicy` compares the saved, previously successful frontend configuration with the current network profile, routing policy and relevant user preferences. The tile first validates the last successfully started runtime against the active network and effective route policy, then verifies the current frontend settings. A valid previously running runtime is reusable even when an unrelated preference changed and its stored fingerprint no longer matches. SHA-256 fingerprints remain supported for configurations that lack embedded route metadata. AWG/WARP profiles additionally require a recorded modification timestamp from a successful start on the current version; changed profiles must be re-confirmed in the app. The tile dispatches to the existing ProxyService via ACTION_START/ACTION_STOP. Rejected starts never silently open MainActivity: a short localized message and a non-sensitive `ProxyQuickSettingsTile` Logcat reason identify missing or changed configuration, and the tile stays inactive. A failed foreground-service start releases the click gate. No secrets, hosts, routes or tokens are written to tile logs.

The foreground notification remains mandatory while running. The tile is added manually by the user, and OEM-specific panel behavior is not guaranteed. Device acceptance is described in `docs/testing/quick-settings-tile.md`.


