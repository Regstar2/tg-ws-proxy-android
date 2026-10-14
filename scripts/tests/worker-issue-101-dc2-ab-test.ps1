[CmdletBinding()]
param()

Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'

$scriptRoot = Split-Path -Parent $PSScriptRoot
$smokeScript = Join-Path $scriptRoot 'worker-issue-101-device-smoke.ps1'
$receiver = Join-Path (Split-Path -Parent $scriptRoot) 'app\src\debug\java\com\amurcanov\tgwsproxy\WorkerSmokeConfigReceiver.kt'

$tokens = $null
$errors = $null
$ast = [System.Management.Automation.Language.Parser]::ParseFile(
    $smokeScript, [ref]$tokens, [ref]$errors
)
if ($errors) { throw "DC2 A/B smoke PowerShell parser errors: $($errors -join '; ')" }
$source = Get-Content -LiteralPath $smokeScript -Raw
$kotlin = Get-Content -LiteralPath $receiver -Raw

foreach ($required in @(
    '[switch]$LegacyDC2AB', "Name = 'legacy_dc2_51'", "Name = 'legacy_dc2_220'",
    "Transport = 'legacy_websocket'", "Dc2Target = '149.154.167.51'",
    "Dc2Target = '149.154.167.220'", "'--es', 'dc2_target'",
    "Invoke-WorkerSmokeBroadcast -Action 'WORKER_SMOKE_RESTORE'",
    "Get-Dc2ABDestinationEvidence -Lines"
)) {
    if (-not $source.Contains($required)) { throw "DC2 A/B smoke missing: $required" }
}
foreach ($required in @(
    'DC2_AB_TARGETS = setOf("149.154.167.51", "149.154.167.220")',
    'transport == WorkerTransport.LEGACY_WEBSOCKET.prefValue',
    'DcIpTexts.KEY_DC2, dc2Target',
    'WorkerDestinationMode.FLOWSEAL_DC_MAP.prefValue',
    'DcIpTexts.KEY_DC2, ProxyRuntimeConfigFactory.KEY_WORKER_DESTINATION_MODE',
    '"last_proxy_port_default_1443_migrated", FlowsealDcPreset.PREF_ENABLED',
    'FlowsealDcPreset.PREF_ENABLED, false',
    'editor.remove(key)',
    'setResultData("restored")'
)) {
    if (-not $kotlin.Contains($required)) { throw "DC2 A/B backup/override missing: $required" }
}

$fn = $ast.Find({
    param($node)
    $node -is [System.Management.Automation.Language.FunctionDefinitionAst] -and
        $node.Name -eq 'Get-Dc2ABDestinationEvidence'
}, $true)
if ($null -eq $fn) { throw 'Missing DC2 destination evidence function' }
. ([scriptblock]::Create($fn.Extent.Text))

$sample = @(
    'MTProto Worker route signed_dc=2 dc=2 worker_dst=149.154.167.51',
    'MTProto Worker route signed_dc=2 dc=2 worker_dst=149.154.167.220',
    'MTProto Worker route signed_dc=2 dc=2 worker_dst=149.154.167.220',
    'MTProto Worker route signed_dc=4 dc=4 worker_dst=149.154.167.51',
    'MTProto Worker route signed_dc=2 dc=2 worker_dst=149.154.167.2200',
    'MTProto Worker route signed_dc=2 dc=2 worker_dst=149.154.167.5'
)
if ((Get-Dc2ABDestinationEvidence -Lines $sample -ExpectedWorkerDst '149.154.167.51') -ne 1) {
    throw 'DC2 .51 evidence counting should exclude other DCs and IPs'
}
if ((Get-Dc2ABDestinationEvidence -Lines $sample -ExpectedWorkerDst '149.154.167.220') -ne 2) {
    throw 'DC2 .220 evidence counting incorrect'
}
if ((Get-Dc2ABDestinationEvidence -Lines $sample -ExpectedWorkerDst '149.154.167.50') -ne 0) {
    throw 'Unobserved DC2 destination must not be reported as selected'
}

$reportAst = $ast.Find({
    param($node)
    $node -is [System.Management.Automation.Language.FunctionDefinitionAst] -and
        $node.Name -eq 'Get-TransportReport'
}, $true)
if ($null -eq $reportAst) { throw 'Missing transport report function' }
. ([scriptblock]::Create($reportAst.Extent.Text))

$closed = @(
    'MTProto bridge closed signed_dc=2 worker_dst=149.154.167.51 actual_backend=cf_worker_ws fallback_used=false up_bytes=800 down_bytes=0'
    'MTProto bridge closed signed_dc=2 worker_dst=149.154.167.220 actual_backend=cf_worker_ws fallback_used=false up_bytes=800 down_bytes=400'
    'MTProto Worker connected worker_transport=legacy_websocket'
)
$report51 = Get-TransportReport -Transport 'legacy_websocket' -Lines $closed -LogName 'fixture.log' -ExpectedWorkerDst '149.154.167.51'
$report220 = Get-TransportReport -Transport 'legacy_websocket' -Lines $closed -LogName 'fixture.log' -ExpectedWorkerDst '149.154.167.220'
if ($report51.workerOnlyCloseCount -ne 1 -or $report51.workerOnlyOtherTargetCloseCount -ne 1 -or
    $report51.dc2ZeroDownstreamCount -ne 1 -or $report51.bidirectionalBridgeCount -ne 0) {
    throw 'DC2 .51 A/B report was contaminated by the other endpoint'
}
if ($report220.workerOnlyCloseCount -ne 1 -or $report220.workerOnlyOtherTargetCloseCount -ne 1 -or
    $report220.dc2ZeroDownstreamCount -ne 0 -or $report220.bidirectionalBridgeCount -ne 1) {
    throw 'DC2 .220 A/B report was contaminated by the other endpoint'
}

$shortReplies = @(
    'MTProto Worker connected worker_transport=legacy_websocket'
    'MTProto bridge closed signed_dc=2 worker_dst=149.154.167.220 actual_backend=cf_worker_ws fallback_used=false up_bytes=1200 down_bytes=309 duration_ms=120'
    'MTProto bridge closed signed_dc=2 worker_dst=149.154.167.220 actual_backend=cf_worker_ws fallback_used=false up_bytes=1400 down_bytes=309 duration_ms=190'
    'MTProto bridge closed signed_dc=2 worker_dst=149.154.167.220 actual_backend=cf_worker_ws fallback_used=false up_bytes=5000 down_bytes=309 duration_ms=550'
)
$shortReport = Get-TransportReport -Transport 'legacy_websocket' -Lines $shortReplies -LogName 'fixture.log' -ExpectedWorkerDst '149.154.167.220'
if ($shortReport.observedNetworkStatus -ne 'dc2_uniform_short_replies_unverified' -or
    $shortReport.dc2UniformShortReplyBytes -ne 309 -or
    $shortReport.dc2PositiveReplyCount -ne 3) {
    throw "Uniform short DC2 responses must not be treated as usable MTProto: $($shortReport.observedNetworkStatus)"
}
$shortReplies[3] = $shortReplies[3].Replace('down_bytes=309', 'down_bytes=8192')
$longReport = Get-TransportReport -Transport 'legacy_websocket' -Lines $shortReplies -LogName 'fixture.log' -ExpectedWorkerDst '149.154.167.220'
if ($longReport.observedNetworkStatus -eq 'dc2_uniform_short_replies_unverified') {
    throw 'Meaningful mixed-size responses must not trigger uniform short reply signature'
}
$shortReplies[3] = $shortReplies[3].Replace('down_bytes=8192', 'down_bytes=309').Replace('duration_ms=550', 'duration_ms=3000')
$longLivedReport = Get-TransportReport -Transport 'legacy_websocket' -Lines $shortReplies -LogName 'fixture.log' -ExpectedWorkerDst '149.154.167.220'
if ($longLivedReport.observedNetworkStatus -eq 'dc2_uniform_short_replies_unverified') {
    throw 'Long-running legitimate sessions must not trigger uniform short reply signature'
}

Write-Host 'Worker DC2 legacy A/B target selection, restoration and report checks: PASS'
