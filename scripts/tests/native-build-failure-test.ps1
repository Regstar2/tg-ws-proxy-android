[CmdletBinding()]
param([string]$NativeBuildScript)
$ErrorActionPreference = 'Stop'
if (-not $NativeBuildScript) { $NativeBuildScript = Join-Path (Split-Path -Parent $PSScriptRoot) 'build-native-android.ps1' }
$root = Split-Path -Parent (Split-Path -Parent $PSScriptRoot)
$fixture = Join-Path $root ('artifacts\issue-162\native-failure-' + [guid]::NewGuid().ToString('N'))
New-Item -ItemType Directory -Path (Join-Path $fixture 'scripts') -Force | Out-Null
$copy = Join-Path $fixture 'scripts\build-native-android.ps1'
Copy-Item -LiteralPath $NativeBuildScript -Destination $copy
$outputs = @()
foreach ($abi in @('armeabi-v7a','arm64-v8a')) {
    foreach ($path in @("app\src\main\jniLibs\$abi\libtgwsproxy.so", "artifacts\native\$abi\libtgwsproxy.so")) {
        $output = Join-Path $fixture $path
        New-Item -ItemType Directory -Force -Path (Split-Path $output) | Out-Null
        Set-Content -LiteralPath $output -Value 'stale-test-output'
        $outputs += $output
    }
}
$names = @('ANDROID_NDK_ROOT','GOOS','GOARCH','GOARM','CGO_ENABLED','CC')
$saved = @{}
foreach ($name in $names) { $saved[$name] = [Environment]::GetEnvironmentVariable($name) }
try {
    $env:ANDROID_NDK_ROOT = Join-Path $fixture 'missing-ndk'
    $env:GOOS='sentinel-os'; $env:GOARCH='sentinel-arch'; $env:GOARM='6'; $env:CGO_ENABLED='0'; $env:CC='sentinel-cc'
    $failed = $false
    try { & $copy } catch { $failed = $true }
    if (-not $failed) { throw 'Invalid NDK unexpectedly succeeded' }
    foreach ($output in $outputs) {
        if (Test-Path -LiteralPath $output) { throw "Failed build left stale native output: $output" }
    }
    if ($env:GOOS -ne 'sentinel-os' -or $env:GOARCH -ne 'sentinel-arch' -or $env:GOARM -ne '6' -or $env:CGO_ENABLED -ne '0' -or $env:CC -ne 'sentinel-cc') {
        throw 'Native build changed caller environment on failure'
    }
    Write-Host 'PASS: failed native build invalidates both ABI outputs and preserves environment'
} finally {
    foreach ($name in $names) { [Environment]::SetEnvironmentVariable($name, $saved[$name], 'Process') }
}
