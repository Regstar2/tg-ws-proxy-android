# Release notes v1.6.2

## Added

- Added EffectiveRoutePolicyResolver.
- Added effective route policy resolution by current network type.
- Added compatibility path from legacy ConnectionMode to NetworkRoutePolicy.
- Added route policy runtime tokens.
- Added tests for resolver and runtime token generation.

## Behavior

The app still keeps the existing UI and legacy connection mode selector.

If no per-network policy is saved, startup remains compatible with the existing global connection mode.

Saved per-network policies can now influence runtime token generation, but the Wi-Fi/Mobile UI is not added yet.

## Next

- Add Wi-Fi/Mobile route policy UI.
- Add visible effective policy diagnostics.
- Add reconfigure reconnect when the active network changes.
