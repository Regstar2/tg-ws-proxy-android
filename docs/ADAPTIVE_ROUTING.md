# Adaptive Auto routing (v1.5.0)

Auto mode is adaptive in the Android fork. It does not blindly follow a fixed desktop order on every connection.
It uses recent route health, network profile, DC direction, cooldowns, and last-good route to choose the most likely working path.
Manual modes remain deterministic.

## Route types

- `direct_ws` — direct Telegram WebSocket
- `cf_worker_ws` — Cloudflare Worker WebSocket
- `cf_proxy_ws` — Cloudflare proxy WebSocket (domain pool)
- `tcp_fallback` — direct TCP passthrough when allowed

## Network profile

The app builds a lightweight network profile without storing raw SSID:

- **Wi-Fi** — `WIFI` + hashed identifier (`wifi_unknown` when SSID is unavailable)
- **Mobile** — `MOBILE` + hash of MCC/MNC / operator name when available
- **Unknown** — safe fallback profile

Profiles are passed to the native runtime as `@network_profile_*` tokens.

## Route statistics

Per profile, route type, DC, and media flag the runtime tracks:

- success / failure counts
- last error and latency
- cooldown after repeated failures
- last-good route (12 hour TTL)

Stats are stored locally in `SharedPreferences` (`adaptive_route_stats`) and synchronized with the Go runtime during proxy sessions.

## Scoring (Auto / Direct + fallback routes)

Fallback chain order is scored per connection:

- Wi-Fi gives Direct a starting bonus
- Mobile gives Worker / CF bonuses when Direct often fails
- Success history, failures, consecutive failures, and latency adjust the score
- Routes in cooldown are skipped
- Last-good route receives a bonus when still valid

## Manual modes

Adaptive scoring applies to **Auto** and **Direct + fallback routes** only.

`Worker only`, `CF only`, `Direct only`, `Worker first`, and `CF first` keep their documented deterministic behavior.

## Privacy

- Route statistics are stored on device only
- Network identity is stored as a hash or generic profile label
- Raw SSID is not persisted
- No Telegram message content is stored

## Reset

Settings → Adaptive routing:

- **Reset current network statistics** — clears stats for the active profile
- **Reset all route statistics** — clears all adaptive stats (does not reset CF domain cache, Worker domain, or connection mode)
