# Original vs Android Diff

Updated: 2026-04-11

## Scope

Compared projects:

- Runtime origin: `Flowseal/tg-ws-proxy`
- Android origin: `amurcanov/tg-ws-proxy-android`
- Current repository: Android fork with local runtime and UI fixes

This file tracks high-level behavior that matters for the current Cloudflare Proxy baseline.

## What changed since the first comparison

The first audit found that the Android fork did not have a practical Cloudflare Proxy runtime branch. That is no longer true for this working tree.

Current fork now has:

- `CF proxy`, `CF first`, and `CF only` runtime modes.
- Cloudflare fallback to `wss://kws{dc}.<domain>/apiws`.
- Hostname-first Cloudflare dialing, followed by resolved IP candidates when needed.
- DNS/TCP/WS diagnostic logs for Cloudflare attempts.
- UI settings for Cloudflare mode and domain.
- Bridge first-exit logging for close-reason diagnosis.
- WS pool warmup disabled in CF-first and CF-only modes to reduce direct-path noise.

## Runtime behavior notes

### Cloudflare path

The useful Android path now matches the important part of Flowseal's strategy:

- derive the Telegram DC from the client request/init data;
- route the session to `kws{dc}.<cf_domain>`;
- bridge the client socket to the Cloudflare WebSocket;
- fall back only when the selected CF route cannot be established.

The default domain remains `pclead.co.uk`, but it is a shared endpoint and can return `429 Too Many Requests`. A user-owned Cloudflare domain is still the better long-term setup.

### Direct WS domains

The current override policy is back to the safer baseline:

- `DC203 -> DC2`

The temporary debug override:

- `DC1 -> DC2`

was removed because it could make logs and direct WS domain selection misleading, for example mapping DC1 traffic to `kws2.*`.

### Bridge close handling

The bridge now records the first primary close reason before cancelling the paired copy loop. Secondary `net.ErrClosed` / EOF-like errors should no longer hide the primary cause.

Useful log categories:

- `bridge first-exit primary ...`
- `bridge secondary ...`
- `CF proxy closed: reason=...`

## Remaining differences from Flowseal runtime

- No automatic encoded Cloudflare domain pool/rotation in Android.
- No full Cloudflare domain tester UI yet.
- Android has foreground service, Compose UI, log export, theme/language settings, and mobile-specific UX that the desktop/runtime project does not need.
- Direct path remains available, but it is not the primary working route for tested mobile networks.

## Practical conclusion

The current Android fork is no longer blocked on "missing CfProxy". The active question is now operational stability:

- Does `CF first` / `CF only` remain usable for real Telegram traffic on mobile networks?
- Does the default domain hit Cloudflare limits too often?
- Is a custom domain workflow needed before a public release?

Until Cloudflare Proxy is proven insufficient, avoid spending more time on random direct Telegram IP/path selection.
