[CmdletBinding()]
param(
    [ValidateSet("Debug", "Release")]
    [string]$Configuration = "Debug",

    [switch]$SkipNative
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

if (-not $SkipNative) {
    & (Join-Path $PSScriptRoot "build-native-android.ps1")
    if ($LASTEXITCODE -ne 0) {
        throw "Native build failed with exit code $LASTEXITCODE"
    }
}

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
& $gradleWrapper "--no-daemon" $task 2>&1 | ForEach-Object { Write-Host $_ }
$gradleExit = $LASTEXITCODE
$ErrorActionPreference = $prevEap
if ($gradleExit -ne 0) {
    throw "Gradle task $task failed with exit code $gradleExit"
}

$sourceApk = if ($Configuration -eq "Release") {
    Join-Path $repoRoot "app\build\outputs\apk\release\app-release.apk"
} else {
    Join-Path $repoRoot "app\build\outputs\apk\debug\app-debug.apk"
}

if (-not (Test-Path $sourceApk)) {
    throw "APK not found: $sourceApk"
}

New-Item -ItemType Directory -Force -Path $apkOutDir | Out-Null
$destApk = Join-Path $apkOutDir ("tgwsproxy-" + $configLower + ".apk")
Copy-Item -Force $sourceApk $destApk

Write-Host "APK copied to:" $destApk
