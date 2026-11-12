$ErrorActionPreference = 'Stop'
. (Join-Path $PSScriptRoot '../issue-138-storm-report.ps1')

function Assert-Equal {
    param([string]$Name, $Expected, $Actual)
    if ($Expected -ne $Actual) {
        throw "$Name : expected '$Expected', got '$Actual'"
    }
    Write-Host "$Name : PASS"
}

# Reconstruct the count/rate from the archived 2026-11-05 symptom;
# this fixture does not claim that the actual original archive was replayed.
$oldFormat = @(
    1..11801 | ForEach-Object {
        '11-05 16:56:27.123 900 100 I TgWsProxy: MTProto route rejected remote=127.0.0.1:55000 reason=handshake_failed error=fake tls client hello expected'
    }
)
$oldReport = Get-Issue138StormReport -Phase 'Baseline' -Lines $oldFormat -DurationSeconds 34
Assert-Equal 'Old-format storm count' 11801 $oldReport.RejectedLogLines
Assert-Equal 'Old-format ClientHello count' 11801 $oldReport.FakeTlsExpectedLogLines
Assert-Equal 'Old-format rate' 347.09 $oldReport.RejectionLogRatePerSecond
Assert-Equal 'Old-format log storm detected' $true $oldReport.HighRejectionLogVolume
Assert-Equal 'No fabricated successful backend' 0 $oldReport.AwgDuplexSessions

$sampled = @(1, 2, 4, 8, 16, 32, 64 | ForEach-Object {
    "11-11 12:06:27 I TgWsProxy: MTProto route rejected remote=127.0.0.1:55000 reason=handshake_failed error=fake tls client hello expected: ingress_class=unrecognized_binary reject_class=unexpected_client_hello class_count=$_"
})
$sampled += '11-11 12:06:28 I TgWsProxy: MTProto route connected actual_backend=awg_warp fallback_used=true'
$sampled += '11-11 12:06:31 I TgWsProxy: MTProto bridge closed actual_backend=awg_warp up_bytes=1024 down_bytes=2048 error=none'
$report = Get-Issue138StormReport -Phase 'Inject' -Lines $sampled -DurationSeconds 10 -AttemptedConnections 64 -RedirectResponses 64
Assert-Equal 'Sampled logging lines' 7 $report.RejectedLogLines
Assert-Equal 'Sampled class metadata records' 7 $report.SampleMetadataLogLines
Assert-Equal 'Sampled runtime is not reported legacy' $false $report.LikelyUninstrumentedRuntime
Assert-Equal 'Sampled logging is bounded' $false $report.HighRejectionLogVolume
Assert-Equal 'Last sampled cumulative count' 64 $report.LastSampledClassCount
Assert-Equal 'Largest sampled cumulative count' 64 $report.LargestSampledClassCount
Assert-Equal 'Redirect count' 64 $report.RedirectResponses
Assert-Equal 'Actual AWG duplex' 1 $report.AwgDuplexSessions
Assert-Equal 'Total duplex' 1 $report.DuplexSessions
Assert-Equal 'Route connected count' 1 $report.MtProtoConnections

# Reconstructed from user-confirmed 2026-11-12 Inject capture: 100
# HTTP 301 replies alongside 100 unsampled rejection log entries.
$legacyFakeTlsLog = @(
    1..100 | ForEach-Object {
        '11-12 12:01:34 I TgWsProxy: MTProto route rejected remote=127.0.0.1:50000 reason=handshake_failed error=fake tls client hello expected'
    }
)
$legacyFakeTls = Get-Issue138StormReport -Phase 'Inject' -Lines $legacyFakeTlsLog -DurationSeconds 11 -AttemptedConnections 100 -RedirectResponses 100
Assert-Equal 'Legacy Fake TLS redirect count' 100 $legacyFakeTls.RedirectResponses
Assert-Equal 'Legacy Fake TLS rejection count' 100 $legacyFakeTls.FakeTlsExpectedLogLines
Assert-Equal 'Legacy Fake TLS metadata count' 0 $legacyFakeTls.SampleMetadataLogLines
Assert-Equal 'Unsampled installed APK classification' $true $legacyFakeTls.LikelyUninstrumentedRuntime
Assert-Equal 'Unsampled installed APK diagnostic' $true ($legacyFakeTls.HandshakeDiagnosis -match 'predates')
Assert-Equal 'Unsampled log volume still excessive' $true $legacyFakeTls.HighRejectionLogVolume

$negative = @(
    'MTProto bridge closed actual_backend=awg_warp up_bytes=42 down_bytes=0 error=none'
    'MTProto bridge closed actual_backend=direct_tcp up_bytes=0 down_bytes=42 error=none'
    'no_mtproto_route_available'
    'FATAL EXCEPTION: main'
    'ANR in io.github.regstar2.tgwsproxy'
)
$negativeReport = Get-Issue138StormReport -Phase 'Baseline' -Lines $negative -DurationSeconds 5
Assert-Equal 'Do not count one-way as duplex' 0 $negativeReport.DuplexSessions
Assert-Equal 'No-route failure classified' 1 $negativeReport.NoRouteErrors
Assert-Equal 'Crash and ANR markers' 2 $negativeReport.CrashOrAnrMarkers

# End-to-end empty-file regression: reading an empty logcat capture must
# return an empty array, not $null, under Windows PowerShell 5.1.
$fixture = [System.IO.Path]::GetTempFileName()
try {
    $emptyCapture = Read-Issue138CaptureLines -Path $fixture
    Assert-Equal 'Empty capture remains non-null' $false ($null -eq $emptyCapture)
    Assert-Equal 'Empty capture has zero lines' 0 @($emptyCapture).Count
    $quietReport = Get-Issue138StormReport -Phase 'Baseline' -Lines $emptyCapture -DurationSeconds 5
    Assert-Equal 'Empty on-disk log parses with no rejections' 0 $quietReport.RejectedLogLines

    $missingCapture = Read-Issue138CaptureLines -Path "$fixture.missing"
    Assert-Equal 'Missing capture remains non-null' $false ($null -eq $missingCapture)
    Assert-Equal 'Missing capture has zero lines' 0 @($missingCapture).Count
    $missingReport = Get-Issue138StormReport -Phase 'Baseline' -Lines $missingCapture -DurationSeconds 5
    Assert-Equal 'Missing on-disk log parses with no rejections' 0 $missingReport.RejectedLogLines

    'one log line' | Set-Content -LiteralPath $fixture
    $oneCapture = Read-Issue138CaptureLines -Path $fixture
    Assert-Equal 'Single line remains an array' 1 @($oneCapture).Count
    Assert-Equal 'Single line content retained' 'one log line' $oneCapture[0]

    @('first', 'second') | Set-Content -LiteralPath $fixture
    $manyCapture = Read-Issue138CaptureLines -Path $fixture
    Assert-Equal 'Multiple log lines retained' 2 @($manyCapture).Count
    Assert-Equal 'Multiple log order preserved' 'second' $manyCapture[1]
}
finally {
    Remove-Item -LiteralPath $fixture -Force -ErrorAction SilentlyContinue
}

$wrongFrontend = @(
    1..10 | ForEach-Object {
        'MTProto route rejected remote=127.0.0.1:51000 reason=handshake_failed error=unsupported mtproto transport reject_class=unsupported_transport'
    }
)
$wrongFrontendReport = Get-Issue138StormReport -Phase 'Inject' -Lines $wrongFrontend -DurationSeconds 1 -AttemptedConnections 10 -RedirectResponses 0 -ConnectionFailures 10
Assert-Equal 'Unsupported transport category' 10 $wrongFrontendReport.UnsupportedTransportLogLines
Assert-Equal 'Unsupported transport is not Fake TLS expected' 0 $wrongFrontendReport.FakeTlsExpectedLogLines
Assert-Equal 'Unsupported transport suggests checking frontend' $true ($wrongFrontendReport.HandshakeDiagnosis -match 'masking domain')
Assert-Equal 'Unrelated MTProto rejects not counted as read errors' 0 $wrongFrontendReport.HandshakeReadFailureLogLines

$readFailure = Get-Issue138StormReport -Phase 'Inject' -Lines @(
    'MTProto route rejected remote=127.0.0.1:51000 reason=handshake_failed error=mtproto handshake read failed: EOF reject_class=handshake_read_failed'
) -DurationSeconds 1 -AttemptedConnections 1 -ConnectionFailures 1
Assert-Equal 'Handshake read failure category' 1 $readFailure.HandshakeReadFailureLogLines
Assert-Equal 'Read failure diagnostic' $true ($readFailure.HandshakeDiagnosis -match 'handshake read failure')

$fakeTls = Get-Issue138StormReport -Phase 'Inject' -Lines @(
    'MTProto route rejected remote=127.0.0.1:51000 reason=handshake_failed error=fake tls client hello expected: ingress_class=unrecognized_binary reject_class=unexpected_client_hello'
) -DurationSeconds 1 -AttemptedConnections 1 -RedirectResponses 1
Assert-Equal 'Fake TLS expected category' 1 $fakeTls.FakeTlsExpectedLogLines
Assert-Equal 'Proper redirect does not imply wrong frontend' $false ($fakeTls.HandshakeDiagnosis -match 'masking domain')

$empty = Get-Issue138StormReport -Phase 'Baseline' -Lines @() -DurationSeconds 5
Assert-Equal 'Empty capture has zero rejections' 0 $empty.RejectedLogLines
Assert-Equal 'Empty capture has zero route sessions' 0 $empty.MtProtoConnections
Write-Host 'Issue #138 storm report parser regression tests: PASS'
