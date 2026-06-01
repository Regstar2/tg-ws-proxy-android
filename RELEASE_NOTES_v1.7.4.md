# Release notes v1.7.4

## Added

- Added upstream-inspired Cloudflare Worker pool foundation.
- Added Worker pool warmup when Worker route is enabled by the effective policy.
- Added Worker/CF pool metrics to runtime status.
- Added pool metrics to diagnostics and runtime log export.
- Added or verified MTProto message splitting for WebSocket frames.
- Added tests for pool/splitter behavior where applicable.
- Updated Cloudflare Worker setup documentation and Worker code.

## Behavior

Worker route can now reuse prepared WebSocket connections when available.

Worker warmup respects the effective Wi-Fi/Mobile route policy and does not run when Worker route is disabled.

Existing global connection mode compatibility remains unchanged.

Existing low-level Direct, Worker, CF, TCP, and active route diagnostics remain available.

## Privacy

Pool metrics and diagnostics do not include raw SSID, raw SIM/operator values, full domains, or full runtime tokens.

## Not included

Fake TLS / ee-secret support is not included in v1.7.4.

PROXY protocol support is not included in v1.7.4.

Docker/headless TG_WS_PROXY_SECRET support is not relevant for Android and is not included.

## Next

- Add smarter remediation actions based on diagnostics.
- Add optional route health history.
- Research Fake TLS only as a separate future feature, not as part of local Android SOCKS mode.
