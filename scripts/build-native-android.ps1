[CmdletBinding()]
param(
    [ValidateRange(26, 35)][int]$ApiLevel = 26,
    [ValidateSet('armeabi-v7a', 'arm64-v8a', 'x86', 'x86_64')]
    [string[]]$Abis = @('armeabi-v7a', 'arm64-v8a', 'x86', 'x86_64')
)

$ErrorActionPreference = 'Stop'
$repoRoot = Split-Path -Parent $PSScriptRoot
$go = Get-Command go -ErrorAction SilentlyContinue
$goExe = if ($go) { $go.Source } else { 'C:\Program Files\Go\bin\go.exe' }

$sdk = $env:ANDROID_SDK_ROOT
if (-not $sdk) { $sdk = $env:ANDROID_HOME }
if (-not $sdk) { $sdk = 'C:\Android\Sdk' }

$ndk = $env:ANDROID_NDK_ROOT

$targets = @{
    'armeabi-v7a' = @{
        GoArch = 'arm'; GoArm = '7'
        Triple = 'armv7a-linux-androideabi'
        ElfClass = 'ELF32'; MachinePattern = '(ARM|EM_ARM)'
    }
    'arm64-v8a' = @{
        GoArch = 'arm64'; GoArm = $null
        Triple = 'aarch64-linux-android'
        ElfClass = 'ELF64'; MachinePattern = '(AArch64|EM_AARCH64)'
    }
    'x86' = @{
        GoArch = '386'; GoArm = $null
        Triple = 'i686-linux-android'
        ElfClass = 'ELF32'; MachinePattern = '(Intel 80386|EM_386)'
    }
    'x86_64' = @{
        GoArch = 'amd64'; GoArm = $null
        Triple = 'x86_64-linux-android'
        ElfClass = 'ELF64'; MachinePattern = '(Advanced Micro Devices X86-64|X86-64|EM_X86_64)'
    }
}

$saved = @{}
foreach ($name in @('GOOS', 'GOARCH', 'GOARM', 'CGO_ENABLED', 'CC')) {
    $saved[$name] = [Environment]::GetEnvironmentVariable($name, 'Process')
}

# Remove stale outputs for every requested ABI before validating the toolchain.
foreach ($abi in $Abis) {
    foreach ($relativePath in @(
        "app\src\main\jniLibs\$abi\libtgwsproxy.so",
        "artifacts\native\$abi\libtgwsproxy.so"
    )) {
        $target = Join-Path $repoRoot $relativePath
        if (Test-Path -LiteralPath $target) {
            Remove-Item -LiteralPath $target -Force
        }
    }
}

$built = @()

try {
    if (-not (Test-Path -LiteralPath $goExe)) {
        throw "Go executable not found: $goExe"
    }

    if (-not $ndk) {
        $ndkBase = Join-Path $sdk 'ndk'
        if (-not (Test-Path -LiteralPath $ndkBase)) {
            throw "Android NDK not found: $ndkBase"
        }
        $latestNdk = Get-ChildItem $ndkBase -Directory |
            Sort-Object { [version]$_.Name } -Descending |
            Select-Object -First 1
        if (-not $latestNdk) { throw "No Android NDK found under $ndkBase" }
        $ndk = $latestNdk.FullName
    }

    $toolchain = Join-Path $ndk 'toolchains\llvm\prebuilt\windows-x86_64\bin'
    $readelf = Join-Path $toolchain 'llvm-readelf.exe'
    if (-not (Test-Path -LiteralPath $readelf)) {
        throw "NDK llvm-readelf not found: $readelf"
    }

    foreach ($abi in $Abis) {
        $target = $targets[$abi]
        $compilerStem = "$($target.Triple)$ApiLevel-clang"
        $clang = Join-Path $toolchain "$compilerStem.cmd"
        if (-not (Test-Path -LiteralPath $clang)) {
            $clang = Join-Path $toolchain "$compilerStem.exe"
        }
        if (-not (Test-Path -LiteralPath $clang)) {
            throw "NDK C compiler missing for $abi API $ApiLevel"
        }

        $artifactDir = Join-Path $repoRoot "artifacts\native\$abi"
        New-Item -ItemType Directory -Force -Path $artifactDir | Out-Null
        $outLib = Join-Path $artifactDir 'libtgwsproxy.so'

        $env:GOOS = 'android'
        $env:GOARCH = $target.GoArch
        $env:CGO_ENABLED = '1'
        $env:CC = $clang
        [Environment]::SetEnvironmentVariable('GOARM', $target.GoArm, 'Process')

        Write-Host "==> Building $abi (GOARCH=$($target.GoArch), API=$ApiLevel)"
        Push-Location (Join-Path $repoRoot 'native\tgwsproxy')
        try {
            & $goExe build -buildmode=c-shared -trimpath -o $outLib .
            if ($LASTEXITCODE -ne 0) {
                throw "Go build failed for $abi (exit code $LASTEXITCODE)"
            }
        }
        finally {
            Pop-Location
        }

        if (-not (Test-Path -LiteralPath $outLib) -or
            (Get-Item -LiteralPath $outLib).Length -le 0) {
            throw "Native library was not created for $abi"
        }

        $header = (& $readelf -h $outLib 2>&1) -join "`n"
        $readelfExit = $LASTEXITCODE
        if ($readelfExit -ne 0) {
            throw "llvm-readelf failed for $abi"
        }

        if ($header -notmatch "Class:\s+$($target.ElfClass)\b" -or
            $header -notmatch "Machine:\s+$($target.MachinePattern)\b") {
            throw "Wrong ELF architecture for $abi. Header:`n$header"
        }

        $built += @{ Abi = $abi; Path = $outLib }
        Write-Host "Verified $abi ($($target.ElfClass)): $outLib"
    }

    # Publish native libraries only after every requested ABI has built and passed ELF checks.
    foreach ($item in $built) {
        $destination = Join-Path $repoRoot "app\src\main\jniLibs\$($item.Abi)"
        New-Item -ItemType Directory -Force -Path $destination | Out-Null
        Copy-Item -LiteralPath $item.Path `
            -Destination (Join-Path $destination 'libtgwsproxy.so') -Force
    }
}
finally {
    foreach ($name in $saved.Keys) {
        [Environment]::SetEnvironmentVariable($name, $saved[$name], 'Process')
    }
}
