[CmdletBinding()]
param()

Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'

$smokeScript = Join-Path (Split-Path -Parent $PSScriptRoot) 'worker-issue-101-device-smoke.ps1'
$tokens = $null
$parseErrors = $null
$ast = [System.Management.Automation.Language.Parser]::ParseFile(
    $smokeScript, [ref]$tokens, [ref]$parseErrors
)
if ($parseErrors) {
    throw "Smoke script syntax invalid: $($parseErrors -join '; ')"
}
$reportFunction = $ast.Find({
    param($node)
    $node -is [System.Management.Automation.Language.FunctionDefinitionAst] -and
        $node.Name -eq 'Get-TransportReport'
}, $true)
if ($null -eq $reportFunction) { throw 'Get-TransportReport function missing' }
. ([scriptblock]::Create($reportFunction.Extent.Text))

$dc2 = @(
    'MTProto bridge closed signed_dc=2 actual_backend=cf_worker_ws fallback_used=false up_bytes=458752 down_bytes=0'
    'MTProto bridge closed signed_dc=2 actual_backend=cf_worker_ws fallback_used=false up_bytes=65536 down_bytes=0 error=write'
    'MTProto bridge closed signed_dc=2 actual_backend=cf_worker_ws fallback_used=false up_bytes=36415 down_bytes=0'
    'MTProto bridge closed signed_dc=2 actual_backend=cf_worker_ws fallback_used=false up_bytes=1234 down_bytes=0'
    'MTProto bridge closed signed_dc=2 actual_backend=cf_worker_ws fallback_used=false up_bytes=512 down_bytes=256'
    'MTProto bridge closed signed_dc=1 actual_backend=cf_worker_ws fallback_used=false up_bytes=512 down_bytes=256'
    'MTProto Worker connected worker_transport=legacy_websocket'
    'MTProto Worker TCP stalled session_id=fake elapsed_ms=2000'
    'MTProto Worker Flowseal parity WS write failed session_id=fake'
)
$report = Get-TransportReport -Transport 'legacy_websocket' -Lines $dc2 -LogName 'test.log'
if ($report.observedNetworkStatus -ne 'dc2_no_downstream_regression') {
    throw "Expected DC2 regression, got $($report.observedNetworkStatus)"
}
if ($report.dc2ZeroDownstreamCount -ne 4 -or $report.dc2CloseCount -ne 5) {
    throw 'Incorrect DC2 session counters'
}
if ($report.dc2ZeroDownstreamUpBytes -ne 561937) {
    throw "Incorrect DC2 silent upstream bytes: $($report.dc2ZeroDownstreamUpBytes)"
}
if ($report.tcpStallLogLines -ne 1 -or $report.websocketWriteFailureLines -ne 1) {
    throw 'TCP stall/write failure markers not recognized'
}
if ($report.errorLogLines -lt 2) { throw 'Failures not counted' }

$healthy = @(
    'MTProto bridge closed signed_dc=2 actual_backend=cf_worker_ws fallback_used=false up_bytes=128 down_bytes=512'
    'MTProto bridge closed signed_dc=2 actual_backend=cf_worker_ws fallback_used=false up_bytes=256 down_bytes=1024'
    'MTProto Worker connected worker_transport=chunk_relay'
)
$goodReport = Get-TransportReport -Transport 'chunk_relay' -Lines $healthy -LogName 'test.log'
if ($goodReport.observedNetworkStatus -ne 'two_way_bytes_observed_media_unverified' -or
    $goodReport.dc2ZeroDownstreamCount -ne 0) {
    throw 'Healthy two-way Chunk Relay data misclassified'
}
Write-Host 'Worker DC2 zero-downstream regression classification: PASS'
