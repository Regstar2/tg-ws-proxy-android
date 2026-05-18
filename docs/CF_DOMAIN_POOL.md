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

## Deferred work

`v1.4.0` does not implement:

- Fake TLS;
- GitHub pinned TLS fallback;
- mirror registries;
- APK auto-update;
- a perpetual background worker.

Possible `v1.4.1` follow-ups:

- mirror URL support;
- richer download diagnostics;
- further resilient-download work around mirrors or pinned transports.
