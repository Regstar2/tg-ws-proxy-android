# CF Domain Pool

## v1.4.0 CF domain auto-update

The Android fork can update its Cloudflare proxy domain list from Flowseal upstream:

```text
https://raw.githubusercontent.com/Flowseal/tg-ws-proxy/main/.github/cfproxy-domains.txt
```

GitHub is an update source, not a runtime dependency. If the request fails, the app keeps the last cached upstream list. If there is no cached list yet, the built-in list remains available. A manual user domain is never overwritten by upstream refreshes.

## Sources and order

The selector uses:

1. Manual domain
2. Cached upstream domains
3. Built-in domains

The resulting fallback order is `Manual -> Cached upstream -> Built-in`.

Every selected domain keeps the existing health model:

- source;
- success and failure counters;
- last success and failure timestamps;
- last failure reason;
- cooldown deadline;
- last latency.

If the manual domain receives `429`, `403`, `5xx`, or repeated transport failures, it can cool down and yield to cached upstream domains. If cached upstream domains are unavailable, built-in domains remain the emergency fallback.

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
