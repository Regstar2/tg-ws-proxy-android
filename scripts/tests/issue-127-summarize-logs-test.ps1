# Offline acceptance for issue-127 log-stage classification.
# Uses synthetic log excerpts only, without ADB or network access.
Set-StrictMode -Version Latest
$ErrorActionPreference = "Stop"
$analyzer = Join-Path (Split-Path -Parent $PSScriptRoot) 'issue-127-summarize-logs.ps1'
$dir = Join-Path ([System.IO.Path]::GetTempPath()) ("issue127-summary-" + [guid]::NewGuid().ToString("N"))
New-Item -Path $dir -ItemType Directory -ErrorAction Stop | Out-Null
try {
    @(
        '11-09 I TgWsProxy: MTProto route truth frontend=MTProto selected_backend=direct_ws attempt_backend=direct_ws actual_backend=none reason=connecting'
        '11-09 W TgWsProxy: [MTProto] DC2 direct_ws TCP dial failure for example.test via 127.0.0.1 (timeout): i/o timeout'
        '11-09 W TgWsProxy: [MTProto] DC2 direct_ws TLS handshake failure for example.test via 127.0.0.1: connection timed out'
        '11-09 I TgWsProxy: MTProto route failed frontend=MTProto selected_backend=direct_ws actual_backend=none reason=direct_ws_ip_timeout error=i/o timeout'
    ) | Set-Content -LiteralPath (Join-Path $dir 'baseline.log') -Encoding UTF8

    @(
        '11-09 I TgWsProxy: MTProto route truth frontend=MTProto selected_backend=direct_ws attempt_backend=direct_ws actual_backend=none reason=connecting'
        '11-09 W TgWsProxy: [MTProto] DC2 direct_ws websocket upgrade read failure for example.test via 127.0.0.1: unexpected EOF'
        '11-09 D TgWsProxy: WebSocket PoC: ClientHello tcp_split performed two writes (TCP segments unverified)'
        '11-09 I TgWsProxy: MTProto route failed frontend=MTProto selected_backend=direct_ws actual_backend=none reason=direct_ws_connect_failed error=unexpected EOF'
    ) | Set-Content -LiteralPath (Join-Path $dir 'tcp_split.log') -Encoding UTF8

    & $analyzer -RunDir $dir | Out-Null
    # WinPS 5.1 emits a JSON array as ONE pipeline object, unlike
    # PowerShell 7. Assign the decoded value first, then materialize
    # the actual array contents. Do not wrap the JSON pipeline in @(...).
    $raw = Get-Content -LiteralPath (Join-Path $dir 'direct-stage-summary.json') -Raw
    if (-not $raw.TrimStart([char]0xFEFF, [char]' ', [char]10, [char]13, [char]9).StartsWith('[')) {
        throw "Stage summary must have a JSON array as its root"
    }
    $parsed = ConvertFrom-Json -InputObject $raw
    $data = @($parsed)
    if ($data.Count -ne 2) { throw "Expected two profiles, got $($data.Count)" }
    $baseline = @($data | Where-Object { $_.profile -eq 'baseline' })[0]
    $split = @($data | Where-Object { $_.profile -eq 'tcp_split' })[0]
    if ($baseline.stages.tcp_dial_failure -ne 1 -or
        $baseline.stages.tls_handshake_failure -ne 1 -or
        $baseline.stages.route_attempt -ne 1 -or
        $baseline.directRouteFailed -ne 1 -or
        $baseline.routeFailureReasons.direct_ws_ip_timeout -ne 1 -or
        $baseline.transportErrorTypes.timeout -ne 2) {
        throw "Baseline TCP/TLS error classification is incorrect"
    }
    if ($split.stages.ws_upgrade_read_failure -ne 1 -or
        $split.stages.split_two_writes -ne 1 -or
        $split.stages.route_attempt -ne 1 -or
        $split.routeFailureReasons.direct_ws_connect_failed -ne 1 -or
        $split.transportErrorTypes.eof -ne 1) {
        throw "Split WebSocket stage classification is incorrect"
    }
    $raw = Get-Content -LiteralPath (Join-Path $dir 'direct-stage-summary.json') -Raw
    if ($raw -match 'example\.test|127\.0\.0\.1|secret|token') {
        throw "Stage summary must not contain raw endpoints or credential-like fields"
    }
    Write-Host "Issue #127 offline stage summary regression PASS"
} finally {
    Remove-Item -LiteralPath $dir -Recurse -Force -ErrorAction SilentlyContinue
}
