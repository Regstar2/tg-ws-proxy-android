# AndroidJUnitRunner is the authority when the Windows adb process exit code is missing.
# Host nonzero exit, incomplete reports, failed tests and runner crashes are all FAIL.
function Get-Issue135InstrumentationStatus {
    param(
        [AllowNull()][object]$HostExitCode,
        [AllowEmptyCollection()][string[]]$OutputLines
    )

    if ($null -ne $HostExitCode -and $HostExitCode -ne 0) { return "FAIL" }

    $PassedTests = @($OutputLines | Where-Object {
        $_ -match '^INSTRUMENTATION_STATUS_CODE:\s*0\s*$'
    }).Count
    $Finished = @($OutputLines | Where-Object {
        $_ -match '^INSTRUMENTATION_CODE:\s*-1\s*$'
    }).Count -eq 1
    $Failed = @($OutputLines | Where-Object {
        $_ -match '^INSTRUMENTATION_STATUS_CODE:\s*-2\s*$' -or
        $_ -match 'FAILURES!!!|INSTRUMENTATION_FAILED|INSTRUMENTATION_ABORTED|Process crashed'
    }).Count -gt 0

    if ($Finished -and $PassedTests -eq 1 -and -not $Failed) {
        return "PASS"
    }
    return "FAIL"
}
