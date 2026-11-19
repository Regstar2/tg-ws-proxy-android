# Testing

Manual checklist for validating builds and routing. Automated project checks are available through `.\scripts\ci.ps1`.

The latest published stable release is v1.11.9. The current source candidate is **v1.11.10/66**, including TLS validation, opt-in WS/80, notification reconnect, Telegram link copying and ARMv7/ARM64/Universal. Verify final signed APKs separately; see [release checklist](../releases/RELEASE_CHECKLIST.md).

## v1.11.10 device acceptance

- [ ] Verify Android CA trust roots, CF WSS/443, Direct WS, Worker Legacy and Chunk Relay with valid upstream certificates; controlled invalid certificates must fail.
- [ ] Test explicit WS/80 without TLS on Wi-Fi and cellular; keep WSS/443 as default.
- [ ] Test MTProto notification reconnect, MTProto/Fake TLS/SOCKS5 copied links, real message/media transfer and route fallback.
- [ ] Test ARMv7, ARM64 and Universal native/JNA on supported devices, and in-place signed upgrade from v1.11.9; emulator translation failures are not PASS.

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
- [ ] the owner-controlled release workflow runs the exact tag only on a trusted Windows self-hosted runner and restores signing material only into the runner's temporary directory; confirm cleanup and access control.

Release automation is not considered functionally verified by a debug CI run. Before an automated public release, separately validate release signing, `scripts/release.ps1`, the exact release tag and the generated `dist/` artifacts.

Automation contract: [../development/github-automation.md](../development/github-automation.md).

## Basic build

- [ ] Gradle sync / `.\gradlew.bat assembleDebug`
- [ ] Install APK (`adb install -r app\build\outputs\apk\debug\app-universal-debug.apk` or `scripts\build-apk.ps1`)
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

## Copy proxy link

- [ ] With MTProto selected, copy the link using the home-screen icon. Confirm that the clipboard contains `https://t.me/proxy` with the current host, port and secret.
- [ ] Compare the home-screen MTProto link with **Settings → Connection → Copy link**; the values must match.
- [ ] Repeat with a valid Fake TLS domain and confirm that the generated secret includes the domain encoding.
- [ ] Switch to SOCKS5 and copy the link. Confirm `https://t.me/socks?server=127.0.0.1&port=<configured_port>` with no MTProto secret.
- [ ] For an invalid connection configuration, copying must show a validation message instead of placing a malformed link in the clipboard.
- [ ] Do not attach real copied MTProto links to reports or logs: they include the connection secret.

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

## Boot autostart

- [ ] Leave **Autostart on boot** disabled, reboot the device, and verify that the proxy service does not start.
- [ ] Start the proxy once with a valid configuration, enable **Autostart on boot**, then reboot without manually opening the app.
- [ ] After `BOOT_COMPLETED`, verify that the existing foreground notification appears and the saved local proxy port is reachable.
- [ ] Open the app and verify that the UI reports the already-running `ProxyService`.
- [ ] Stop the proxy manually, verify that the autostart setting remains enabled, reboot again, and verify that the proxy starts.
- [ ] For SOCKS5, remove/clear the saved runtime configuration in a test setup and verify that boot start is skipped without a crash or restart loop.
- [ ] Verify that repeated boot delivery does not create a second proxy runtime.
- [ ] Disable autostart, reboot again, and verify that no proxy service is started.
- [ ] Review `TgWsProxy` logcat for privacy-safe `BOOT_*` events only; no domains, keys, tokens, or private runtime configuration should be logged.

## Release smoke (before tagging)

Release process: [../releases/release.md](../releases/release.md).  
Detailed checklist: [../releases/RELEASE_CHECKLIST.md](../releases/RELEASE_CHECKLIST.md).

For v1.11.7, the following route and notification checks are mandatory in addition to the generic checklist. The AWG/WARP provisioning list below is historical v1.11.0 coverage and is not automatically a new implementation requirement:

- [ ] create and validate an automatic WARP profile;
- [ ] verify real Telegram MTProto traffic through `awg_warp`;
- [ ] verify existing-profile → direct API → custom Worker → built-in Worker bootstrap ordering as applicable;
- [ ] confirm the three built-in project Workers are provisioning-only and never enter the Telegram Worker Pool;
- [ ] verify built-in Worker opt-out;
- [ ] document the separate package-ID upgrade path for releases before v1.11.6 (do not pretend a package-ID change is an in-place update).
