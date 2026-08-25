# GitHub automation

This document describes the public automation contract for TgWsProxy Android. Private project-governance templates are not part of this repository.

## Overview

Project-specific commands live in PowerShell scripts. GitHub Actions only orchestrates those scripts.

| Entry point | Purpose |
|---|---|
| `scripts/ci.ps1` | Native Go verification/tests, Android unit tests, debug APK build and packaged-resource audit |
| `scripts/release.ps1 -Version vX.Y.Z` | Signed release APK build, signature verification, stable artifact naming and SHA-256 generation into `dist/` |
| `.github/workflows/trusted-ci.yml` | Public PR CI and manual CI dispatch on a GitHub-hosted Windows runner |
| `.github/workflows/project-sync.yml` | Add Issues and Pull Requests to the Development Project on a GitHub-hosted runner |
| `.github/workflows/release.yml` | Owner-only release validation/build/publication on the trusted self-hosted Windows runner |

## Public CI

The repository is public, so normal pull-request validation must not execute contributor code on the persistent owner machine.

`trusted-ci.yml` therefore uses GitHub-hosted `windows-latest` and responds to normal `pull_request` events plus `workflow_dispatch`. It does not receive project or release secrets.

The workflow:

1. checks out the event ref without persisting Git credentials;
2. configures Java 17;
3. configures the Go version declared by `native/tgwsproxy/go.mod`;
4. executes only `scripts/ci.ps1` as the project-specific CI entry point.

Because external PR code runs only inside an ephemeral GitHub-hosted runner, contributors can receive CI feedback without access to the owner's persistent Windows machine.

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

`project-sync.yml` adds repository Issues and Pull Requests to:

```text
https://github.com/users/Regstar2/projects/2
```

Required repository Actions secret:

```text
ADD_TO_PROJECT_PAT
```

The token value must exist only in GitHub Actions secrets. It must not be committed, printed in logs, placed in issue text or copied into diagnostics.

Project Sync runs on `ubuntu-latest`, not on the owner's machine. For pull requests it uses `pull_request_target`, but it does not checkout or execute PR code; it only invokes the pinned project-management action against event metadata. This permits Project access without exposing the secret to contributor code.

The token needs only the permissions required by GitHub for adding repository Issues/PRs to the configured user-level Project.

## Release automation

`release.yml` is intentionally separate from public CI. It runs only for an owner-triggered `v*` tag push or an owner `workflow_dispatch` that references an existing release tag.

The release job still uses the trusted self-hosted Windows/X64 runner because the current signing model expects a keystore and signing environment that remain outside the repository checkout.

Public PR code is never executed by this release workflow. The release workflow checks out only the exact owner-controlled release tag before running the project scripts.

Release flow:

```text
exact owner-controlled tag
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

For local builds, the existing gitignored `release-signing.env` loader may populate these variables. On the self-hosted release runner, configure the runner/service environment or another secure secret-injection mechanism so that the release process receives them without storing the values in the checkout.

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

## Verification before the first automated release

1. open or update a PR and confirm public CI runs on GitHub-hosted Windows;
2. confirm `scripts/ci.ps1` succeeds both locally and in GitHub-hosted CI;
3. confirm a repository Issue/PR is added to Development Project #2 without using the self-hosted runner;
4. confirm no Project PAT or signing values appear in logs;
5. verify the self-hosted runner is used only for the owner-controlled release workflow;
6. run `scripts/release.ps1` only after release signing is configured and application version metadata matches the intended tag;
7. test the release workflow with a deliberate release candidate/test tag before the first stable automated publication when practical.
