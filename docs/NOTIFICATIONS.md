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

- `proxy_status` — low importance, no sound (ongoing status)
- `proxy_alerts` — optional future important errors

## Planned

Quick Settings Tile (start/stop/reconnect) is planned for a later release (v1.6.1 / v1.7).
