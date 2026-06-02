# Testing

Manual checklist for validating builds and routing. Automated unit tests: `.\gradlew.bat testDebugUnitTest` (when the Gradle test runner works in your environment).

## Basic build

- [ ] Gradle sync / `.\gradlew.bat assembleDebug`
- [ ] Install APK (`adb install -r app\build\outputs\apk\debug\app-debug.apk` or `scripts\build-apk.ps1`)
- [ ] Launch app without crash

## Basic runtime

- [ ] Start proxy
- [ ] Stop proxy
- [ ] Restart app with proxy stopped
- [ ] Foreground notification visible while running (if enabled)
- [ ] Logcat shows `TgWsProxy` lines

## Mobile network

- [ ] Disable Wi‑Fi, use mobile data
- [ ] Start proxy
- [ ] Open Telegram, load chats and media
- [ ] Logcat: `route_policy network=MOBILE ...`
- [ ] UI: mode, current route, transport look consistent with logs

## Wi‑Fi

- [ ] Enable Wi‑Fi
- [ ] Start proxy (or reconfigure on network switch)
- [ ] Open Telegram, load chats and media
- [ ] Logcat: `route_policy network=WIFI ...`

## Settings

- [ ] Change per-network route policy (enable/disable direct, worker, CF, TCP)
- [ ] Restart app — settings persist
- [ ] Apply “Recommended” preset if used — matches expected defaults
- [ ] Reset adaptive stats (if used) — does not break proxy

## Route status UI

- [ ] **Mode** matches selected strategy (e.g. CF first, Worker first)
- [ ] **Current route** shows route kind label (e.g. Cloudflare proxy), not bare “websocket”
- [ ] **Transport** shows WebSocket or TCP separately
- [ ] After disabling direct, stale direct timeouts do not flip UI to Direct WebSocket

## Diagnostics

- [ ] Route probe / connection tests respect disabled routes
- [ ] Export runtime log / diagnostics (no secrets in export)

## Release smoke (before tagging)

See [release.md](release.md) and [RELEASE_CHECKLIST.md](RELEASE_CHECKLIST.md).
