# Release checklist

## Build

- [ ] `go test ./tgwsroute/...`
- [ ] `./gradlew lint`
- [ ] `./gradlew assembleDebug`
- [ ] `./gradlew assembleRelease` (if signing configured)

## Runtime

- [ ] Start / stop / reconnect proxy
- [ ] Telegram connects via SOCKS5 `127.0.0.1:1081`
- [ ] Worker test OK
- [ ] CF test OK or clear error
- [ ] Auto / Worker only / CF only modes

## Notification

- [ ] Foreground notification visible while running
- [ ] Tap notification opens app
- [ ] Stop / Start / Reconnect actions work
- [ ] Speed and latency when metrics enabled
- [ ] Minimal mode hides metrics
- [ ] Open Android notification settings

## Lifecycle

- [ ] Screen rotation
- [ ] App background / resume
- [ ] Network switch Wi-Fi ↔ mobile

## Privacy

- [ ] Log export masks domains when configured
- [ ] No raw SSID in diagnostics

## UI

- [ ] Onboarding on first launch
- [ ] Help / About links open repository
- [ ] Worker domain URL normalized to hostname
