[CmdletBinding()]
param(
    [ValidateRange(26, 35)][int]$ApiLevel = 26,
    [ValidateSet('armeabi-v7a', 'arm64-v8a')][string[]]$Abis = @('armeabi-v7a', 'arm64-v8a')
)
$ErrorActionPreference = 'Stop'
$repoRoot = Split-Path -Parent $PSScriptRoot
$go = Get-Command go -ErrorAction SilentlyContinue
$goExe = if ($go) { $go.Source } else { 'C:\Program Files\Go\bin\go.exe' }
$sdk = $env:ANDROID_SDK_ROOT
if (-not $sdk) { $sdk = $env:ANDROID_HOME }
if (-not $sdk) { $sdk = 'C:\Android\Sdk' }
$ndk = $env:ANDROID_NDK_ROOT
$saved = @{}
foreach ($name in @('GOOS','GOARCH','GOARM','CGO_ENABLED','CC')) {
    $saved[$name] = [Environment]::GetEnvironmentVariable($name, 'Process')
}
# Invalidate requested outputs before toolchain validation, including on failure.
foreach ($abi in $Abis) {
    foreach ($path in @("app\src\main\jniLibs\$abi\libtgwsproxy.so", "artifacts\native\$abi\libtgwsproxy.so")) {
        $target = Join-Path $repoRoot $path
        if (Test-Path -LiteralPath $target) { Remove-Item -LiteralPath $target -Force }
    }
}
$built = @()
try {
    if (-not (Test-Path $goExe)) { throw 'Go executable not found' }
    if (-not $ndk) {
        $ndk = (Get-ChildItem (Join-Path $sdk 'ndk') -Directory |
            Sort-Object { [version]$_.Name } -Descending | Select-Object -First 1).FullName
    }
    $toolchain = Join-Path $ndk 'toolchains\llvm\prebuilt\windows-x86_64\bin'
    $readelf = Join-Path $toolchain 'llvm-readelf.exe'
    foreach ($abi in $Abis) {
        $arm32 = $abi -eq 'armeabi-v7a'
        $triple = if ($arm32) { 'armv7a-linux-androideabi' } else { 'aarch64-linux-android' }
        $clang = Join-Path $toolchain "$triple$ApiLevel-clang.cmd"
        if (-not (Test-Path $clang) -or -not (Test-Path $readelf)) { throw "NDK tools missing for $abi API $ApiLevel" }
        $artifactDir = Join-Path $repoRoot "artifacts\native\$abi"
        New-Item -ItemType Directory -Force -Path $artifactDir | Out-Null
        $outLib = Join-Path $artifactDir 'libtgwsproxy.so'
        $env:GOOS = 'android'
        $env:GOARCH = if ($arm32) { 'arm' } else { 'arm64' }
        $env:GOARM = if ($arm32) { '7' } else { $null }
        $env:CGO_ENABLED = '1'
        $env:CC = $clang
        Push-Location (Join-Path $repoRoot 'native\tgwsproxy')
        try {
            & $goExe build -buildmode=c-shared -trimpath -o $outLib .
            if ($LASTEXITCODE -ne 0) { throw "go build failed for $abi ($LASTEXITCODE)" }
        } finally { Pop-Location }
        $header = (& $readelf -h $outLib) -join "`n"
        if ($LASTEXITCODE -ne 0) { throw "readelf failed for $abi" }
        $class = if ($arm32) { 'ELF32' } else { 'ELF64' }
        $machine = if ($arm32) { 'ARM' } else { 'AArch64' }
        if ($header -notmatch "Class:\s+$class\b" -or $header -notmatch "Machine:\s+$machine\s") {
            throw "Wrong ELF architecture for $abi"
        }
        $built += @{ Abi = $abi; Path = $outLib }
        Write-Host "Verified $abi ($class / $machine): $outLib"
    }
    # Publish only once all requested architectures have passed verification.
    foreach ($item in $built) {
        $destination = Join-Path $repoRoot "app\src\main\jniLibs\$($item.Abi)"
        New-Item -ItemType Directory -Force -Path $destination | Out-Null
        Copy-Item -LiteralPath $item.Path -Destination (Join-Path $destination 'libtgwsproxy.so') -Force
    }
} finally {
    foreach ($name in $saved.Keys) { [Environment]::SetEnvironmentVariable($name, $saved[$name], 'Process') }
}
