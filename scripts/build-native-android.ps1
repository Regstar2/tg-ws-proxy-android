[CmdletBinding()]
param(
    [int]$ApiLevel = 26
)

$ErrorActionPreference = "Stop"

$repoRoot = Split-Path -Parent $PSScriptRoot
$sourceDir = Join-Path $repoRoot "native\tgwsproxy"
$artifactDir = Join-Path $repoRoot "artifacts\native\arm64-v8a"
$jniLibDir = Join-Path $repoRoot "app\src\main\jniLibs\arm64-v8a"
$outLib = Join-Path $artifactDir "libtgwsproxy.so"

function Resolve-GoExe {
    $cmd = Get-Command go -ErrorAction SilentlyContinue
    if ($cmd) {
        return $cmd.Source
    }
    $fallback = "C:\Program Files\Go\bin\go.exe"
    if (Test-Path $fallback) {
        return $fallback
    }
    throw "Go executable not found"
}

function Resolve-AndroidNdkRoot {
    if (-not $env:ANDROID_SDK_ROOT -and (Test-Path "C:\Android\SDK")) {
        $env:ANDROID_SDK_ROOT = "C:\Android\SDK"
    }
    if ($env:ANDROID_NDK_ROOT -and (Test-Path $env:ANDROID_NDK_ROOT)) {
        return $env:ANDROID_NDK_ROOT
    }
    if (-not $env:ANDROID_SDK_ROOT) {
        throw "ANDROID_SDK_ROOT is not set"
    }
    $ndkBase = Join-Path $env:ANDROID_SDK_ROOT "ndk"
    if (-not (Test-Path $ndkBase)) {
        throw "Android NDK not found under $ndkBase"
    }
    $latest = Get-ChildItem $ndkBase -Directory | Sort-Object Name -Descending | Select-Object -First 1
    if (-not $latest) {
        throw "No Android NDK versions found under $ndkBase"
    }
    return $latest.FullName
}

$goExe = Resolve-GoExe
$ndkRoot = Resolve-AndroidNdkRoot
$clang = Join-Path $ndkRoot "toolchains\llvm\prebuilt\windows-x86_64\bin\aarch64-linux-android$ApiLevel-clang.cmd"
if (-not (Test-Path $clang)) {
    $clang = Join-Path $ndkRoot "toolchains\llvm\prebuilt\windows-x86_64\bin\aarch64-linux-android$ApiLevel-clang"
}
if (-not (Test-Path $clang)) {
    throw "Android clang toolchain not found for API $ApiLevel under $ndkRoot"
}

New-Item -ItemType Directory -Force -Path $artifactDir | Out-Null
New-Item -ItemType Directory -Force -Path $jniLibDir | Out-Null

$oldGoos = $env:GOOS
$oldGoarch = $env:GOARCH
$oldCgo = $env:CGO_ENABLED
$oldCc = $env:CC

try {
    $env:GOOS = "android"
    $env:GOARCH = "arm64"
    $env:CGO_ENABLED = "1"
    $env:CC = $clang

    Push-Location $sourceDir
    try {
        & $goExe build -buildmode=c-shared -trimpath -o $outLib .
    } finally {
        Pop-Location
    }
    if ($LASTEXITCODE -ne 0) {
        throw "go build failed with exit code $LASTEXITCODE"
    }

    Copy-Item -Force $outLib (Join-Path $jniLibDir "libtgwsproxy.so")
    Write-Host "Native library built:" $outLib
    Write-Host "Native library copied to:" (Join-Path $jniLibDir "libtgwsproxy.so")
}
finally {
    $env:GOOS = $oldGoos
    $env:GOARCH = $oldGoarch
    $env:CGO_ENABLED = $oldCgo
    $env:CC = $oldCc
}
