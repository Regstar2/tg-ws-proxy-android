[CmdletBinding()]
param()

Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'

$root = Split-Path -Parent $PSScriptRoot
Set-Location $root

function Invoke-CheckedCommand {
    param(
        [Parameter(Mandatory = $true)]
        [string]$Label,

        [Parameter(Mandatory = $true)]
        [string]$FilePath,

        [string[]]$Arguments = @(),

        [string]$WorkingDirectory = $root
    )

    Write-Host "`n==> $Label"
    Push-Location $WorkingDirectory
    try {
        & $FilePath @Arguments
        $exitCode = $LASTEXITCODE
        if ($exitCode -ne 0) {
            throw "$Label failed with exit code $exitCode."
        }
    }
    finally {
        Pop-Location
    }
}

$go = Get-Command go -ErrorAction Stop
$pythonLauncher = Get-Command py -ErrorAction Stop
$windowsPowerShell = Get-Command powershell.exe -ErrorAction Stop
$gradle = Join-Path $root 'gradlew.bat'
if (-not (Test-Path $gradle)) {
    throw "Gradle wrapper not found: $gradle"
}

$nativeDir = Join-Path $root 'native\tgwsproxy'
if (-not (Test-Path $nativeDir)) {
    throw "Native runtime directory not found: $nativeDir"
}

$pythonRequirements = Join-Path $PSScriptRoot 'requirements-ci.txt'
if (-not (Test-Path $pythonRequirements)) {
    throw "Python CI requirements not found: $pythonRequirements"
}

$releaseAudit = Join-Path $PSScriptRoot 'audit-release.ps1'
if (-not (Test-Path $releaseAudit)) {
    throw "Release audit script not found: $releaseAudit"
}

$releaseScript = Join-Path $PSScriptRoot 'release.ps1'
if (-not (Test-Path $releaseScript)) {
    throw "Release script not found: $releaseScript"
}

foreach ($acceptanceScriptName in @('worker-issue-101-device-smoke.ps1', 'build-signed-worker-smoke.ps1', 'worker-issue-101-native-command.ps1', 'tests/worker-issue-101-native-command-test.ps1', 'tests/worker-issue-101-dc2-ab-test.ps1')) {
    $acceptanceScript = Join-Path $PSScriptRoot $acceptanceScriptName
    if (-not (Test-Path -LiteralPath $acceptanceScript)) {
        throw "Worker acceptance script not found: $acceptanceScript"
    }
    $parseTokens = $null
    $parseErrors = $null
    [System.Management.Automation.Language.Parser]::ParseFile(
        $acceptanceScript, [ref]$parseTokens, [ref]$parseErrors
    ) | Out-Null
    if ($parseErrors) {
        throw "Worker acceptance script has PowerShell syntax errors: $($parseErrors -join '; ')"
    }
}

$routeInstallScript = Join-Path $PSScriptRoot 'issue-135-rebuild-device.ps1'
$routeParseTokens = $null
$routeParseErrors = $null
[System.Management.Automation.Language.Parser]::ParseFile(
    $routeInstallScript, [ref]$routeParseTokens, [ref]$routeParseErrors
) | Out-Null
if ($routeParseErrors) { throw "Issue #135 installation script has invalid PowerShell syntax: $($routeParseErrors -join '; ')" }

$routeUiTestScript = Join-Path $PSScriptRoot 'issue-135-run-ui-tests.ps1'
if (-not (Test-Path -LiteralPath $routeUiTestScript)) {
    throw "Issue #135 bounded UI test script missing"
}
$uiParseTokens = $null
$uiParseErrors = $null
[System.Management.Automation.Language.Parser]::ParseFile(
    $routeUiTestScript, [ref]$uiParseTokens, [ref]$uiParseErrors
) | Out-Null
if ($uiParseErrors) {
    throw "Issue #135 bounded UI test script has invalid PowerShell syntax: $($uiParseErrors -join '; ')"
}
$uiTestSource = Get-Content -LiteralPath $routeUiTestScript -Raw
if ($uiTestSource -notmatch 'WaitForExit\(' -or
    $uiTestSource -notmatch 'Compress-Archive' -or
    $uiTestSource -match '(?im)^\s*(?:adb\s+[^\r\n]*\s+uninstall\b|adb\s+[^\r\n]*\s+pm\s+clear\b|[^#\r\n]*gradlew[^\r\n]*connectedDebugAndroidTest)') {
    throw "Issue #135 UI test runner lacks watchdog/ZIP or calls forbidden destructive commands"
}

# The live route smoke script runs on persistent devices; CI checks its syntax
# and enforces the bounded PID wait and actual-backend evidence without ADB.
$routeSmokeScript = Join-Path $PSScriptRoot 'issue-135-route-switch-smoke.ps1'
if (-not (Test-Path -LiteralPath $routeSmokeScript)) {
    throw "Issue #135 route switching smoke script is missing"
}
$smokeTokens = $null
$smokeErrors = $null
[System.Management.Automation.Language.Parser]::ParseFile(
    $routeSmokeScript, [ref]$smokeTokens, [ref]$smokeErrors
) | Out-Null
if ($smokeErrors) {
    throw "Issue #135 route smoke script has invalid PowerShell syntax: $($smokeErrors -join '; ')"
}
$smokeSource = Get-Content -LiteralPath $routeSmokeScript -Raw
if ($smokeSource -notmatch 'Start-Sleep -Milliseconds 500' -or
    $smokeSource -notmatch 'Duplex sessions by actual backend:' -or
    $smokeSource -match '(?im)^\s*(?:adb\s+[^\r\n]*\s+uninstall\b|adb\s+[^\r\n]*\s+pm\s+clear\b|[^#\r\n]*gradlew[^\r\n]*connectedDebugAndroidTest)') {
    throw "Issue #135 route smoke requires a bounded PID wait, backend evidence and safe app handling"
}

# Guard against an AGP instrumentation cleanup removing the production app.
$routeInstallSource = Get-Content -LiteralPath $routeInstallScript -Raw
if ($routeInstallSource -match '(?m)^[^#\r\n]*:app:connectedDebugAndroidTest') {
    throw "Issue #135 device acceptance must use direct instrumentation, not Gradle connected tests."
}
if ($routeInstallSource -notmatch 'Test-ApplicationInstalled' -or
    $routeInstallSource -notmatch 'AllowFreshInstall') {
    throw "Issue #135 device acceptance lacks its missing-package safety guard."
}
$gradleProperties = Get-Content -LiteralPath (Join-Path $root 'gradle.properties') -Raw
if ($gradleProperties -notmatch '(?m)^android\.injected\.androidTest\.leaveApksInstalledAfterRun=true\s*$') {
    throw "Connected device tests must not uninstall the existing application."
}

$routeResultParser = Join-Path $PSScriptRoot 'issue-135-instrumentation-status.ps1'
$routeResultTest = Join-Path $PSScriptRoot 'tests/issue-135-instrumentation-status-test.ps1'
foreach ($path in @($routeResultParser, $routeResultTest)) {
    if (-not (Test-Path -LiteralPath $path)) {
        throw "Issue #135 instrumentation result regression file is missing: $path"
    }
    $tokens = $null
    $errors = $null
    [System.Management.Automation.Language.Parser]::ParseFile(
        $path, [ref]$tokens, [ref]$errors
    ) | Out-Null
    if ($errors) {
        throw "Issue #135 instrumentation status script syntax error in $path : $($errors -join '; ')"
    }
}
Write-Host "`n==> Issue #135 instrumentation result classification (Windows PowerShell 5.1)"
Invoke-CheckedCommand -Label 'Issue #135 instrumentation results under Windows PowerShell 5.1' `
    -FilePath $windowsPowerShell.Source -Arguments @(
        '-NoProfile', '-ExecutionPolicy', 'Bypass', '-File', $routeResultTest
    )
Write-Host "`n==> Issue #135 instrumentation result classification (PowerShell)"
& $routeResultTest

$nativeAdbTest = Join-Path $PSScriptRoot 'tests\worker-issue-101-native-command-test.ps1'
Invoke-CheckedCommand `
    -Label 'Test native stderr handling under Windows PowerShell 5.1' `
    -FilePath $windowsPowerShell.Source `
    -Arguments @('-NoProfile', '-ExecutionPolicy', 'Bypass', '-File', $nativeAdbTest)

# CI is invoked by pwsh on GitHub Actions; verify both PowerShell engines.
& $nativeAdbTest

$dc2ReportTest = Join-Path $PSScriptRoot 'tests\worker-issue-101-dc2-report-test.ps1'
Invoke-CheckedCommand `
    -Label 'Test DC2 zero-downstream detection under Windows PowerShell 5.1' `
    -FilePath $windowsPowerShell.Source `
    -Arguments @('-NoProfile', '-ExecutionPolicy', 'Bypass', '-File', $dc2ReportTest)

& $dc2ReportTest

$dc2ABTest = Join-Path $PSScriptRoot 'tests\worker-issue-101-dc2-ab-test.ps1'
Invoke-CheckedCommand `
    -Label 'Test DC2 Legacy A/B target selection and fixture restoration under PowerShell 5.1' `
    -FilePath $windowsPowerShell.Source `
    -Arguments @('-NoProfile', '-ExecutionPolicy', 'Bypass', '-File', $dc2ABTest)
& $dc2ABTest

Write-Host "`n==> Audit release metadata, localization and tracked private files"
& $releaseAudit -ExpectedVersion '1.11.6' -ExpectedVersionCode 62
if ($LASTEXITCODE -ne 0) {
    throw "Release audit failed with exit code $LASTEXITCODE."
}

Invoke-CheckedCommand `
    -Label 'Validate release audit under Windows PowerShell 5.1' `
    -FilePath $windowsPowerShell.Source `
    -Arguments @(
        '-NoProfile',
        '-ExecutionPolicy', 'Bypass',
        '-File', $releaseAudit,
        '-ExpectedVersion', '1.11.6',
        '-ExpectedVersionCode', '62'
    )

Write-Host "`n==> Validate release-script metadata preflight"
& $releaseScript -Version 'v1.11.6' -PreflightOnly
if ($LASTEXITCODE -ne 0) {
    throw "Release-script preflight failed with exit code $LASTEXITCODE."
}

Invoke-CheckedCommand -Label 'Install Python build dependencies' -FilePath $pythonLauncher.Source -Arguments @('-3', '-m', 'pip', 'install', '--disable-pip-version-check', '--requirement', $pythonRequirements)
Invoke-CheckedCommand -Label 'Verify Go module' -FilePath $go.Source -Arguments @('mod', 'verify') -WorkingDirectory $nativeDir

$isWindowsHost = [System.Environment]::OSVersion.Platform -eq [System.PlatformID]::Win32NT
if ($isWindowsHost) {
    # The root package is a c-shared Android entry point and requires cgo. Full root/race
    # coverage runs in the Linux worker-transport job; Windows validates portable packages
    # here and compiles the production cgo path with the Android NDK during assembleDebug.
    Invoke-CheckedCommand -Label 'Run portable native Go tests' -FilePath $go.Source -Arguments @('test', './mtproxyfrontend', './tgwsroute') -WorkingDirectory $nativeDir
}
else {
    Invoke-CheckedCommand -Label 'Run native Go tests' -FilePath $go.Source -Arguments @('test', './...') -WorkingDirectory $nativeDir
}

Invoke-CheckedCommand -Label 'Run Android unit tests and build debug APK' -FilePath $gradle -Arguments @('--no-daemon', 'testDebugUnitTest', 'lintDebug', 'assembleDebug', 'assembleDebugAndroidTest', '--stacktrace')

$quickSettingsManifestTest = Join-Path $PSScriptRoot 'tests\quick-settings-preferences-manifest-test.ps1'
Write-Host "`n==> Verify merged Android Quick Settings preferences manifest"
& $quickSettingsManifestTest

$debugApk = Join-Path $root 'app\build\outputs\apk\debug\app-debug.apk'
if (-not (Test-Path $debugApk)) {
    throw "Expected debug APK was not produced: $debugApk"
}

Write-Host "`n==> Verify packaged Android application identity"
& (Join-Path $PSScriptRoot 'tests\application-identity-test.ps1') -ApkPath $debugApk
if ($LASTEXITCODE -ne 0) { throw "Android application identity verification failed." }

$nativeLib = Join-Path $root 'app\src\main\jniLibs\arm64-v8a\libtgwsproxy.so'
if (-not (Test-Path $nativeLib)) {
    throw "Expected native library was not produced: $nativeLib"
}

$auditScript = Join-Path $PSScriptRoot 'audit-apk-icons.ps1'
if (-not (Test-Path $auditScript)) {
    throw "APK audit script not found: $auditScript"
}

Write-Host "`n==> Audit packaged APK resources"
& $auditScript -ApkPath $debugApk
if ($LASTEXITCODE -ne 0) {
    throw "APK audit failed with exit code $LASTEXITCODE."
}

Write-Host "`nCI checks completed successfully."
Write-Host "Debug APK: $debugApk"
