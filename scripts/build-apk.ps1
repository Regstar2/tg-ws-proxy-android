[CmdletBinding()]
param(
    [ValidateSet("Debug", "Release")]
    [string]$Configuration = "Debug",

    [switch]$SkipNative,
    [switch]$IsolatedAbiSmoke
)

$ErrorActionPreference = "Stop"

$repoRoot = Split-Path -Parent $PSScriptRoot

if ($Configuration -eq "Release") {
    & (Join-Path $PSScriptRoot "load-release-signing.ps1") | Out-Null
}
$artifactRoot = Join-Path $repoRoot "artifacts\apk"
$configLower = $Configuration.ToLowerInvariant()
$apkOutDir = Join-Path $artifactRoot $configLower
$gradleWrapper = Join-Path $repoRoot "gradlew.bat"
$localProperties = Join-Path $repoRoot "local.properties"
$sourceApk = if ($Configuration -eq "Release") {
    Join-Path $repoRoot "app\build\outputs\apk\release\app-universal-release.apk"
} else {
    Join-Path $repoRoot "app\build\outputs\apk\debug\app-universal-debug.apk"
}
$destApk = Join-Path $apkOutDir ("tgwsproxy-" + $configLower + ".apk")

function Resolve-JavaHome {
    if ($env:JAVA_HOME -and (Test-Path (Join-Path $env:JAVA_HOME "bin\java.exe"))) {
        return $env:JAVA_HOME
    }
    $candidates = Get-ChildItem "C:\Program Files\Microsoft" -Directory -Filter "jdk-17*" -ErrorAction SilentlyContinue |
        Sort-Object Name -Descending
    if ($candidates) {
        return $candidates[0].FullName
    }
    throw "JAVA_HOME is not configured and JDK 17 was not found"
}

function Resolve-AndroidSdkRoot {
    if ($env:ANDROID_SDK_ROOT -and (Test-Path $env:ANDROID_SDK_ROOT)) {
        return $env:ANDROID_SDK_ROOT
    }
    if (Test-Path "C:\Android\SDK") {
        return "C:\Android\SDK"
    }
    throw "ANDROID_SDK_ROOT is not configured and C:\\Android\\SDK was not found"
}

function Remove-SyncConflictBuildArtifacts {
    $artifactDirs = @(
        (Join-Path $repoRoot "app\build"),
        (Join-Path $repoRoot "app\src\main\jniLibs")
    )

    foreach ($artifactDir in $artifactDirs) {
        if (-not (Test-Path $artifactDir)) {
            continue
        }

        $conflicts = Get-ChildItem -Path $artifactDir -Recurse -File -ErrorAction SilentlyContinue |
            Where-Object { $_.Name -like "*.sync-conflict-*" }

        foreach ($conflict in $conflicts) {
            Write-Host "Removing stale sync-conflict build artifact:" $conflict.FullName
            Remove-Item -LiteralPath $conflict.FullName -Force
        }
    }
}

Remove-SyncConflictBuildArtifacts

# A failed build must not leave an older APK looking like a fresh result.
$staleApks = @($sourceApk, $destApk)
foreach ($abi in @('armeabi-v7a', 'arm64-v8a', 'universal')) {
    $staleApks += Join-Path (Split-Path $sourceApk) "app-$abi-$configLower.apk"
    $destination = Join-Path $apkOutDir "tgwsproxy-$configLower-$abi.apk"
    $staleApks += $destination
    $staleApks += "$destination.sha256"
}
foreach ($staleApk in ($staleApks | Select-Object -Unique)) {
    if (Test-Path $staleApk) {
        Remove-Item -LiteralPath $staleApk -Force
    }
}

# Gradle owns native compilation and output validation.
if ($SkipNative) { Write-Warning 'SkipNative is retained for compatibility; Gradle still validates native outputs.' }

if (-not (Test-Path $gradleWrapper)) {
    throw "gradlew.bat not found. Generate the Gradle wrapper first."
}

$env:JAVA_HOME = Resolve-JavaHome
$env:ANDROID_SDK_ROOT = Resolve-AndroidSdkRoot
$env:ANDROID_HOME = $env:ANDROID_SDK_ROOT

$sdkDirEscaped = $env:ANDROID_SDK_ROOT.Replace("\", "\\")
Set-Content -Path $localProperties -Value "sdk.dir=$sdkDirEscaped"

$task = if ($Configuration -eq "Release") { ":app:assembleRelease" } else { ":app:assembleDebug" }
$prevEap = $ErrorActionPreference
$ErrorActionPreference = "Continue"
$buildArguments = @('--no-daemon', $task)
if ($IsolatedAbiSmoke) {
    if ($Configuration -ne 'Debug') { throw 'IsolatedAbiSmoke is debug-only' }
    $buildArguments += '-PisolatedAbiSmoke=true'
}
& $gradleWrapper @buildArguments 2>&1 | ForEach-Object { Write-Host $_ }
$gradleExit = $LASTEXITCODE
$ErrorActionPreference = $prevEap
if ($gradleExit -ne 0) {
    throw "Gradle task $task failed with exit code $gradleExit"
}

if (-not (Test-Path $sourceApk)) {
    throw "APK not found: $sourceApk"
}

New-Item -ItemType Directory -Force -Path $apkOutDir | Out-Null
foreach ($abi in @('armeabi-v7a', 'arm64-v8a', 'universal')) {
    $source = Join-Path (Split-Path $sourceApk) "app-$abi-$configLower.apk"
    if (-not (Test-Path -LiteralPath $source)) { throw "Missing APK: $source" }
    & py -3 (Join-Path $PSScriptRoot 'audit-apk-abis.py') $source $abi
    if ($LASTEXITCODE -ne 0) { throw "APK ABI audit failed: $abi" }
}
foreach ($abi in @('armeabi-v7a', 'arm64-v8a', 'universal')) {
    $source = Join-Path (Split-Path $sourceApk) "app-$abi-$configLower.apk"
    $destination = Join-Path $apkOutDir "tgwsproxy-$configLower-$abi.apk"
    Copy-Item -LiteralPath $source -Destination $destination -Force
    $hash = (Get-FileHash -LiteralPath $destination -Algorithm SHA256).Hash.ToLowerInvariant()
    Set-Content -LiteralPath "$destination.sha256" -Value "$hash  $([IO.Path]::GetFileName($destination))" -Encoding ascii
}
# Existing install-apk entry point remains valid and installs the universal APK.
Copy-Item -Force $sourceApk $destApk

Write-Host "APK copied to:" $destApk
