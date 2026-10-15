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

# Exercise the same transport-phase planning that the device runner uses, without ADB.
$phasePlanAst = $ast.Find({
    param($node)
    $node -is [System.Management.Automation.Language.FunctionDefinitionAst] -and
        $node.Name -eq 'Get-WorkerSmokePhases'
}, $true)
if ($null -eq $phasePlanAst) { throw 'Get-WorkerSmokePhases function missing' }
. ([scriptblock]::Create($phasePlanAst.Extent.Text))
$chunkPlan = @(Get-WorkerSmokePhases -ChunkRelayOnly -ChunkRelayWorkerDomain 'chunk.example.workers.dev')
if ($chunkPlan.Count -ne 1 -or $chunkPlan[0].Name -ne 'chunk_relay' -or
    $chunkPlan[0].Domain -ne 'chunk.example.workers.dev' -or
    $chunkPlan[0].Transport -ne 'chunk_relay') {
    throw 'ChunkRelayOnly must not require or select a Legacy Worker'
}
$legacyPlan = @(Get-WorkerSmokePhases -LegacyOnly -LegacyWorkerDomain 'flowseal.example.workers.dev')
if ($legacyPlan.Count -ne 1 -or $legacyPlan[0].Transport -ne 'legacy_websocket') {
    throw 'LegacyOnly must not require or select a Chunk Relay Worker'
}
$combinedPlan = @(Get-WorkerSmokePhases -LegacyWorkerDomain 'flowseal.example.workers.dev' -ChunkRelayWorkerDomain 'chunk.example.workers.dev')
if ($combinedPlan.Count -ne 2 -or $combinedPlan[0].Name -ne 'legacy_websocket' -or
    $combinedPlan[1].Name -ne 'chunk_relay') {
    throw 'Combined smoke must preserve Legacy and Chunk Relay independent phases'
}
$dc2Plan = @(Get-WorkerSmokePhases -LegacyOnly -LegacyDC2AB -LegacyWorkerDomain 'flowseal.example.workers.dev')
if ($dc2Plan.Count -ne 2 -or $dc2Plan[0].Dc2Target -ne '149.154.167.51' -or
    $dc2Plan[1].Dc2Target -ne '149.154.167.220') {
    throw 'Legacy-only DC2 A/B phase selection must remain unchanged'
}

Write-Host 'Worker DC2 zero-downstream regression classification: PASS'
