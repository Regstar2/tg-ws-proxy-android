# Release notes v1.7.0

## Added

- Added Wi-Fi and Mobile route policy settings UI.
- Added per-network route toggles for Direct WebSocket, Cloudflare Worker, Cloudflare Proxy, and TCP fallback.
- Added preferred route selector for Wi-Fi and Mobile policies.
- Added Auto strategy selector per network type.
- Added fallback toggle per network type.
- Added reset actions for Wi-Fi and Mobile route policies.
- Added testable NetworkRoutePolicyEditor logic.

## Behavior

The existing global connection mode selector remains available.

If no custom policy is saved for the current network type, the app keeps using the legacy global connection mode compatibility path.

If a custom Wi-Fi or Mobile policy is saved, it is used by the effective route policy resolver during proxy startup.

Changing route policies while the proxy is running is disabled in v1.7.0. Stop the proxy before editing route policies.

## Next

- Add reconfigure reconnect when the active network changes.
- Show effective route policy in diagnostics.
- Add route policy export to diagnostic reports.
