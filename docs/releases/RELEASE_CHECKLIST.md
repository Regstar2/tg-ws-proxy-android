# Release checklist

> Short workflow: [release.md](release.md). This file is the detailed checklist.

## Build

- [ ] `cd native/tgwsproxy && go test ./...`
- [ ] `./gradlew.bat testDebugUnitTest`
- [ ] `./gradlew.bat assembleDebug`
- [ ] `cd native/tgwsproxy && go test ./tgwsroute/...`
- [ ] `./gradlew lint`
- [ ] `./gradlew assembleDebug`
- [ ] `./gradlew assembleRelease` (if signing configured)

## Runtime

- [ ] Start / stop / reconnect proxy
- [ ] Telegram connects via SOCKS5 `127.0.0.1:1081`
- [ ] Worker test OK
- [ ] CF test OK or clear error
- [ ] Auto / Worker only / CF only modes
- [ ] Worker route works with pool enabled
- [ ] Worker route still works when pool size is 0
- [ ] Worker pool does not warm up when Worker route is disabled by policy
- [ ] Worker pool resets on Stop
- [ ] Worker pool resets or reconfigures on ACTION_RECONFIGURE
- [ ] CF route still works after Worker pool changes
- [ ] Direct route still works when selected by policy
- [ ] TCP fallback still works when selected by policy
- [ ] Active routes test respects Wi-Fi policy
- [ ] Active routes test respects Mobile policy
- [ ] Disabled route is shown as disabled, not failed
- [ ] Empty Worker domain is shown as not configured
- [ ] CF failures show useful hint
- [ ] Route probe report can be copied
- [ ] Route probe report is included in runtime log export after running probe
- [ ] Existing Direct/Worker/CF/TCP test buttons still work
- [ ] Worker pool hits/misses appear in runtime status
- [ ] CF pool hits/misses appear in runtime status
- [ ] Pool metrics appear in runtime log export
- [ ] Pool metrics export does not include domains or runtime tokens
- [ ] Active route diagnostics still shows disabled/not configured routes correctly
- [ ] Effective route policy card shows current network type
- [ ] Effective route policy card shows saved vs global source
- [ ] Network switch updates last reconfigure status
- [ ] Last reconfigure status does not spam Toasts

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
- [ ] Start proxy on Wi-Fi, switch to Mobile, proxy reconfigures without manual restart
- [ ] Start proxy on Mobile, switch to Wi-Fi, proxy reconfigures without manual restart
- [ ] Reconfigure keeps foreground notification visible
- [ ] Reconfigure does not show repeated Toast spam
- [ ] Manual Reconnect still uses last runtime config
- [ ] Stop proxy disables network monitor

## Privacy

- [ ] Log export masks domains when configured
- [ ] No raw SSID in diagnostics
- [ ] Copy policy diagnostics masks sensitive data
- [ ] Runtime log export includes effective route policy section
- [ ] Export does not include raw SSID, SIM operator, or full domains
- [ ] Route probe report masks domains and sensitive network data

## UI

- [ ] Onboarding on first launch
- [ ] Help / About links open repository
- [ ] Worker domain URL normalized to hostname

## Docs

- [ ] Cloudflare Worker guide contains current Worker code
- [ ] Copy Worker code action works, if implemented
