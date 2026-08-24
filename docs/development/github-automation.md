# GitHub automation

This document describes the public automation contract for TgWsProxy Android. Private project-governance templates are not part of this repository.

## Overview

Project-specific commands live in PowerShell scripts. GitHub Actions only orchestrates those scripts.

| Entry point | Purpose |
|---|---|
| `scripts/ci.ps1` | Native Go verification/tests, Android unit tests, debug APK build and packaged-resource audit |
| `scripts/release.ps1 -Version vX.Y.Z` | Signed release APK build, signature verification, stable artifact naming and SHA-256 generation into `dist/` |
| `.github/workflows/trusted-ci.yml` | Owner-only CI for trusted same-repository PRs and manual dispatch |
| `.github/workflows/project-sync.yml` | Add owner-created Issues and same-repository PRs to the Development Project |
| `.github/workflows/release.yml` | Validate a release tag, rerun CI, build `dist/`, then create a GitHub Release |

## Trusted CI

The persistent self-hosted runner is treated as a trusted owner machine.

The workflow uses the labels:

```text
self-hosted, Windows, X64
```

For `pull_request_target`, self-hosted execution is allowed only when all of the following are true:

- actor is `Regstar2`;
- triggering actor is `Regstar2`;
- PR author is `Regstar2`;
- PR head belongs to this same repository.

The workflow definition comes from the trusted base branch. Only after the metadata gate passes does it checkout the exact PR head SHA and execute `scripts/ci.ps1`.

External/fork PR code must not run on the self-hosted runner.

### Local equivalent

From repository root:

```powershell
.\scripts\ci.ps1
```

The script fails on an unsuccessful mandatory check. It currently performs:

1. `go mod verify` in `native/tgwsproxy/`;
2. `go test ./...`;
3. `testDebugUnitTest` and `assembleDebug` through the Gradle Wrapper;
4. verification that the debug APK and `libtgwsproxy.so` were produced;
5. `scripts/audit-apk-icons.ps1` against the built APK.

## Development Project sync

`project-sync.yml` adds owner-created Issues and owner-created same-repository Pull Requests to:

```text
https://github.com/users/Regstar2/projects/2
```

Required repository Actions secret:

```text
ADD_TO_PROJECT_PAT
```

The token value must exist only in GitHub Actions secrets. It must not be committed, printed in logs, placed in issue text or copied into diagnostics.

The token needs only the permissions required by GitHub for adding repository Issues/PRs to the configured user-level Project.

External issue/PR events are intentionally not sent to the self-hosted runner by this workflow.

## Release automation

`release.yml` runs only for an owner-triggered `v*` tag push or an owner `workflow_dispatch` that references an existing release tag.

Release flow:

```text
exact tag
  -> scripts/ci.ps1
  -> clean dist/
  -> scripts/release.ps1 -Version <tag>
  -> verify APK + SHA-256 output
  -> create GitHub Release
```

The workflow refuses to silently replace an existing GitHub Release.

Alpha, beta and RC tags are created as GitHub prereleases.

### Signing requirements

A release APK must be signed. `scripts/release.ps1` requires these environment variables:

```text
KEYSTORE_FILE
KEYSTORE_PASSWORD
KEY_PASSWORD
KEY_ALIAS    # optional; defaults to tgwsproxy
```

`KEYSTORE_FILE` may point to a keystore outside the repository. Signing material and passwords must not be committed.

For local builds, the existing gitignored `release-signing.env` loader may populate these variables. On a self-hosted Actions runner, configure the runner/service environment or another secure secret-injection mechanism so that the release process receives them without storing the values in the checkout.

The release script also requires Android SDK build-tools with `apksigner.bat`. It verifies the generated APK signature before copying anything into `dist/`.

### Version gate

The release tag without the leading `v` must exactly match `versionName` in `app/build.gradle.kts`. For example:

```text
v1.10.13 <-> versionName = "1.10.13"
```

This prevents publishing a tag whose APK reports a different application version.

### Output

For `v1.10.13` the expected output is:

```text
dist/TgWsProxy-Android-v1.10.13-arm64-v8a.apk
dist/TgWsProxy-Android-v1.10.13-arm64-v8a.apk.sha256
```

`dist/` is generated locally and is ignored by Git.

## Safe verification before the first release

Before relying on this automation for a public release:

1. verify the self-hosted runner reports the `self-hosted`, `Windows`, and `X64` labels;
2. run `scripts/ci.ps1` locally;
3. open an owner same-repository PR and confirm Trusted CI executes successfully;
4. confirm an owner Issue/PR is added to Development Project #2;
5. confirm no Project PAT or signing values appear in logs;
6. run `scripts/release.ps1` only after release signing is configured and application version metadata matches the intended tag;
7. test the release workflow with a deliberate release candidate/test tag before the first stable automated publication when practical.
