# Debug State

Updated: 2026-04-10

## Current facts

- Android fork direct path over mobile network is not confirmed working.
- Latest Android transport sweep on mobile:
  - `dc_tcp_successes=0`
  - `kws_transport_successes=0`
  - No confirmed reachability to tested Telegram DC IPs on `80/443/5222`
  - No confirmed reachability to `kws*.web.telegram.org` on `ws:80` or `wss:443`
- Latest Android transport sweep on Wi-Fi:
  - `dc_tcp_successes=2`
  - Only `149.154.167.220:80` and `149.154.167.220:443` were reachable
  - `kws*.web.telegram.org` still did not complete `ws:80` or `wss:443`
- Current Android fork has improved diagnostics, IPv6 SOCKS5 parsing, safe `JoinHostPort`, and upstream test tools.
- Current Android fork does not implement the original project's `CfProxy` runtime branch.

## Original project facts from Windows v1.5.1 logs

- Original `tg-ws-proxy` starts with:
  - `cfproxy=True`
  - `cfproxy_priority=True`
  - `cfproxy_domain='pclead.co.uk'`
- Original default config still uses only:
  - `DC2: 149.154.167.220`
  - `DC4: 149.154.167.220`
- For missing DCs, original does not stop at direct TCP fallback:
  - `DC1 not in config -> fallback`
  - `DC1 -> CF proxy wss://kws1.pclead.co.uk/apiws`
- Original Windows logs show partial success over phone tethering:
  - `DC1 WS session closed ... in 114.8s`
  - `DC2 WS session closed ... in 115.2s`
  - `DC3 -> CF proxy ...` also appears and one session closes normally
- Original also shows intermittent failures:
  - `CF proxy ... getaddrinfo failed`
  - direct `WS connect failed: [WinError 1231]`
  - direct `TCP fallback ... [WinError 1231/1232]`

## Working hypothesis

- Direct Android path is currently a dead end on mobile for the tested endpoints and transports.
- The original project survives some mobile-tethered cases because it has a separate `CfProxy` fallback branch with its own domain logic.
- The next primary implementation target in this Android fork should be `CfProxy`, not more direct-IP experiments.

## Stop conditions

- If `CfProxy` diagnostics also show no usable path, stop tuning direct transport logic further.
- If `CfProxy` and spec-aligned plain TCP checks both fail, move to a relay/VPS plan instead of more direct endpoint sweeps.
