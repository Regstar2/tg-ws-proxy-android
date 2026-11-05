# Run Issue #135 Android tests individually with a bounded watchdog.
# Never uninstalls an APK, clears data, or invokes Gradle connectedAndroidTest.
param(
    [string]$Serial = "",
    [ValidateRange(15, 300)]
    [int]$PerTestTimeoutSeconds = 60,
    [string]$ReportRoot = ""
)

$ErrorActionPreference = "Stop"
$Root = (Resolve-Path (Join-Path $PSScriptRoot "..")).Path
$AppId = "io.github.regstar2.tgwsproxy"
$Runner = "$AppId.test/androidx.test.runner.AndroidJUnitRunner"
$Adb = (Get-Command adb -ErrorAction Stop).Source

if ([string]::IsNullOrWhiteSpace($Serial)) {
    $Devices = @(adb devices | Where-Object { $_ -match '^\S+\s+device\s*$' })
    if ($LASTEXITCODE -ne 0 -or $Devices.Count -ne 1) {
        throw "Exactly one authorized Android device is required"
    }
    $Serial = ($Devices[0] -split '\s+')[0]
}
$Packages = @(adb -s $Serial shell pm path $AppId)
if ($LASTEXITCODE -ne 0 -or @($Packages | Where-Object { $_ -match '^package:' }).Count -ne 1) {
    throw "Main application is missing. No installation or cleanup attempted."
}
$TestPackages = @(adb -s $Serial shell pm path "$AppId.test")
if ($LASTEXITCODE -ne 0 -or @($TestPackages | Where-Object { $_ -match '^package:' }).Count -ne 1) {
    throw "Test APK is missing. Run the signed installer first, without uninstalling the main app."
}
$Instrumentations = @(adb -s $Serial shell pm list instrumentation)
$ExpectedRunner = "instrumentation:$Runner (target=$AppId)"
if ($LASTEXITCODE -ne 0 -or
    -not @($Instrumentations | Where-Object { $_.Trim() -eq $ExpectedRunner }).Count) {
    throw "The instrumentation runner is not registered against $AppId."
}

if ([string]::IsNullOrWhiteSpace($ReportRoot)) {
    $ReportRoot = Join-Path $Root "artifacts/issue135"
}
New-Item -ItemType Directory -Path $ReportRoot -Force | Out-Null
$Timestamp = Get-Date -Format "yyyyMMdd-HHmmss"
$RunDir = Join-Path $ReportRoot "ui-tests-$Timestamp"
$Zip = "$RunDir.zip"
New-Item -ItemType Directory -Path $RunDir -Force | Out-Null

$Class = "com.amurcanov.tgwsproxy.OrderedRoutesIssue135UiTest"
$Source = Join-Path $Root "app/src/androidTest/java/com/amurcanov/tgwsproxy/OrderedRoutesIssue135UiTest.kt"
if (-not (Test-Path -LiteralPath $Source)) { throw "UI test source is missing: $Source" }
$Methods = @(Get-Content -LiteralPath $Source | ForEach-Object {
    if ($_ -match '@Test\s+fun\s+([A-Za-z][A-Za-z0-9_]*)\s*\(') {
        $Matches[1]
    }
})
if ($Methods.Count -eq 0 -or $Methods.Count -ne @($Methods | Select-Object -Unique).Count) {
    throw "No unique Compose UI tests discovered in $Source"
}
$Tests = @("com.amurcanov.tgwsproxy.InstrumentationBootstrapIssue135Test#runnerCanAccessTargetPackage") +
    @($Methods | ForEach-Object { "$Class#$_" })
$Results = [System.Collections.Generic.List[object]]::new()
$StopSuite = $false
$LogcatPath = Join-Path $RunDir "android-logcat.txt"
$PackageStatePath = Join-Path $RunDir "package-state.txt"
Write-Host "=== Issue 135: bounded Android instrumentation ==="
Write-Host "Device: $Serial; tests: $($Tests.Count); timeout/test: $PerTestTimeoutSeconds seconds"
Write-Host "Results: $RunDir"

try {
    for ($Index = 0; $Index -lt $Tests.Count; $Index++) {
        $Test = $Tests[$Index]
        $Method = ($Test -split '#')[-1]
        $BaseName = "{0:D2}-{1}" -f ($Index + 1), $Method
        $Stdout = Join-Path $RunDir "$BaseName.out.txt"
        $Stderr = Join-Path $RunDir "$BaseName.err.txt"
        $Status = "ERROR"
        $ExitCode = $null

        Write-Host "[$($Index + 1)/$($Tests.Count)] START $Test"
        try {
            if ($Test -like "*OrderedRoutesIssue135UiTest#*") {
                # MIUI 14 can deny ActivityScenario's in-process ComponentActivity
                # launch while the application is in the background.
                # Foreground the existing launcher Activity from the ADB shell
                # before instrumentation. This never clears app data.
                $ForegroundLog = Join-Path $RunDir "$BaseName.foreground.txt"
                $ForegroundResult = @(& $Adb -s $Serial shell am start -n `
                    "$AppId/com.amurcanov.tgwsproxy.MainActivity" 2>&1)
                $ForegroundExit = $LASTEXITCODE
                $ForegroundResult | Out-File -LiteralPath $ForegroundLog -Encoding UTF8
                if ($ForegroundExit -ne 0) {
                    throw "Unable to foreground target app before Compose instrumentation"
                }
                Start-Sleep -Milliseconds 800
            }
            $Args = @("-s", $Serial, "shell", "am", "instrument", "-w", "-r",
                "-e", "class", $Test, $Runner)
            $Process = Start-Process -FilePath $Adb -ArgumentList $Args `
                -RedirectStandardOutput $Stdout -RedirectStandardError $Stderr `
                -PassThru -NoNewWindow
            $Completed = $Process.WaitForExit($PerTestTimeoutSeconds * 1000)
            if (-not $Completed) {
                Stop-Process -Id $Process.Id -Force -ErrorAction SilentlyContinue
                $Status = "TIMEOUT"
                $StopSuite = $true
                Write-Warning "$Test timed out. The remaining UI tests were NOT started."
            } else {
                $Process.Refresh()
                $ExitCode = $Process.ExitCode
                $Output = @(Get-Content -LiteralPath $Stdout -ErrorAction SilentlyContinue)
                $CompletedTests = @($Output | Where-Object {
                    $_ -match '^INSTRUMENTATION_STATUS_CODE:\s*0\s*$'
                }).Count
                $RunnerFinished = @($Output | Where-Object {
                    $_ -match '^INSTRUMENTATION_CODE:\s*-1\s*$'
                }).Count -eq 1
                $Failure = @($Output | Where-Object {
                    $_ -match '^INSTRUMENTATION_STATUS_CODE:\s*-2\s*$' -or
                    $_ -match 'FAILURES!!!|INSTRUMENTATION_FAILED|Process crashed'
                }).Count -gt 0
                # Some PowerShell/ADB combinations leave Process.ExitCode null even
                # after WaitForExit, despite AndroidJUnitRunner reporting completion.
                # A missing host exit code is NOT an Android test failure: require
                # exactly one successful test and the runner's terminal PASS marker.
                # A known nonzero host exit code always remains a failure.
                $HostExitedSuccessfully = $null -eq $ExitCode -or $ExitCode -eq 0
                $Status = if ($HostExitedSuccessfully -and $RunnerFinished -and
                    $CompletedTests -eq 1 -and -not $Failure) { "PASS" } else { "FAIL" }
            }
        } catch {
            Write-Warning "$Test runner failed: $_"
            $Status = "ERROR"
            $StopSuite = $true
        }
        $Results.Add([pscustomobject]@{
            Test = $Test; Status = $Status; ExitCode = $ExitCode
        })
        Write-Host "[$($Index + 1)/$($Tests.Count)] $Status $Method"

        if ($Status -ne "PASS") {
            # Capture diagnostics now, before a later run could overwrite the buffer.
            adb -s $Serial logcat -d -b main -b system -b crash -v threadtime -t 20000 |
                Out-File -LiteralPath $LogcatPath -Encoding UTF8
            if ($Index -eq 0 -or $Status -eq "TIMEOUT") {
                $StopSuite = $true
            }
        }
        if ($StopSuite) { break }
    }
} finally {
    if (-not (Test-Path -LiteralPath $LogcatPath)) {
        adb -s $Serial logcat -d -b main -b system -b crash -v threadtime -t 10000 |
            Out-File -LiteralPath $LogcatPath -Encoding UTF8
    }
    # Distinguish a Compose hang from the MIUI background-activity policy.
    Get-Content -LiteralPath $LogcatPath -ErrorAction SilentlyContinue |
        Select-String -Pattern 'MIUILOG- Permission Denied Activity',
            'Abort background activity starts',
            'ActivityScenario:.*intent does not match' |
        Select-Object -ExpandProperty Line |
        Set-Content -LiteralPath (Join-Path $RunDir "activity-launch-denials.txt") -Encoding UTF8

    adb -s $Serial shell pm path $AppId |
        Out-File -LiteralPath $PackageStatePath -Encoding UTF8
    adb -s $Serial shell pm list instrumentation |
        Out-File -LiteralPath (Join-Path $RunDir "instrumentation-registered.txt") -Encoding UTF8
    adb -s $Serial shell dumpsys activity processes |
        Out-File -LiteralPath (Join-Path $RunDir "activity-processes.txt") -Encoding UTF8

    $Results | Format-Table -AutoSize | Out-String |
        Set-Content -LiteralPath (Join-Path $RunDir "SUMMARY.txt") -Encoding UTF8
    $Results | Export-Csv -LiteralPath (Join-Path $RunDir "results.csv") `
        -Delimiter ";" -NoTypeInformation -Encoding UTF8

    Compress-Archive -Path (Join-Path $RunDir "*") `
        -DestinationPath $Zip -CompressionLevel Optimal -Force

    Write-Host "=== INSTRUMENTATION RESULTS ==="
    $Results | Format-Table Status, Test -AutoSize
    Write-Host "ZIP: $Zip"
    Write-Host "Production package remains installed: $(
        @(Get-Content -LiteralPath $PackageStatePath | Where-Object { $_ -match '^package:' }).Count -gt 0
    )"
    Write-Host "No APK uninstall or data clear was performed."
}

if (@($Results | Where-Object { $_.Status -ne "PASS" }).Count -gt 0 -or
    $Results.Count -ne $Tests.Count) {
    throw "On-device instrumentation is not PASS. Diagnostic ZIP: $Zip"
}
