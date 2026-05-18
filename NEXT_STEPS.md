# NEXT_STEPS

Current working candidate: `Worker first` / `CF first` / `CF only` with user Worker domain or custom Cloudflare domain.

## v1.3.1 Routing hardening

- fixed Telegram IP mapping edge-cases;
- prevented Telegram-like direct passthrough in Worker-only / CF-only modes;
- added safe handling for Telegram IPv6 destinations;
- added CF domain cooldown on `429` / `403` / `5xx` / repeated timeouts;
- clarified `Direct + fallback routes` naming;
- added regression tests for route order and restricted modes.

## v1.3.2 completed

- built-in CF domain pool;
- per-domain health/cooldown;
- manual domain cooldown;
- CF diagnostics;
- cooldown reset.

## v1.4.0 next

- update CF-domain list from Flowseal GitHub;
- cached upstream list;
- manual update button.
- validation and fallback to built-in list.

## Cloudflare Worker (new in 1.3.0-worker)

- See [docs/cloudflare-worker.md](docs/cloudflare-worker.md) for setup.
- Test with in-app **Test Worker** before relying on mobile networks.
- Do not use shared/public Worker endpoints.

## TODO (not in 1.3.0-worker)

- Fake TLS masking (evaluate desktop Flowseal approach for Android SOCKS5).
- GitHub-backed CF domain list refresh and pinned TLS fetch fallback.

1. Run a 20-minute mobile usability test
- Network: mobile only.
- Mode: `CF first` first, then `CF only` if direct fallback adds noise.
- Check incoming text, outgoing text, photos, video, stickers/reactions, background resume, and network switching.
- Save runtime logs to the configured export folder.

2. Watch the bridge/session signals
- Verify that `cfproxy connected ... via=hostname` is followed by real downstream traffic.
- Check `bridge first-exit primary ...` when sessions close.
- Treat later `use of closed network connection` lines as secondary unless they are explicitly marked as primary.

3. Prepare the custom Cloudflare domain workflow
- Keep `pclead.co.uk` as the default test domain.
- Document that the default domain is shared and can fail with `429 Too Many Requests`.
- Add or polish a settings flow for user-owned domains.
- Test `kws1`, `kws2`, `kws3`, `kws4`, `kws5`, and `kws203` for resolve/connect/WS upgrade before recommending a domain.

4. Prepare release packaging
- Decide whether the public release should be debug-only for testing or signed release APK.
- Keep APK, `.so`, runtime logs, and local build outputs out of git.
- Add a short release note for the current CF Proxy baseline.

5. Do not reopen direct-path work unless CF fails
- Do not add random IP sweeps.
- Do not tune WS pool behavior before the CF path is proven unstable.
- Do not add relay/VPS logic unless Cloudflare Proxy stops being a viable path.
