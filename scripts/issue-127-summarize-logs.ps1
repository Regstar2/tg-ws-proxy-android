# Offline analysis of an existing Issue #127 device A/B capture.
# Does not run adb, rebuild APKs, change application data, or upload raw logs.
[CmdletBinding()]
param(
    [Parameter(Mandatory = $true)][string]$RunDir
)

Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'
$RunDir = (Resolve-Path -LiteralPath $RunDir -ErrorAction Stop).Path
$stages = @(
    @{ Name = 'tcp_dial_failure'; Pattern = 'direct_ws TCP dial failure' },
    @{ Name = 'tls_handshake_failure'; Pattern = 'direct_ws TLS handshake failure' },
    @{ Name = 'ws_upgrade_write_failure'; Pattern = 'direct_ws websocket upgrade write failure' },
    @{ Name = 'ws_upgrade_read_failure'; Pattern = 'direct_ws websocket upgrade read failure' },
    @{ Name = 'ws_http_upgrade_failure'; Pattern = 'direct_ws websocket upgrade failure' },
    @{ Name = 'ws_connect_other_failure'; Pattern = 'direct_ws WS connect failed' },
    @{ Name = 'tls_certificate_related'; Pattern = 'direct_ws .*certificate' },
    @{ Name = 'split_two_writes'; Pattern = 'ClientHello tcp_split performed two writes' },
    @{ Name = 'route_attempt'; Pattern = 'MTProto route truth .*attempt_backend=direct_ws' },
    @{ Name = 'route_connected'; Pattern = 'MTProto route connected .*actual_backend=direct_ws' },
    @{ Name = 'bridge_closed'; Pattern = 'MTProto bridge closed .*actual_backend=direct_ws' },
    @{ Name = 'local_handshake_rejected'; Pattern = 'MTProto route rejected .*reason=handshake_failed' },
    @{ Name = 'frontend_listening'; Pattern = 'MTProto frontend listening' }
)

$report = [System.Collections.Generic.List[object]]::new()
foreach ($profile in @('baseline', 'tcp_split')) {
    $path = Join-Path $RunDir ($profile + '.log')
    if (-not (Test-Path -LiteralPath $path)) {
        throw "Capture not found: $path"
    }

    $counts = @{}
    foreach ($stage in $stages) { $counts[$stage.Name] = 0 }
    $reasons = @{}
    $errors = @{}
    $read = [System.IO.StreamReader]::new($path)
    $logLineCount = 0
    $routeFailedCount = 0
    try {
        while (($line = $read.ReadLine()) -ne $null) {
            $logLineCount++
            foreach ($stage in $stages) {
                if ($line -match $stage.Pattern) { $counts[$stage.Name]++ }
            }
            if ($line -match 'MTProto route failed .*selected_backend=direct_ws') {
                $routeFailedCount++
                if ($line -match '\breason=([a-z0-9_]+)') {
                    $reason = $Matches[1]
                    if (-not $reasons.ContainsKey($reason)) { $reasons[$reason] = 0 }
                    $reasons[$reason]++
                }
            }
            if ($line -match 'direct_ws (?:TCP dial failure|TLS handshake failure|websocket upgrade .*failure|WS connect failed)') {
                $category = if ($line -match '(?i)(i/o timeout|connection timed out|timeout exceeded|deadline exceeded)') {
                    'timeout'
                } elseif ($line -match '(?i)(no route to host|network is unreachable|network unreachable)') {
                    'unreachable'
                } elseif ($line -match '(?i)(connection refused|actively refused)') {
                    'connection_refused'
                } elseif ($line -match '(?i)(certificate|x509|unknown authority)') {
                    'certificate'
                } elseif ($line -match '(?i)(reset by peer|connection reset)') {
                    'connection_reset'
                } elseif ($line -match '(?i)(EOF|unexpected EOF)') {
                    'eof'
                } else { 'other_or_unspecified' }
                if (-not $errors.ContainsKey($category)) { $errors[$category] = 0 }
                $errors[$category]++
            }
        }
    } finally {
        $read.Dispose()
    }

    $report.Add([pscustomobject]@{
        profile = $profile
        totalLogLines = $logLineCount
        directRouteFailed = $routeFailedCount
        stages = [ordered]@{}
        routeFailureReasons = [ordered]@{}
        transportErrorTypes = [ordered]@{}
    })
    foreach ($key in @($counts.Keys | Sort-Object)) {
        $report[$report.Count - 1].stages[$key] = $counts[$key]
    }
    foreach ($key in @($reasons.Keys | Sort-Object)) {
        $report[$report.Count - 1].routeFailureReasons[$key] = $reasons[$key]
    }
    foreach ($key in @($errors.Keys | Sort-Object)) {
        $report[$report.Count - 1].transportErrorTypes[$key] = $errors[$key]
    }
}
$output = Join-Path $RunDir 'direct-stage-summary.json'
$report.ToArray() | ConvertTo-Json -Depth 6 | Set-Content -LiteralPath $output -Encoding UTF8
Get-Content -LiteralPath $output
Write-Host "Non-sensitive stage summary: $output"
