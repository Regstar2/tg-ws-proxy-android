# Userspace AmneziaWG/WARP transport PoC

Issue: #70

## Goal

Prove whether the native Go runtime can open Telegram DC TCP connections through an AmneziaWG/WARP peer without Android `VpnService`, a system TUN interface, root access, or device-wide routing changes.

The production routes (`direct_ws`, `cf_worker_ws`, `cf_proxy_ws`, and `tcp_fallback`) remain unchanged during this PoC.

## Current architecture

```text
Telegram / focused probe
        |
        v
local proxy/runtime
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

`amneziawg-go` already contains the in-memory gVisor netstack adapter used here, so the PoC does not create an Android TUN device.

## Dependency pin

The PoC pins:

```text
github.com/amnezia-vpn/amneziawg-go/v3 v3.1.20260828
source commit: b5928efb6ca19f0153958460c3d141f04abc5c2e
license: MIT
```

The dependency is used through its public `conn`, `device`, and `tun/netstack` packages. Upstream source is not copied or modified in this repository.

## Supported configuration

The initial parser accepts one local WireGuard-style `.conf` with exactly one peer.

`[Interface]`:

- `PrivateKey`
- `Address`
- optional `MTU`
- `Jc`, `Jmin`, `Jmax`
- `S1` ... `S4`
- `H1` ... `H4`
- `I1` ... `I5`

`[Peer]`:

- `PublicKey`
- `Endpoint`
- `AllowedIPs`
- optional `PersistentKeepalive`

`DNS` and `Table` are accepted but ignored because the PoC does not install system routes and does not need in-tunnel DNS for Telegram IP-literal targets. `PresharedKey` and multiple peers are intentionally rejected in the initial scope.

When `MTU` is omitted, the PoC uses `1280`. A config-provided MTU overrides that value.

## Secret handling

Real WARP/AWG credentials must never be committed to the repository, included in tests, or printed in logs.

The parser converts private/public WireGuard keys to the hex representation required by the upstream UAPI only in memory. Validation errors do not echo key material. Runtime diagnostics extract only safe fields from `IpcGet()` and deliberately ignore private/public key entries.

## Lifecycle

`newAwgWarpDialerFromFile()`:

1. validates and parses the local config;
2. creates an in-memory gVisor netstack;
3. creates one reusable userspace AmneziaWG device with a normal UDP bind;
4. applies the UAPI configuration;
5. brings the device up.

`DialContext()`:

- supports TCP only in the initial PoC;
- requires an IP-literal target so tunnel DNS is not required;
- rejects targets outside configured `AllowedIPs`;
- respects the supplied context;
- returns a normal `net.Conn` backed by the userspace stack;
- never falls back to direct TCP.

Closing a returned connection does not stop the tunnel. `Close()` stops the reusable AWG device and its userspace stack and is safe to call repeatedly.

## Diagnostics

`Diagnostics()` returns only:

- configured peer endpoint;
- latest handshake timestamp reported by the AWG device;
- tunnel TX/RX byte counters;
- application bytes written/read through returned `net.Conn` values;
- last inner target address.

These fields are intended to prove the path used by a later Android-focused probe. A successful TCP connect by itself is not sufficient acceptance evidence.

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

## Device acceptance evidence

The PoC is accepted only after a real Android run with a known-good local config demonstrates all of the following without logging secrets:

1. AWG handshake timestamp becomes non-zero/recent.
2. Tunnel TX and RX counters both increase.
3. The inner target is the intended Telegram DC IP and port.
4. A valid Telegram/MTProto exchange passes application bytes in both directions.
5. No `VpnService` permission/dialog and no system TUN interface are involved.
6. Starting, stopping, and starting the transport again works in one app process.

## Current conclusion

**INCONCLUSIVE.**

The isolated userspace transport adapter, configuration boundary, lifecycle, safe diagnostics, and unit-testable parsing are implemented. The production route selector is intentionally unchanged. A focused Android probe and real-device Telegram exchange are still required before deciding GO/NO-GO for integration as a production `RouteKind`.
