# Testing

Manual checklist for validating builds and routing. Automated project checks are available through `.\scripts\ci.ps1`.

The currently published application version is `1.10.12`; `v1.10.13` is the active development target. Do not mark a v1.10.13 check as passed unless it was actually run against the corresponding integrated build.

## Automated checks

Preferred local/CI entry point:

```powershell
.\scripts\ci.ps1
```

The script covers Go module verification, native Go tests, Android unit tests, debug APK assembly, expected APK/native-library outputs and packaged-resource audit.

Individual commands remain useful for diagnosis:

```powershell
.\gradlew.bat testDebugUnitTest
.\gradlew.bat assembleDebug

Push-Location native\tgwsproxy
go test ./...
Pop-Location
```

If a command cannot run in the current environment, record that limitation instead of treating the check as successful.

## GitHub automation smoke

For changes that affect `.github/workflows/` or `scripts/ci.ps1`:

- [ ] owner-created same-repository PR starts **Trusted CI** on the self-hosted `Windows`/`X64` runner;
- [ ] Trusted CI checks out the PR head and `.\scripts\ci.ps1` completes successfully;
- [ ] owner-created Issue/PR is added to Development Project #2 when `ADD_TO_PROJECT_PAT` is configured;
- [ ] external/fork PR code is not executed on the persistent self-hosted runner;
- [ ] no Project PAT or signing secret is printed in logs.

Release automation is not considered functionally verified by a debug CI run. Before an automated public release, separately validate release signing, `scripts/release.ps1`, the exact release tag and the generated `dist/` artifacts.

Automation contract: [../development/github-automation.md](../development/github-automation.md).

## Basic build

- [ ] Gradle sync / `.\gradlew.bat assembleDebug`
- [ ] Install APK (`adb install -r app\build\outputs\apk\debug\app-debug.apk` or `scripts\build-apk.ps1`)
- [ ] Launch app without crash

## Basic runtime

- [ ] Start proxy
- [ ] Stop proxy
- [ ] Restart app with proxy stopped
- [ ] Foreground notification visible while running (if enabled)
- [ ] Logcat shows `TgWsProxy` lines when runtime logging is enabled

## Mobile network

- [ ] Disable Wi-Fi, use mobile data
- [ ] Start proxy
- [ ] Open Telegram, load chats and media
- [ ] Diagnostics/logs identify the mobile network policy and actual route
- [ ] UI: configured route, current route and transport are consistent with diagnostics

## Wi-Fi

- [ ] Enable Wi-Fi
- [ ] Start proxy (or allow the runtime to reconfigure on network switch)
- [ ] Open Telegram, load chats and media
- [ ] Diagnostics/logs identify the Wi-Fi policy and actual route

## Settings

- [ ] Change per-network route policy (enable/disable direct, Worker, CF Proxy, TCP where supported)
- [ ] Restart app — settings persist
- [ ] Apply the recommended/default configuration where applicable
- [ ] Reset adaptive/runtime statistics where exposed — proxy remains usable

## Route status UI

- [ ] **Mode/policy** matches the configured strategy
- [ ] **Current route** shows the actual route kind (for example Cloudflare Proxy), not only the transport name `websocket`
- [ ] **Transport** shows WebSocket or TCP separately from route kind
- [ ] Disabled routes are not reported as active solely because of stale diagnostics

## Diagnostics

- [ ] Route probe / connection tests respect disabled routes
- [ ] External-domain failure is reported as a route/domain result rather than a claim that every possible route is unavailable
- [ ] Export runtime log / diagnostics
- [ ] Review export manually for secrets, proxy credentials, sensitive URLs/IPs and other private data before sharing

## Network switch / reconnect

- [ ] Start with Telegram working through Wi-Fi
- [ ] Switch to mobile data and verify reconnect
- [ ] Switch back to Wi-Fi and verify reconnect
- [ ] Stop/start the proxy after a failed route attempt
- [ ] Verify the local listener is usable after reconnect/restart

## Release smoke (before tagging)

Release process: [../releases/release.md](../releases/release.md).  
Detailed checklist: [../releases/RELEASE_CHECKLIST.md](../releases/RELEASE_CHECKLIST.md).

A v1.10.13 release candidate must additionally satisfy the acceptance criteria of the v1.10.13 GitHub Issues included in the release scope.
