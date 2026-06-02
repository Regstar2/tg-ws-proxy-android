# Release notes v1.6.1

## Added

- Added foundation layer for future per-network route policies.
- Added RouteKind.
- Added NetworkRoutePolicy.
- Added DefaultNetworkRoutePolicies.
- Added NetworkRoutePolicyRepository.
- Added NetworkRoutePolicyMapper for compatibility with legacy ConnectionMode.
- Added unit tests for policy persistence and legacy mapping.

## Behavior

Runtime and UI behavior are unchanged in v1.6.1.

The new policy layer is not connected to proxy startup yet. It only prepares the architecture for future Wi-Fi/Mobile route configuration.

## Next

- Connect route policies to runtime config.
- Add Wi-Fi/Mobile route policy UI.
- Add reconfigure reconnect on network switch.
- Extend diagnostics to show effective route policy.
