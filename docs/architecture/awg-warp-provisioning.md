# AWG/WARP profile provisioning

Issues: #84, #86

## Scope

TGWSProxyAndroid can create and manage WARP/AWG profiles without Android `VpnService`, root, a system TUN interface, or a user-supplied `.conf`. The transport itself remains the userspace `awg_warp` path proven by #70; provisioning is a separate Android-side feature.

The automatic MVP provider is **Consumer WARP**. Manual `.conf` import remains available and uses the same profile repository.

## Boundaries

```text
Cloudflare settings UI
    |
    v
AwgWarpProfileManager
    |-----------------------------|
    v                             v
WarpProfileProvisioner      AwgWarpProfileRepository
    |                             |
    v                             v
Consumer WARP registration  app-private profile files
    |
    v
WarpProvisionedProfile
    |
    v
AwgWarpProfileSerializer
    |
    v
staging .conf
    |
    +--> ValidateAWGWarpConfig
    |
    +--> ProbeAWGWarpConfig
    |
    v
saved profile
```

Registration orchestration remains separate from route selection. For #86, a narrowly scoped native helper may create a temporary `AwgWarpDialer` from an already validated saved profile and use it only to reach the fixed Consumer WARP API. The runtime route still receives only the path of the explicitly selected profile through the existing runtime configuration.

## Consumer registration provider

The consumer registration flow follows the currently observed Cloudflare consumer-client schema used by `wgcf`:

- base host: `api.cloudflareclient.com`;
- provider/API-version constants are isolated inside `ConsumerWarpProfileProvisioner`;
- the WireGuard Curve25519 keypair is generated locally by the native Go runtime;
- only the public key and required device metadata are included in registration;
- the response is normalized to assigned IPv4/IPv6 addresses, peer public key and peer endpoint;
- response/request bodies are never logged.

This is not treated as a stable public API. If Cloudflare changes the consumer registration contract, the provider can be replaced without changing AWG transport code, profile storage, or route policy.

## Bootstrap policy for a new Consumer registration

A new profile always uses a newly generated local WireGuard keypair and a new Consumer WARP registration. A saved profile is never copied into the new profile and a failed fresh registration is not replaced with a stored registration seed.

Bootstrap order is:

1. the currently selected profile when its persisted health is `WORKING`;
2. other saved `WORKING` profiles, newest first;
3. direct Android HTTPS to `api.cloudflareclient.com`;
4. the existing restricted Cloudflare Worker bootstrap.

Both generated and imported profiles are eligible when they have already passed the real full-duplex profile check. Before use, the candidate config is parsed and validated again. The native reachability probe then has to prove that the fixed API host is reachable through a temporary userspace AWG tunnel.

The native bootstrap boundary is intentionally not a generic proxy. It allows only:

- `GET https://api.cloudflareclient.com/` for a side-effect-free reachability check;
- `POST https://api.cloudflareclient.com/v0a4005/reg` with a newly generated public key;
- `PATCH https://api.cloudflareclient.com/v0a4005/reg/<registration-id>` with `warp_enabled=true`.

Redirects, arbitrary hosts, ports, paths, query strings and methods are rejected. The HTTP transport has no direct-socket fallback: DNS resolves only the fixed API hostname, while every TCP connection for the request is opened through `AwgWarpDialer.DialContext`.

If a candidate fails before a side-effecting registration request is sent, provisioning continues to the next candidate and then to direct/Worker fallback. If request headers may already have left the device, the flow preserves the existing ambiguity guard and does not issue another registration POST through a different transport. Activation uses the same AWG bootstrap first; because the PATCH is idempotent, a transport failure may safely fall back to direct API and then Worker.

## AWG/WireGuard compatibility preset

Cloudflare WARP peers speak WireGuard. The generated profile therefore keeps the actual WireGuard packet format intact:

- `S1..S4 = 0`;
- `H1..H4 = 1,2,3,4`, the standard WireGuard message types;
- `Jc = 4`, `Jmin = 40`, `Jmax = 70` add only client-side junk packets before a handshake.

AmneziaWG documents junk packets as client-side and says unspecified/zero parameters use standard behaviour. Server-coupled header/padding obfuscation is deliberately not randomized for a standard WARP peer.

The preset is intentionally conservative. More aggressive AWG obfuscation requires separate compatibility testing rather than random values in provisioning code.

## Profile storage

Profiles live only in app-private storage:

```text
files/awg-warp/
  profiles/
    <uuid>/
      profile.conf
      metadata.properties
  staging/
  selected-profile-id
```

Properties:

- profile IDs are random UUIDs and do not derive from display names;
- the selected profile ID is stored separately from profile contents;
- full config/private keys are never stored in SharedPreferences;
- writes use temp files plus replacement;
- deleting the selected profile clears selection instead of selecting another profile implicitly;
- a legacy `files/awg-warp/active.conf` is migrated to an imported profile when possible.

Metadata contains only display/source/timestamp/health state plus the generated local **public** key. It does not contain the private key or full config body.

## Validation

Configuration and network validation are separate operations.

### Structural/native validation

`ValidateAWGWarpConfig` parses the staged file through the existing native AWG parser without changing the active runtime. Selection is rejected if this validation fails.

### Network validation

`ProbeAWGWarpConfig` creates an isolated temporary AWG dialer and performs a bounded TCP connection to a Telegram DC through the userspace tunnel. The result is accepted only when:

- the connection succeeded through the temporary AWG dialer;
- the latest AWG handshake is non-zero;
- tunnel TX and RX counters are non-zero.

The probe has no fallback to direct TCP. It returns only support-safe code, endpoint, handshake timestamp and counters.

An automatically provisioned profile is persisted only after both checks succeed. A failed attempt therefore cannot overwrite the selected working profile.

## Security rules

- PrivateKey is generated locally and never sent to the registration provider.
- PrivateKey and full profile text must not be logged, included in crash messages, fixtures or CI artifacts.
- Profile-details UI hides PrivateKey by default and reveals it only on an explicit user action.
- Registration errors expose support-safe error codes, not response/request bodies.
- Real registration credentials/profile material are not used in unit tests.

## Manual device acceptance

CI verifies parser/serializer/key-generation and existing native transport regressions, but the consumer provider is network-dependent. Before merging #84, verify on a real Android device:

1. Settings → Cloudflare → WARP / AmneziaWG → Create profile.
2. Provisioning reaches Save without exposing a secret in logcat.
3. The created profile shows `Working` and a recent check.
4. Select the profile and enable/prefer the `awg_warp` route.
5. Start the proxy.
6. Confirm AWG handshake and non-zero tunnel TX/RX.
7. Confirm Telegram has bidirectional traffic with `actual_backend=awg_warp` and no silent fallback.
8. Stop/start the proxy and verify the selected generated profile is preserved.
9. Verify manual `.conf` import still works after a failed automatic provisioning attempt.
