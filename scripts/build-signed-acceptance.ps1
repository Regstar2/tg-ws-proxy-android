[CmdletBinding()]
param(
    [string]$Repository = 'C:\Base\projects\TGWSProxyAndroid',
    [string]$Worktree = 'C:\Base\projects\TGWSProxyAndroid-issue100',
    [string]$OutputDirectory = 'C:\Base\projects\TGWSProxyAndroid\artifacts\issue-100-signed',
    [switch]$Install
)

Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'
$Branch = 'fix/issue-100-chunk-relay-diagnostics'
$ReleaseVersion = 'v1.11.1'
$ApkName = 'TgWsProxy-issue-100-signed-arm64-v8a.apk'

function Assert-NativeSuccess {
    param([string]$Step)
    if ($LASTEXITCODE -ne 0) { throw "$Step failed (exit code $LASTEXITCODE)." }
}

if (-not (Test-Path (Join-Path $Repository '.git'))) {
    throw "Original Git repository was not found: $Repository"
}
foreach ($command in @('git', 'go', 'py', 'powershell.exe')) {
    if (-not (Get-Command $command -ErrorAction SilentlyContinue)) {
        throw "Required command not found: $command"
    }
}
if ($Install -and -not (Get-Command adb -ErrorAction SilentlyContinue)) {
    throw 'adb was not found on PATH.'
}

Write-Host '==> Load LOCAL release key from original checkout (secrets are never printed)'
$loader = Join-Path $Repository 'scripts\load-release-signing.ps1'
if (Test-Path $loader) { & $loader | Out-Null }

foreach ($name in @('KEYSTORE_FILE', 'KEYSTORE_PASSWORD', 'KEY_PASSWORD')) {
    if ([string]::IsNullOrWhiteSpace([Environment]::GetEnvironmentVariable($name))) {
        throw "Missing $name. Configure the ORIGINAL release key in $Repository\release-signing.env or process environment. Never generate a replacement signing key."
    }
}
if ([string]::IsNullOrWhiteSpace($env:KEY_ALIAS)) { $env:KEY_ALIAS = 'tgwsproxy' }

$keystore = $env:KEYSTORE_FILE
if (-not [IO.Path]::IsPathRooted($keystore)) {
    $keystore = Join-Path $Repository $keystore
}
if (-not (Test-Path -LiteralPath $keystore -PathType Leaf)) {
    throw "Release keystore file not found: $keystore. A GitHub Actions secret cannot be automatically retrieved from GitHub."
}
$env:KEYSTORE_FILE = (Resolve-Path -LiteralPath $keystore).ProviderPath

Write-Host '==> Fetch PR #102 (do not change main)'
& git -C $Repository fetch origin "+refs/heads/${Branch}:refs/remotes/origin/${Branch}"
Assert-NativeSuccess 'git fetch'
if (Test-Path -LiteralPath $Worktree) {
    if (-not (Test-Path -LiteralPath (Join-Path $Worktree '.git'))) {
        throw "Existing path is not a Git worktree: $Worktree"
    }
    $status = @(& git -C $Worktree status --porcelain)
    Assert-NativeSuccess 'git status'
    if ($status.Count -gt 0) {
        throw "Worktree has uncommitted changes, refusing to overwrite: $Worktree"
    }
    & git -C $Worktree switch --detach "refs/remotes/origin/$Branch"
    Assert-NativeSuccess 'git switch'
} else {
    & git -C $Repository worktree add --detach $Worktree "refs/remotes/origin/$Branch"
    Assert-NativeSuccess 'git worktree add'
}
$commit = (& git -C $Worktree rev-parse HEAD).Trim()
Assert-NativeSuccess 'git rev-parse'
Write-Host "PR revision: $commit"

Write-Host '==> Run local CI before signing'
Push-Location $Worktree
try {
    & (Join-Path $Worktree 'scripts\ci.ps1')
    Assert-NativeSuccess 'local CI'
    Write-Host '==> Build release with ORIGINAL signing certificate (no GitHub release)'
    & (Join-Path $Worktree 'scripts\release.ps1') -Version $ReleaseVersion
    Assert-NativeSuccess 'signed release'
} finally {
    Pop-Location
}

$sourceApk = Join-Path $Worktree 'dist\TgWsProxy-Android-v1.11.1-arm64-v8a.apk'
if (-not (Test-Path -LiteralPath $sourceApk)) {
    throw "Signed APK missing: $sourceApk"
}
New-Item -Path $OutputDirectory -ItemType Directory -Force | Out-Null
$apk = Join-Path $OutputDirectory $ApkName
Copy-Item -LiteralPath $sourceApk -Destination $apk -Force
$sha256 = (Get-FileHash -LiteralPath $apk -Algorithm SHA256).Hash.ToLowerInvariant()
Set-Content -LiteralPath "$apk.sha256" -Encoding Ascii -Value "$sha256  $ApkName"

Write-Host "SIGNED APK: $apk"
Write-Host "SHA256: $sha256"
Write-Host "Commit: $commit"
Write-Host 'APK signature and version verified by scripts/release.ps1.'

if ($Install) {
    Write-Host '==> Install signed APK without uninstalling or clearing data'
    & adb devices
    Assert-NativeSuccess 'adb devices'
    & adb install -r $apk
    Assert-NativeSuccess 'adb install -r'
    Write-Host 'Installed over existing app, without intentionally deleting data.'
}
