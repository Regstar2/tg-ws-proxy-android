# Testing

Manual checklist for validating builds and routing. Automated project checks are available through `.\scripts\ci.ps1`.

The latest published prerelease is `v1.11.0-beta.1`; `v1.11.0` is the current stable release target. Do not mark a stable check as passed unless it was run against the corresponding integrated build.

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

- [ ] a repository PR starts **CI** on GitHub-hosted `windows-latest`;
- [ ] CI executes `.\scripts\ci.ps1` successfully without using the persistent owner runner;
- [ ] an Issue/PR is added to Development Project #2 when `ADD_TO_PROJECT_PAT` is configured;
- [ ] Project Sync runs on GitHub-hosted `ubuntu-latest` and does not checkout/execute PR code;
- [ ] external/fork PR code is never executed on the persistent self-hosted runner;
- [ ] no Project PAT or signing secret is printed in logs;
- [ ] the owner-controlled release workflow runs the exact release tag on GitHub-hosted `windows-latest` and restores signing material only into the temporary runner directory.

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

A v1.11.0 stable candidate must additionally verify the AWG/WARP and provisioning paths introduced by #84, #86 and #88:

- [ ] create and validate an automatic WARP profile;
- [ ] verify real Telegram MTProto traffic through `awg_warp`;
- [ ] verify existing-profile → direct API → custom Worker → built-in Worker bootstrap ordering as applicable;
- [ ] confirm the three built-in project Workers are provisioning-only and never enter the Telegram Worker Pool;
- [ ] verify built-in Worker opt-out;
- [ ] install the signed stable APK over both `v1.10.14` and `v1.11.0-beta.1`.
