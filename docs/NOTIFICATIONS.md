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

## Planned

Quick Settings Tile (start/stop/reconnect) is planned for a later release (v1.6.1 / v1.7).
