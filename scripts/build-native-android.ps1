[CmdletBinding()]
param(
    [int]$ApiLevel = 26
)

$ErrorActionPreference = "Stop"

$repoRoot = Split-Path -Parent $PSScriptRoot
$sourceDir = Join-Path $repoRoot "native\tgwsproxy"

function Resolve-GoExe {
    $cmd = Get-Command go -ErrorAction SilentlyContinue
    if ($cmd) { return $cmd.Source }
    $fallback = "C:\Program Files\Go\bin\go.exe"
    if (Test-Path $fallback) { return $fallback }
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
    if (-not $latest) { throw "No Android NDK versions found under $ndkBase" }
    return $latest.FullName
}

$goExe = Resolve-GoExe
$ndkRoot = Resolve-AndroidNdkRoot
$ndkBin = Join-Path $ndkRoot "toolchains\llvm\prebuilt\windows-x86_64\bin"
if (-not (Test-Path $ndkBin)) {
    throw "Windows Android NDK toolchain not found: $ndkBin"
}
if (-not (Test-Path $sourceDir)) {
    throw "Go source directory not found: $sourceDir"
}

$targets = @(
    @{ Abi = "arm64-v8a"; GoArch = "arm64"; Triple = "aarch64-linux-android"; GoArm = $null },
    @{ Abi = "armeabi-v7a"; GoArch = "arm"; Triple = "armv7a-linux-androideabi"; GoArm = "7" },
    @{ Abi = "x86"; GoArch = "386"; Triple = "i686-linux-android"; GoArm = $null },
    @{ Abi = "x86_64"; GoArch = "amd64"; Triple = "x86_64-linux-android"; GoArm = $null }
)

$savedEnv = @{}
$envNames = @("GOOS", "GOARCH", "GOARM", "CGO_ENABLED", "CC", "CXX")
foreach ($name in $envNames) {
    $savedEnv[$name] = [Environment]::GetEnvironmentVariable($name, "Process")
}

try {
    $env:GOOS = "android"
    $env:CGO_ENABLED = "1"

    foreach ($target in $targets) {
        $compilerStem = "$($target.Triple)$ApiLevel-clang"
        $cc = Join-Path $ndkBin "$compilerStem.cmd"
        if (-not (Test-Path $cc)) { $cc = Join-Path $ndkBin $compilerStem }
        $cxxStem = "$($target.Triple)$ApiLevel-clang++"
        $cxx = Join-Path $ndkBin "$cxxStem.cmd"
        if (-not (Test-Path $cxx)) { $cxx = Join-Path $ndkBin $cxxStem }
        if (-not (Test-Path $cc)) {
            throw "Android C compiler not found for ABI $($target.Abi): $compilerStem"
        }
        if (-not (Test-Path $cxx)) {
            throw "Android C++ compiler not found for ABI $($target.Abi): $cxxStem"
        }

        $artifactDir = Join-Path $repoRoot "artifacts\native\$($target.Abi)"
        $jniLibDir = Join-Path $repoRoot "app\src\main\jniLibs\$($target.Abi)"
        $outLib = Join-Path $artifactDir "libtgwsproxy.so"
        New-Item -ItemType Directory -Force -Path $artifactDir, $jniLibDir | Out-Null

        $env:GOARCH = $target.GoArch
        if ($target.GoArm) { $env:GOARM = $target.GoArm }
        else { Remove-Item Env:GOARM -ErrorAction SilentlyContinue }
        $env:CC = $cc
        $env:CXX = $cxx

        Write-Host "==> Building $($target.Abi) (GOARCH=$($target.GoArch), API=$ApiLevel)"
        Push-Location $sourceDir
        try {
            & $goExe build -buildmode=c-shared -trimpath -o $outLib .
            if ($LASTEXITCODE -ne 0) {
                throw "Go native build failed for $($target.Abi) with exit code $LASTEXITCODE"
            }
        }
        finally {
            Pop-Location
        }

        if (-not (Test-Path $outLib) -or (Get-Item $outLib).Length -eq 0) {
            throw "Native library was not created for $($target.Abi)"
        }
        Copy-Item -Force $outLib (Join-Path $jniLibDir "libtgwsproxy.so")
        Write-Host "Built and packaged $($target.Abi): $outLib"
    }
}
finally {
    foreach ($name in $envNames) {
        if ($null -eq $savedEnv[$name]) {
            [Environment]::SetEnvironmentVariable($name, $null, "Process")
        }
        else {
            [Environment]::SetEnvironmentVariable($name, $savedEnv[$name], "Process")
        }
    }
}

Write-Host "All Android native ABIs built successfully: arm64-v8a, armeabi-v7a, x86, x86_64"
