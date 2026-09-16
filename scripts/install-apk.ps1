[CmdletBinding()]
param(
    [ValidateSet("Debug", "Release")]
    [string]$Configuration = "Debug",

    [int]$WaitSeconds = 120
)

$ErrorActionPreference = "Stop"

$repoRoot = Split-Path -Parent $PSScriptRoot
$configLower = $Configuration.ToLowerInvariant()

function Resolve-AndroidSdkRoot {
    if ($env:ANDROID_SDK_ROOT -and (Test-Path $env:ANDROID_SDK_ROOT)) {
        return $env:ANDROID_SDK_ROOT
    }
    if (Test-Path "C:\Android\SDK") {
        return "C:\Android\SDK"
    }
    throw "ANDROID_SDK_ROOT is not set and C:\Android\SDK was not found"
}

$sdkRoot = Resolve-AndroidSdkRoot
$adb = Join-Path $sdkRoot "platform-tools\adb.exe"
if (-not (Test-Path $adb)) {
    throw "adb not found: $adb. Install Android SDK Platform-Tools."
}

# Install only the artifact copied by build-apk.ps1 after a successful Gradle build.
# app\build\outputs may contain stale files left by an interrupted or failed build.
$apk = Join-Path $repoRoot ("artifacts\apk\{0}\tgwsproxy-{0}.apk" -f $configLower)

if (-not (Test-Path $apk)) {
    Write-Host "Validated $Configuration APK missing, building..."
    & (Join-Path $PSScriptRoot "build-apk.ps1") -Configuration $Configuration -SkipNative:$false
    if (-not (Test-Path $apk)) {
        throw "APK still missing after successful build: $apk"
    }
}

Write-Host "Using adb: $adb"
Write-Host "APK: $apk ($([math]::Round((Get-Item $apk).Length / 1MB, 1)) MB)"

& $adb start-server | Out-Null

$deadline = (Get-Date).AddSeconds($WaitSeconds)
$deviceId = $null

while ((Get-Date) -lt $deadline) {
    $lines = & $adb devices 2>&1 | Where-Object { $_ -match "^\S+\s+\S+" -and $_ -notmatch "List of devices" }
    foreach ($line in $lines) {
        $parts = ($line -split "\s+", 2)
        if ($parts.Count -lt 2) { continue }
        $id, $state = $parts[0], $parts[1]
        if ($state -eq "unauthorized") {
            Write-Host "Device $id is unauthorized. On the phone: allow USB debugging (RSA fingerprint)."
        } elseif ($state -eq "device") {
            $deviceId = $id
            break
        }
    }
    if ($deviceId) { break }
    Start-Sleep -Seconds 2
}

if (-not $deviceId) {
    throw "No authorized device within ${WaitSeconds}s. Check USB cable, drivers, and accept the debugging prompt."
}

Write-Host "Installing on $deviceId ..."
& $adb -s $deviceId install -r $apk
if ($LASTEXITCODE -ne 0) {
    throw "adb install failed with exit code $LASTEXITCODE"
}

Write-Host "Installed successfully."
