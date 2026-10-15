[CmdletBinding()]
param()

Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'

. (Join-Path (Split-Path -Parent $PSScriptRoot) 'worker-issue-101-native-command.ps1')

$windowsPowerShell = (Get-Command 'powershell.exe' -ErrorAction Stop).Source

# A native command may write harmless diagnostics to stderr even on success.
$okOutput = @(Invoke-WorkerSmokeNativeCommand -FilePath $windowsPowerShell -Arguments @(
    '-NoProfile', '-NonInteractive', '-Command',
    '[Console]::Error.WriteLine(424242); exit 0'
))
if (-not ($okOutput | Where-Object { $_ -match '424242' })) {
    throw 'Expected to capture native stderr without aborting on exit code 0.'
}

$failed = $false
try {
    Invoke-WorkerSmokeNativeCommand -FilePath $windowsPowerShell -Arguments @(
        '-NoProfile', '-NonInteractive', '-Command',
        '[Console]::Error.WriteLine(171717); exit 17'
    ) | Out-Null
}
catch {
    $failed = $_.Exception.Message -match 'exit 17'
}
if (-not $failed) {
    throw 'A nonzero native exit code must still fail the smoke.'
}

$mainScript = Join-Path (Split-Path -Parent $PSScriptRoot) 'worker-issue-101-device-smoke.ps1'
$source = Get-Content -LiteralPath $mainScript -Raw
if ($source -match 'shell monkey') {
    throw 'Worker smoke must not launch Telegram via monkey.'
}
if ($source -notmatch 'android.intent.action.MAIN' -or $source -notmatch 'android.intent.category.LAUNCHER') {
    throw 'Telegram launcher must use the explicit Android MAIN/LAUNCHER intent.'
}
Write-Host 'Issue #101 native stderr and Telegram launch regression: PASS'
