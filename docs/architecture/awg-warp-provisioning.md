# AWG/WARP profile provisioning

Issues: #84, #86, #88

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
4. enabled custom provisioning Workers, in user-defined list order;
5. the project built-in provisioning Worker pool, only when the dedicated built-in toggle is enabled.

Provisioning Workers are a separate settings/repository model from the Telegram `cf_worker_ws` Worker Pool. Configuring one does not populate or change the other. Custom provisioning endpoints are validated as HTTPS origins without credentials, query, fragment or arbitrary path. The client performs `GET /warp-bootstrap/health` first and requires `service=warp-bootstrap` plus the supported `warp-bootstrap-v1` revision before registration or activation requests are sent.

The built-in pool is application configuration, not copied into the user's custom list. In v1.11.0 it contains three project-provided provisioning endpoints. They are provisioning-only and are never inserted into or inferred from the Telegram `cf_worker_ws` Worker Pool. A disabled built-in toggle is authoritative: no built-in provisioning endpoint is attempted as a hidden fallback. One failed candidate may advance to the next only for bounded transport failures, HTTP 429 or 5xx; non-retryable protocol/application errors stop the operation.

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

`ProbeAWGWarpConfig` creates an isolated temporary AWG dialer and validates the profile against Telegram itself, not only against a generic TCP target. The probe:

1. opens the Telegram DC connection through the temporary userspace AWG tunnel;
2. requires a non-zero recent AWG handshake and non-zero tunnel TX/RX;
3. sends a real MTProto `req_pq_multi` request;
4. accepts the candidate only after receiving and validating the matching `resPQ` response.

The probe has no fallback to direct TCP. A TCP connect or WARP handshake without the MTProto response is not enough to mark a profile as `WORKING`.

Automatic provisioning additionally performs a second bounded confirmation of the selected autotune candidate. The profile is persisted only after the full-duplex validation succeeds twice (`2/2`). A failed attempt therefore cannot overwrite or deselect the current working profile.

The result exposes only support-safe status, endpoint/handshake summary and counters; the MTProto probe does not expose application messages or profile secrets.

## Security rules

- PrivateKey is generated locally and never sent to the registration provider.
- PrivateKey and full profile text must not be logged, included in crash messages, fixtures or CI artifacts.
- Profile-details UI hides PrivateKey by default and reveals it only on an explicit user action.
- Registration errors expose support-safe error codes, not response/request bodies.
- Real registration credentials/profile material are not used in unit tests.

## Manual device acceptance

CI verifies parser/serializer/key-generation, MTProto-aware profile probe logic and native transport regressions, but the Consumer provider and real network path remain environment-dependent. Before tagging v1.11.0 stable, verify on a real Android device:

1. Settings → Cloudflare → WARP / AmneziaWG → Create profile.
2. Provisioning reaches Save without exposing a secret in logcat.
3. The created profile shows `Working` only after the Telegram MTProto `req_pq_multi` → `resPQ` check and the second confirmation succeed.
4. Select the profile and enable/prefer the `awg_warp` route.
5. Start the proxy.
6. Confirm AWG handshake and non-zero tunnel TX/RX.
7. Confirm Telegram has bidirectional traffic with `actual_backend=awg_warp` and no silent fallback.
8. Stop/start the proxy and verify the selected generated profile is preserved.
9. Verify manual `.conf` import still works after a failed automatic provisioning attempt.
10. Make direct Consumer API unavailable and verify custom provisioning Worker fallback.
11. Verify the three built-in provisioning Workers are used only when their dedicated toggle is enabled and never appear in the Telegram Worker Pool.
