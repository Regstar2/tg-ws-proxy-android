# Release notes v1.7.3

## Added

- Added active route connection diagnostics.
- Added route-level probe report for the effective route policy.
- Added disabled/not-configured/skipped statuses for policy-aware diagnostics.
- Added troubleshooting hints for failed route checks.
- Added copyable active route probe report.
- Added route probe report section to runtime log export.
- Added tests for route probe result mapping and safe report formatting.

## Behavior

The app can now test only the routes allowed by the active Wi-Fi/Mobile route policy.

Routes disabled by policy are shown as disabled instead of failed.

Worker and CF configuration problems are shown separately from network failures.

Existing low-level Direct, Worker, CF, TCP, and All diagnostics remain available.

## Privacy

Route probe reports do not include raw SSID, raw SIM/operator values, full domains, or full runtime tokens.

## Next

- Add smarter remediation actions from diagnostics.
- Add one-tap "enable fallback route" suggestions.
- Add optional health history for route diagnostics.
