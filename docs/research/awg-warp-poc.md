# Userspace AmneziaWG/WARP transport PoC

Issue: #70

## Goal

Prove whether the native Go runtime can open Telegram DC TCP connections through an AmneziaWG/WARP peer without Android `VpnService`, a system TUN interface, root access, or device-wide routing changes.

The PoC is now integrated as the MTProto-only `awg_warp` route. SOCKS5 traffic and device-wide traffic are not routed through AWG/WARP.

## Current architecture

```text
Telegram MTProto client
        |
        v
local MTProto frontend
        |
        v
awg_warp route connector
        |
        v
awgWarpDialer.DialContext
        |
        v
gVisor userspace TCP/IP stack
        |
        v
amneziawg-go userspace device
        |
        v
ordinary Android UDP socket
        |
        v
AWG/WARP peer
        |
        v
Telegram DC IP
```

`amneziawg-go` already contains the in-memory gVisor netstack adapter used here, so the implementation does not create an Android TUN device.

## Dependency pin

The PoC pins:

```text
github.com/amnezia-vpn/amneziawg-go/v3 v3.1.20260828
source commit: b5928efb6ca19f0153958460c3d141f04abc5c2e
license: MIT
```

The dependency is used through its public `conn`, `device`, and `tun/netstack` packages. Upstream source is not copied or modified in this repository.

## Supported configuration

The parser accepts one local WireGuard-style `.conf` with exactly one peer.

`[Interface]`:

- `PrivateKey`
- `Address` as CIDR or bare IPv4/IPv6 address
- optional `MTU`
- `Jc`, `Jmin`, `Jmax`
- `S1` ... `S4`
- `H1` ... `H4`
- `I1` ... `I5`

`[Peer]`:

- `PublicKey`
- `Endpoint` as IP literal or hostname plus port
- `AllowedIPs`
- optional `PersistentKeepalive`

`DNS` and `Table` are accepted but ignored because the implementation does not install system routes and Telegram inner targets are IP literals. A hostname used by the outer AWG peer endpoint is resolved before it is passed to `amneziawg-go`, whose standard bind expects an IP-literal UAPI endpoint.

`PresharedKey` and multiple peers are intentionally outside the initial scope.

When `MTU` is omitted, the PoC uses `1280`. A config-provided MTU overrides that value.

## Secret handling

Real WARP/AWG credentials must never be committed to the repository, included in tests, or printed in logs.

The parser converts private/public WireGuard keys to the hex representation required by the upstream UAPI only in memory. Validation errors do not echo key material. Runtime diagnostics extract only safe fields from `IpcGet()` and deliberately ignore private/public key entries. The imported config path is not included in transport diagnostic lines.

## Lifecycle

`newAwgWarpDialerFromFile()`:

1. validates and parses the local config;
2. resolves a hostname outer endpoint to an IP literal when needed;
3. creates an in-memory gVisor netstack;
4. creates one reusable userspace AmneziaWG device with a normal UDP bind;
5. applies the UAPI configuration;
6. brings the device up.

`DialContext()`:

- supports TCP only in the initial implementation;
- requires an IP-literal inner target so tunnel DNS is not required;
- rejects targets outside configured `AllowedIPs`;
- respects the supplied context;
- returns a normal `net.Conn` backed by the userspace stack;
- never silently falls back to direct TCP.

Closing a returned connection does not stop the tunnel. `Close()` stops the reusable AWG device and its userspace stack and is safe to call repeatedly.

When AWG/WARP is enabled, preferred, and fallback is disabled, AWG owns MTProto route selection. Legacy adaptive route scoring is skipped in that mode instead of emitting misleading `No allowed routes in policy` warnings. Enabling fallback keeps the normal legacy route-selection and scoring behavior.

## Diagnostics

`Diagnostics()` exposes only:

- resolved peer endpoint;
- latest handshake timestamp reported by the AWG device;
- tunnel TX/RX byte counters;
- application bytes written/read through returned `net.Conn` values;
- last inner target address.

The MTProto AWG connector logs these fields after a successful connection and again when the connection closes:

```text
AWG/WARP diagnostics event=connected|closed signed_dc=... dc=... media=... endpoint=... last_handshake=... tunnel_tx_bytes=... tunnel_rx_bytes=... app_up_bytes=... app_down_bytes=... inner_target=...
```

Per-session diagnostic lines bind `inner_target` to the connector's own target rather than the dialer's shared last-target snapshot, so concurrent DC connections cannot overwrite each other's reported target.

No private/public keys or imported config path are logged.

## Build and checks

Native unit/race tests:

```powershell
Set-Location native/tgwsproxy
go test ./...
go test -race ./...
```

Android ARM64 shared library using the existing project toolchain:

```powershell
./scripts/build-native-android.ps1 -ApiLevel 26
```

Full repository CI:

```powershell
./scripts/ci.ps1
```

## Real-device evidence — 2026-09-16

Real Android smoke testing with AWG/WARP set as preferred and route fallback disabled demonstrated the following:

- MTProto frontend reported `selected_backend=awg_warp`;
- successful sessions reported `actual_backend=awg_warp`;
- successful sessions reported `fallback_used=false`;
- AWG handshake completed successfully; observed `last_handshake=2026-09-16T08:30:56.395333289Z`;
- tunnel counters were non-zero and increased during use, for example from `tx=1494/rx=368` to `tx=611462/rx=266677`;
- application counters were non-zero in both directions and increased during use, for example to `app_up_bytes=1013527` and `app_down_bytes=219977`;
- Telegram DC targets included `149.154.175.50:443` (DC1) and `149.154.167.51:443` (DC2);
- individual MTProto sessions closed cleanly with `error=none` and bidirectional payload, including `up_bytes=868 down_bytes=7484` on one observed DC1 session;
- previous smoke testing also demonstrated bidirectional media traffic through `awg_warp`, including a session with `up_bytes=6546 down_bytes=281597 error=none`;
- the previous foreground-service crash no longer reproduced;
- no Android `VpnService`, root access, or system TUN interface was required;
- explicit proxy start → stop → start testing in one app process completed successfully and Telegram traffic resumed through AWG/WARP after restart.

This proves that useful Telegram MTProto traffic can traverse the userspace AWG/WARP path without silent direct fallback, that the AWG handshake/tunnel counters advance on the real device, and that the transport survives an application-level stop/start lifecycle.

One instrumentation issue was revealed by concurrent DC dials: the original per-session log read a shared `LastInnerTarget`, so two simultaneous diagnostics could display each other's target even though the route request itself used the correct DC target. The connector now captures and logs its own immutable target for each session; this is a diagnostics-only correction and does not change routing.

## Device acceptance checklist

The real-device PoC demonstrated all required transport acceptance items:

1. AWG handshake timestamp becomes non-zero/recent. **Verified.**
2. Tunnel TX and RX counters both increase. **Verified.**
3. The inner target is the intended Telegram DC IP and port. **Verified; per-session diagnostics bind the immutable connector target on current head.**
4. A valid Telegram/MTProto exchange passes application bytes in both directions. **Verified.**
5. No `VpnService` permission/dialog and no system TUN interface are involved. **Verified.**
6. Starting, stopping, and starting the transport again works in one app process. **Verified.**
7. With fallback disabled, successful sessions show `actual_backend=awg_warp` and `fallback_used=false`. **Verified.**

## Current conclusion

**GO.**

The real-device tests prove the core hypothesis: Telegram traffic can traverse a reusable userspace AmneziaWG/WARP transport on Android without `VpnService`, root, a system TUN interface, or silent direct fallback. Handshake, tunnel TX/RX, Telegram inner destinations, bidirectional MTProto application traffic, media traffic, and application-level start → stop → start lifecycle are demonstrated. The PoC is complete and suitable for follow-up production hardening and provisioning work.
