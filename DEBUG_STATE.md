# Debug State

Updated: 2026-04-11

## Current facts

- The mobile-network direct path remains unreliable in the tested environment.
- The current working path is Cloudflare Proxy through `kws{dc}.<domain>`.
- `CF first` now attempts Cloudflare before the direct WS path.
- `CF only` keeps supported Telegram traffic on the Cloudflare path and skips direct WS pool warmup.
- Cloudflare hostname-first dialing is restored: the runtime first tries the hostname route and only then resolved IP candidates.
- Bridge close logging now records the primary first-exit reason and marks later `net.ErrClosed` / EOF style events as secondary.
- The temporary `DC1 -> DC2` direct WS override was removed. Only `DC203 -> DC2` remains.

## Confirmed in recent logs

Useful expected lines:

```text
cfproxy hostname dial start host=kws2.pclead.co.uk
cfproxy connected host=kws2.pclead.co.uk via=hostname
bridge first-exit primary direction=...
CF proxy closed: reason=...
stats: ... cf=... up=... down=...
```

The user confirmed that the latest working build runs after the Cloudflare dial-order and bridge close-reason fixes.

## Known limits

- The shared default domain `pclead.co.uk` can hit Cloudflare WebSocket limits and return `429 Too Many Requests`.
- For regular use, a custom Cloudflare domain is recommended.
- Direct Telegram endpoint routing is not the current priority because it has repeatedly behaved like a network dead end on mobile.
- Stability still needs a longer mobile usability test with messages, media, background resume, and network switching.

## Stop conditions

- If Cloudflare Proxy becomes unusable even with a custom domain, stop tuning the Android direct path and write a relay/VPS plan.
- If two or three focused Cloudflare iterations do not produce stable Telegram usage, stop adding random endpoint sweeps.
