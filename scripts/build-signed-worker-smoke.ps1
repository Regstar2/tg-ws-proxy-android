[CmdletBinding()]
param(
    [switch]$Install
)

Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'

$repoRoot = Split-Path -Parent $PSScriptRoot
$loader = Join-Path $PSScriptRoot 'load-release-signing.ps1'
if (Test-Path -LiteralPath $loader) {
    & $loader | Out-Null
}

foreach ($name in @('KEYSTORE_FILE', 'KEYSTORE_PASSWORD', 'KEY_PASSWORD')) {
    if ([string]::IsNullOrWhiteSpace([Environment]::GetEnvironmentVariable($name))) {
        throw "Missing $name: configure the ORIGINAL release key locally; never create a replacement key."
    }
}
if ([string]::IsNullOrWhiteSpace($env:KEY_ALIAS)) {
    $env:KEY_ALIAS = 'tgwsproxy'
}

$keyStore = $env:KEYSTORE_FILE
if (-not [IO.Path]::IsPathRooted($keyStore)) {
    $keyStore = Join-Path $repoRoot $keyStore
}
if (-not (Test-Path -LiteralPath $keyStore -PathType Leaf)) {
    throw "Existing release keystore not found: $keyStore"
}
$env:KEYSTORE_FILE = (Resolve-Path -LiteralPath $keyStore).ProviderPath

$previousFlag = [Environment]::GetEnvironmentVariable('TGWS_SIGNED_WORKER_SMOKE')
try {
    $env:TGWS_SIGNED_WORKER_SMOKE = '1'
    & (Join-Path $PSScriptRoot 'build-apk.ps1') -Configuration Debug
    if ($LASTEXITCODE -ne 0) {
        throw "Signed debug APK build failed (exit code $LASTEXITCODE)."
    }
}
finally {
    if ($null -eq $previousFlag) {
        Remove-Item Env:TGWS_SIGNED_WORKER_SMOKE -ErrorAction SilentlyContinue
    } else {
        $env:TGWS_SIGNED_WORKER_SMOKE = $previousFlag
    }
}

$apk = Join-Path $repoRoot 'artifacts\apk\debug\tgwsproxy-debug.apk'
if (-not (Test-Path -LiteralPath $apk -PathType Leaf)) {
    throw "Signed acceptance APK was not produced: $apk"
}

Write-Host "SIGNED WORKER SMOKE APK: $apk"
Write-Host ("SHA256: {0}" -f (Get-FileHash -LiteralPath $apk -Algorithm SHA256).Hash)
Write-Host 'Uses the locally configured release certificate and the current release versionCode.'

if ($Install) {
    $adbCommand = Get-Command adb -ErrorAction SilentlyContinue
    $sdkRoot = if ($env:ANDROID_SDK_ROOT) { $env:ANDROID_SDK_ROOT } else { 'C:\Android\SDK' }
    $adb = if ($adbCommand) {
        $adbCommand.Source
    } else {
        Join-Path $sdkRoot 'platform-tools\adb.exe'
    }
    if (-not (Test-Path -LiteralPath $adb)) {
        throw 'ADB not found. Set ANDROID_SDK_ROOT or add adb to PATH.'
    }
    & $adb install -r $apk
    if ($LASTEXITCODE -ne 0) {
        throw "adb install failed. Verify that the local release key matches the installed app. Do not uninstall."
    }
    Write-Host 'Signed Worker smoke APK installed without removing application data.'
}
