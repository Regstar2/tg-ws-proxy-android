# NEXT_STEPS

Current working candidate: `CF first` / `CF only` through `pclead.co.uk` or a custom Cloudflare domain.

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
