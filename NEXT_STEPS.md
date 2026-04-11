# NEXT_STEPS

Current working candidate: `cfproxy_only` through `pclead.co.uk`.

1. Fix IPv6 Telegram destinations in `cfproxy_only`
- Keep direct passthrough disabled for Telegram-like IPv6 connections in CF-only mode.
- Add only observed exact IPv6 literal mappings; do not broaden to full ranges without runtime logs.
- Verify that IPv6 Telegram attempts go to `kws{dc}.pclead.co.uk` or close fast with a clear reason.

2. Add CF session close reason logging
- Log the first bridge-side close reason: `client_read`, `client_to_ws_write`, `ws_read`, `ws_to_client_write`, or `context_cancel`.
- Keep the existing traffic counters in the close line.
- Use these reasons to understand 9s, 40s, and 90s session lifetimes.

3. Run a 20-minute mobile usability test
- Network: mobile only.
- Mode: `cfproxy_only`.
- Check incoming text, outgoing text, photos, video, stickers/reactions, background resume, and network switching.
- Save runtime logs to the synced report folder.

4. Evaluate the result
- If Telegram is usable, stop changing direct Telegram IP routing and stabilize CfProxy only.
- If Telegram is not usable after 2-3 focused CfProxy iterations, write a relay/VPS plan instead of adding more random sweeps.

5. Add custom domain workflow later
- Add custom CF domain settings only after IPv6 handling and close-reason logs are validated.
- Test `kws1`, `kws2`, `kws4`, and `kws203` for resolve/connect/WS upgrade before enabling a domain by default.
