# Release checklist

> Short workflow: [release.md](release.md). This file is the detailed checklist.

## Metadata / repository audit

- [ ] `releaseVersionName = 1.10.13` and `releaseVersionCode = 51`.
- [ ] `.\scripts\audit-release.ps1 -ExpectedVersion 1.10.13 -ExpectedVersionCode 51` passes.
- [ ] RU/default-English resource keys have parity.
- [ ] README RU/EN, CHANGELOG, version docs and `RELEASE_NOTES_v1.10.13.md` agree with the source metadata.
- [ ] No obsolete `Regstar2/TgWsProxy_Android` repository URLs remain in README RU/EN.
- [ ] No private governance/tool state, local logs, environment files, signing material or keystores are tracked.
- [ ] Final branch diff contains no unrelated changes.

## Automated build

- [ ] `.\scripts\ci.ps1` passes.
- [ ] `go mod verify` passes in `native/tgwsproxy`.
- [ ] `go test ./...` passes in `native/tgwsproxy`.
- [ ] `testDebugUnitTest` passes.
- [ ] `assembleDebug` passes.
- [ ] packaged APK icon/resource audit passes.
- [ ] debug APK reports `versionName 1.10.13-debug` / `versionCode 52`.

## Signed release artifact

- [ ] Local release signing variables/keystore are configured outside Git.
- [ ] `.\scripts\release.ps1 -Version v1.10.13` succeeds.
- [ ] `apksigner verify --verbose --print-certs` succeeds inside the release script.
- [ ] `dist\TgWsProxy-Android-v1.10.13-arm64-v8a.apk` exists.
- [ ] matching `.sha256` exists and contains the artifact SHA-256.
- [ ] signed release APK reports `versionName 1.10.13` / `versionCode 51`.

## Core runtime

- [ ] Start proxy.
- [ ] Stop proxy.
- [ ] Reconnect proxy.
- [ ] MTProto frontend starts on the configured local port (default `127.0.0.1:1443`).
- [ ] **Apply in Telegram** opens the MTProto proxy configuration.
- [ ] Telegram connects and receives text messages.
- [ ] Telegram sends text messages.
- [ ] Telegram loads images/media.
- [ ] Telegram sends media where practical.
- [ ] Main `cf_proxy_ws` route works.
- [ ] Disabled routes are not selected.
- [ ] Direct/TCP fallback behavior remains consistent with the saved route policy.
- [ ] Worker route remains optional and does not become the default unexpectedly.

## Network / lifecycle

- [ ] Proxy works on Wi-Fi.
- [ ] Proxy works on mobile data.
- [ ] Start on Wi-Fi → switch to mobile → runtime reconfigures without manual restart.
- [ ] Start on mobile → switch to Wi-Fi → runtime reconfigures without manual restart.
- [ ] Foreground notification remains visible during reconfigure.
- [ ] Background/resume does not stop the proxy unexpectedly.
- [ ] Screen rotation does not break settings/runtime state.

## Diagnostics / privacy

- [ ] Route diagnostics opens and completes expected checks.
- [ ] Diagnostic report can be copied/shared.
- [ ] Exported diagnostics do not expose raw proxy secrets/tokens.
- [ ] Exported diagnostics do not expose raw SSID or SIM operator.
- [ ] Exported diagnostics do not expose full sensitive domains when masking is expected.
- [ ] Runtime/persistent logging remains disabled by default.

## Feedback

- [ ] **Settings → Feedback / Обратная связь** opens a dedicated screen.
- [ ] Report bug opens this repository's bug Issue Form.
- [ ] Request feature opens this repository's feature Issue Form.
- [ ] Copied helper context contains only app version/code, Android release/SDK and manufacturer/model.
- [ ] No logs, proxy credentials, Telegram data, IPs or secrets are auto-attached.
- [ ] Opening Feedback/browser while proxy is running does not stop/reconfigure it.

## Updates

- [ ] **Settings → Updates / Обновления** opens a dedicated screen.
- [ ] Installed version/code are shown.
- [ ] Automatic check starts only after the Updates screen opens.
- [ ] Manual **Check for updates** works.
- [ ] Release notes render as cleaned compact text; full notes can be expanded/collapsed.
- [ ] Available update action opens only `Regstar2/tg-ws-proxy-android` official GitHub Release page.
- [ ] Offline/timeout/API failures remain local UI errors and do not affect proxy connectivity.
- [ ] No APK is silently downloaded or installed.

## Localization / UI

- [ ] Main UI reviewed in Russian.
- [ ] Main UI reviewed in English/default fallback.
- [ ] Feedback reviewed in RU/EN.
- [ ] Updates reviewed in RU/EN.
- [ ] No obvious unintended hardcoded user-facing strings are visible.
- [ ] Back navigation from Feedback and Updates works.

## Notification / packaged resources

- [ ] Foreground notification visible while running.
- [ ] Application icon resource: `ic_launcher_tgwsproxy_v2`.
- [ ] Round icon: `ic_launcher_tgwsproxy_round_v2`.
- [ ] Notification small icon: `ic_notification_small_v2`.
- [ ] Notification large icon: `notification_app_icon_v2`.
- [ ] `scripts/audit-apk-icons.ps1` reports no legacy filenames in APK.
- [ ] Tap notification opens app.
- [ ] Stop / Start / Reconnect / Open notification actions work.

## Publication gate

- [ ] Issue #6 manual acceptance is complete/closed or explicitly resolved as part of this final acceptance.
- [ ] Issue #7 manual proxy acceptance is recorded.
- [ ] Signed release artifact is verified.
- [ ] Only then create/push tag `v1.10.13` and allow the owner-controlled release workflow to publish.
