# Reproduce the user's AndroidJUnitRunner bootstrap PASS with a missing adb exit code.
$ErrorActionPreference = "Stop"
. (Join-Path $PSScriptRoot "../issue-135-instrumentation-status.ps1")

$Passed = @(
    "INSTRUMENTATION_STATUS: class=com.amurcanov.tgwsproxy.InstrumentationBootstrapIssue135Test"
    "INSTRUMENTATION_STATUS: test=runnerCanAccessTargetPackage"
    "INSTRUMENTATION_STATUS_CODE: 1"
    "INSTRUMENTATION_STATUS: stream=."
    "INSTRUMENTATION_STATUS_CODE: 0"
    "INSTRUMENTATION_RESULT: stream="
    "Time: 0,012"
    "OK (1 test)"
    "INSTRUMENTATION_CODE: -1"
)

function Assert-Status {
    param(
        [string]$Case,
        [string]$Expected,
        [AllowNull()][object]$Exit,
        [string[]]$Lines
    )
    $Actual = Get-Issue135InstrumentationStatus -HostExitCode $Exit -OutputLines $Lines
    if ($Actual -ne $Expected) {
        throw "$Case : expected $Expected, got $Actual"
    }
    Write-Host "$Case : $Actual"
}

Assert-Status "Archived MIUI bootstrap, missing adb exit code" "PASS" $null $Passed
Assert-Status "Normal adb exit 0" "PASS" 0 $Passed
Assert-Status "Known adb failure must fail" "FAIL" 1 $Passed
Assert-Status "Test started but never finished" "FAIL" $null @($Passed | Where-Object {
    $_ -notmatch '^(INSTRUMENTATION_STATUS_CODE:\s*0|INSTRUMENTATION_CODE:)' })
Assert-Status "Runner exit marker absent" "FAIL" $null @($Passed | Where-Object {
    $_ -notmatch '^INSTRUMENTATION_CODE:' })
Assert-Status "Failure status from Android" "FAIL" $null ($Passed + "INSTRUMENTATION_STATUS_CODE: -2")
Assert-Status "Runner reported failure" "FAIL" $null @($Passed | ForEach-Object {
    if ($_ -eq "INSTRUMENTATION_CODE: -1") { "INSTRUMENTATION_CODE: -2" } else { $_ }
})
Assert-Status "Duplicate passed statuses" "FAIL" $null ($Passed + "INSTRUMENTATION_STATUS_CODE: 0")

Write-Host "Issue #135 instrumentation classification regression tests: PASS"
