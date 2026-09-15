# CF Domain Pool

## v1.4.0 CF domain auto-update

The Android fork can update its Cloudflare proxy domain list from Flowseal upstream:

```text
https://raw.githubusercontent.com/Flowseal/tg-ws-proxy/main/.github/cfproxy-domains.txt
```

GitHub is an update source, not a runtime dependency. If the request fails, the app keeps the last cached upstream list. If there is no cached list yet, the built-in list remains available. A manual user domain is never overwritten by upstream refreshes.

## Sources and runtime selection

The selector uses three sources:

1. Manual domains
2. Cached upstream domains
3. Built-in domains

Manual domains are an absolute user override while they are eligible for the affected DC. They always stay ahead of non-manual candidates, although cooldown or an in-flight reservation can temporarily remove a manual endpoint from the current selection.

Cached upstream and built-in candidates share one health-aware ranking. Runtime health is the primary ordering signal; cached upstream receives only a small source-score bonus rather than an absolute priority. This allows a recently successful built-in endpoint to move ahead of a degraded cached endpoint.

Health scoring uses bounded historical counters together with runtime recency:

- a recent success receives a temporary bonus that decays over time;
- recent failures and consecutive failures apply a penalty;
- failure penalties decay in stages and stop affecting ranking after six hours without another failure;
- cached upstream receives a small bounded source bonus;
- latency contributes a bounded penalty.

Every fifth selection performs bounded exploration among non-manual candidates whose score remains reasonably close to the current leader. The least recently observed eligible candidate is temporarily promoted for that selection. This prevents a lower-priority source from being starved indefinitely without allowing a badly degraded endpoint to bypass a clearly healthier candidate. Exploration never moves a candidate ahead of manual domains.

Source ownership remains attached to the base domain. Runtime health is endpoint-specific and is keyed by `(wsDC, baseDomain)`, matching the actual `kws<wsDC>.<baseDomain>` endpoint.

Every selected endpoint keeps its own health model:

- DC;
- source;
- success and failure counters;
- consecutive failure count;
- last success and failure timestamps;
- last failure reason;
- cooldown deadline;
- last latency.

A failure or cooldown for one DC does not penalize the same base domain on another DC. `dcPreferred` remains per-DC, and snapshots include the DC so diagnostics can distinguish health for endpoints that share one base domain.

If the manual domain receives `429`, `403`, `5xx`, or repeated transport failures, it can cool down for the affected DC and yield to cached upstream or built-in domains according to their health. If cached upstream domains are unavailable or unhealthy, built-in domains remain available as runtime fallbacks.

## Update policy

The UI offers:

- `Update CF domain list`
- `Auto-update CF domains`

Manual update starts immediately. Auto-update is enabled by default and is throttled to one attempt every 24 hours. Updates are asynchronous and do not block proxy startup.

Downloaded lists are normalized and validated before replacing the cache:

- blank lines and comment lines are ignored;
- hostnames are lowercased;
- `http://` and `https://` URLs are reduced to hostnames;
- duplicates are removed while preserving order;
- invalid hostnames, ports, wildcards, localhost, and IP literals are rejected.

If a download is empty or invalid, the previous cache is kept.

## v1.4.1 GitHub / mirror download resilience

CF-domain updates are best-effort. The proxy runtime does not depend on GitHub availability.
If the primary source fails, the app can try a user-provided HTTPS mirror. If all update sources fail,
the previous cached list is kept. If there is no cache, the built-in list remains available.

### Update sources

1. Primary GitHub raw list (`CfDomainUpdateConfig.PRIMARY_URL`)
2. Optional user mirror URL (`https://` only, validated)
3. Cached upstream list (runtime fallback)
4. Built-in list (emergency fallback)

Manual and auto updates use the same order: primary first, then mirror when enabled and valid.

### Retry / backoff

- Manual update: up to 2 attempts per source for retryable errors (for example `5xx`, timeouts), with a short delay between attempts.
- Auto update: no aggressive per-source retry; throttle is 24 hours after success and 1 hour after failure.
- Updates are asynchronous and do not block proxy startup.

### Cache safety

The cache is replaced only after a successful download, parse, validation, and a non-empty domain list.
Failed downloads, invalid mirror responses, and empty lists keep the previous cache.

### Conditional requests

The downloader sends `If-None-Match` / `If-Modified-Since` when cached validators exist.
`304 Not Modified` keeps the current cache and updates the last-checked timestamp.

### Diagnostics

Per-source status (last attempt, success, HTTP status, stage, latency) is shown in the CF domains settings panel.
Download failures are classified into stages such as DNS, TCP, TLS, HTTP, READ, PARSE, and VALIDATION.

### Deferred work

`v1.4.1` does not implement:

- Fake TLS;
- pinned TLS certificate pinning;
- APK auto-update;
- WorkManager-based background sync.

Possible later follow-ups:

- additional mirror URLs;
- pinned TLS research for GitHub/mirror fetch paths.
