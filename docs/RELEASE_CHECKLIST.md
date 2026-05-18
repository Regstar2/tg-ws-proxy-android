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
- [ ] Application icon resource: `ic_launcher_tgwsproxy_v2` (manifest)
- [ ] Round icon: `ic_launcher_tgwsproxy_round_v2`
- [ ] Notification small: `ic_notification_small_v2` (vector)
- [ ] Notification large: `notification_app_icon_v2` (not PackageManager)
- [ ] Channel: `tgwsproxy_service_status_v3`
- [ ] `scripts/audit-apk-icons.ps1` reports no legacy filenames in APK
- [ ] `adb dumpsys package` shows expected `versionCode` after install
- [ ] MIUI: uninstall + reinstall (+ reboot if icon still cached)
- [ ] Tap notification opens app
- [ ] Stop / Start / Reconnect / Open actions work
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
