# Repeatable local device gate for issue #135.
param(
    [switch]$SkipBuild,
    [switch]$SkipInstall,
    [string]$OutputDirectory = ""
)

$ErrorActionPreference = "Stop"
$Root = (Resolve-Path (Join-Path $PSScriptRoot "..")).Path
Set-Location $Root
$IsolatedGradleProperty = "-Pissue135IsolatedDeviceTest=true"
$TestPackage = "io.github.regstar2.tgwsproxy.issue135"

if (-not (Get-Command adb -ErrorAction SilentlyContinue)) {
    throw "adb not found in PATH"
}
if (-not (Test-Path ".\gradlew.bat")) {
    throw "Gradle wrapper missing"
}
$Connected = @(adb devices | Where-Object { $_ -match '^\S+\s+device\s*$' })
if ($LASTEXITCODE -ne 0) { throw "adb devices failed" }
if ($Connected.Count -ne 1) {
    throw "Expected exactly one authorized device; got $($Connected.Count)"
}
$Serial = ($Connected[0] -split '\s+')[0]
$Stamp = Get-Date -Format "yyyyMMdd-HHmmss"
if ([string]::IsNullOrWhiteSpace($OutputDirectory)) {
    $OutputDirectory = Join-Path $Root "artifacts/issue-135-device/$Stamp"
}
New-Item -ItemType Directory -Force -Path $OutputDirectory | Out-Null
$OutputDirectory = (Resolve-Path $OutputDirectory).Path
$Status = "FAIL"

try {
    @(
        "serial=$Serial",
        "android_release=$(adb -s $Serial shell getprop ro.build.version.release)",
        "device_model=$(adb -s $Serial shell getprop ro.product.model)",
        "git_head=$(git rev-parse HEAD)",
        "test_application_id=$TestPackage"
    ) | Set-Content -Path (Join-Path $OutputDirectory "device.txt") -Encoding UTF8

    if (-not $SkipBuild) {
        & .\gradlew.bat $IsolatedGradleProperty :app:testDebugUnitTest :app:lintDebug :app:assembleDebug
        if ($LASTEXITCODE -ne 0) { throw "Build, lint or JVM tests failed" }
    }
    if (-not $SkipInstall) {
        $Apk = Join-Path $Root "app/build/outputs/apk/debug/app-debug.apk"
        if (-not (Test-Path $Apk)) { throw "Debug APK not found: $Apk" }
        Write-Host "Installing isolated test package: $TestPackage (existing release app preserved)"
        adb -s $Serial install -r $Apk
        if ($LASTEXITCODE -ne 0) { throw "Isolated APK installation failed (existing apps kept)" }
        $PackagePaths = @(adb -s $Serial shell pm path $TestPackage)
        if ($LASTEXITCODE -ne 0 -or @($PackagePaths | Where-Object { $_ -match '^package:' }).Count -eq 0) {
            throw "Isolated test package not found after install: $TestPackage"
        }
    }

    & .\gradlew.bat $IsolatedGradleProperty :app:connectedDebugAndroidTest
    if ($LASTEXITCODE -ne 0) { throw "Instrumentation tests failed" }
    $Status = "PASS"
} finally {
    $Results = "app/build/outputs/androidTest-results/connected"
    $Report = "app/build/reports/androidTests/connected"
    if (Test-Path $Results) {
        Copy-Item $Results (Join-Path $OutputDirectory "instrumentation-results") -Recurse -Force
    }
    if (Test-Path $Report) {
        Copy-Item $Report (Join-Path $OutputDirectory "instrumentation-report") -Recurse -Force
    }

    $RouteLog = @(adb -s $Serial logcat -d -v time '*:I' 2>$null |
        Where-Object { $_ -match 'MTProto route truth|MTProto fallback activated|Route policy' } |
        ForEach-Object {
            $_ -replace 'session_id=\S+', 'session_id=<redacted>' -replace '(target|domain|worker_url|inner_target)=\S+', '$1=<redacted>' -replace '((?:[0-9]{1,3}\.){3}[0-9]{1,3})', '<ip-redacted>'
        })
    $RouteLog | Set-Content -Path (Join-Path $OutputDirectory "route-log-redacted.txt") -Encoding UTF8
    "status=$Status" | Set-Content -Path (Join-Path $OutputDirectory "status.txt") -Encoding UTF8
    Write-Host "Issue #135 device gate: $Status"
    Write-Host "Evidence: $OutputDirectory"
}
