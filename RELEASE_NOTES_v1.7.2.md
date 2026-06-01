# Release notes v1.7.2

## Added

- Added effective route policy diagnostics.
- Added visible policy source, enabled routes, preferred route, Auto strategy, and fallback status.
- Added last network reconfigure status.
- Added copyable route policy diagnostics.
- Added effective route policy section to runtime log export.
- Added tests for diagnostics formatting and reconfigure status persistence.

## Behavior

The app now shows which route policy is actually active for the current network.

Wi-Fi/Mobile policies, legacy global mode fallback, and automatic reconfigure from v1.7.1 are easier to inspect and export.

No per-SSID or per-operator tracking is added.

## Privacy

Diagnostics do not include raw SSID, raw SIM/operator values, full domains, or full runtime tokens.

## Next

- Add detailed connection probe report per effective route.
- Add route-level diagnostics for disabled vs failed routes.
- Improve troubleshooting hints based on policy and probe results.
