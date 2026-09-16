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

A real Android smoke test with AWG/WARP set as preferred and route fallback disabled demonstrated the following:

- MTProto frontend reported `selected_backend=awg_warp`;
- successful sessions reported `actual_backend=awg_warp`;
- successful sessions reported `fallback_used=false`;
- Telegram normal DC sessions passed application data in both directions;
- Telegram media sessions also connected through `awg_warp` and passed application data in both directions;
- one observed session transferred `6546` application bytes upstream and `281597` bytes downstream with `error=none`;
- the previous foreground-service crash no longer reproduced;
- no Android `VpnService`, root access, or system TUN interface was required.

This proves that useful Telegram MTProto traffic can traverse the userspace AWG/WARP path without silent direct fallback.

The original smoke build did not yet emit the AWG device handshake timestamp and tunnel-level TX/RX counters into logcat. The connector now emits those safe fields. One follow-up device smoke is required to capture that final evidence explicitly.

## Device acceptance checklist

The implementation is accepted after a real Android run with a known-good local config demonstrates all of the following without logging secrets:

1. AWG handshake timestamp becomes non-zero/recent.
2. Tunnel TX and RX counters both increase.
3. The inner target is the intended Telegram DC IP and port.
4. A valid Telegram/MTProto exchange passes application bytes in both directions.
5. No `VpnService` permission/dialog and no system TUN interface are involved.
6. Starting, stopping, and starting the transport again works in one app process.
7. With fallback disabled, successful sessions show `actual_backend=awg_warp` and `fallback_used=false`.

Items 4, 5, and 7 are already demonstrated by the 2026-09-16 smoke. Items 1–3 are instrumented for the next smoke; restart lifecycle should be included in the same final acceptance run.

## Current conclusion

**REAL TRANSPORT PATH PROVEN; FINAL ACCEPTANCE EVIDENCE PENDING.**

The real-device test demonstrates bidirectional Telegram and media traffic over the userspace `awg_warp` route without `VpnService`, root, system TUN, or silent fallback. The remaining acceptance step is to capture the newly exposed AWG handshake/tunnel counters and perform the start-stop-start lifecycle check on device.
