# Release checklist — v1.11.0 stable

## Metadata / documentation

- [ ] `releaseVersionName = 1.11.0`, `releaseVersionCode = 54`.
- [ ] `.\scripts\audit-release.ps1 -ExpectedVersion 1.11.0 -ExpectedVersionCode 54` passes.
- [ ] README RU/EN, CHANGELOG and release notes agree with source metadata.
- [ ] Canonical Worker guide is `docs/cloudflare-worker.md`; legacy path only redirects to it.
- [ ] No keystore, signing secret, local log, `.env`, `local.properties` or private governance files are tracked.
- [ ] Release branch diff contains no unrelated changes.

## Automated build

- [ ] `.\scripts\ci.ps1` passes.
- [ ] Go module verification/tests pass.
- [ ] Android unit tests pass.
- [ ] `assembleDebug` passes.
- [ ] packaged APK resource audit passes.
- [ ] release preflight accepts `v1.11.0`.

## Signed release artifact

- [ ] `.\scripts\release.ps1 -Version v1.11.0` succeeds with the release keystore.
- [ ] `apksigner verify --verbose --print-certs` succeeds.
- [ ] `dist\TgWsProxy-Android-v1.11.0-arm64-v8a.apk` exists.
- [ ] matching `.sha256` exists.
- [ ] APK reports `versionName 1.11.0` / `versionCode 54`.
- [ ] upgrade over signed `v1.10.14` succeeds without uninstall/data loss.
- [ ] upgrade over signed `v1.11.0-beta.1` succeeds without uninstall/data loss.

## Core Telegram runtime

- [ ] Start / stop / reconnect proxy.
- [ ] **Apply in Telegram** opens the local MTProto proxy configuration.
- [ ] Telegram sends and receives text messages.
- [ ] Telegram loads and sends media.
- [ ] main `cf_proxy_ws` route works.
- [ ] disabled routes are not selected.
- [ ] Wi-Fi → mobile and mobile → Wi-Fi reconfigure without manual restart.

## AWG/WARP stable path

- [ ] Existing selected WORKING AWG profile can bootstrap a fresh independent Consumer WARP registration.
- [ ] If selected profile is unavailable, other WORKING profiles are tried before direct API.
- [ ] Direct `api.cloudflareclient.com` path works where reachable.
- [ ] Automatic profile gets a new local keypair and independent registration.
- [ ] Bounded autotune does not save a candidate before two successful confirmations.
- [ ] Validation requires real Telegram MTProto `req_pq_multi → resPQ`, not only a generic tunnel handshake.
- [ ] Selected profile can carry Telegram text and media through `actual_backend=awg_warp` with no unintended fallback.
- [ ] Profile rename/config edit resets validation to **Not checked** until revalidated.
- [ ] Generated names increment (`WARP 1`, `WARP 2`, ...).

## Provisioning Worker policy

- [ ] Custom provisioning Worker can be added, checked, enabled/disabled and deleted.
- [ ] Health requires `service=warp-bootstrap` and `revision=warp-bootstrap-v1`.
- [ ] Custom Worker is tried before built-in provisioning Workers.
- [ ] The three built-in project Workers are used only for WARP profile provisioning.
- [ ] Built-in provisioning Workers never appear in or modify the Telegram `cf_worker_ws` Worker Pool.
- [ ] With **Use built-in bootstrap Workers = OFF**, no built-in endpoint is contacted.
- [ ] A failed provisioning Worker does not replace/deselect the current working AWG profile.
- [ ] Standalone provisioning Worker returns 404 for unrelated paths and cannot act as an arbitrary proxy.

## Privacy / diagnostics

- [ ] WARP private key is not sent to the Consumer API/Worker and is not present in support-safe diagnostics.
- [ ] Raw registration token, Authorization header and full `.conf` are not logged.
- [ ] Exported diagnostics do not expose proxy credentials or unrelated device/network secrets.
- [ ] Runtime/persistent logging remains disabled by default.

## UI / localization / updates

- [ ] Main UI, WARP screens, bootstrap Worker settings, Feedback and Updates reviewed in RU and EN.
- [ ] No obvious clipping or fallback-key text.
- [ ] Updates screen detects the stable release from the official GitHub Releases feed.
- [ ] No APK is silently installed.

## Publication gate

- [ ] All automated checks above pass.
- [ ] Signed APK device smoke is complete.
- [ ] Release notes contain no unverified success claims.
- [ ] Create/push exact tag `v1.11.0`.
- [ ] Release workflow publishes exactly APK + SHA-256.
- [ ] Downloaded release APK can be installed and launched.
