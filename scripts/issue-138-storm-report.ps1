# Read-Host-style pipeline assignments unwrap arrays, and PowerShell 5.1 turns
# an if-expression with no output into $null. Return an array as one object.
function Read-Issue138CaptureLines {
    [CmdletBinding()]
    param([Parameter(Mandatory = $true)][string]$Path)

    $lines = [string[]]@()
    if (Test-Path -LiteralPath $Path -PathType Leaf) {
        $lines = [string[]]@(Get-Content -LiteralPath $Path)
    }
    return ,$lines
}

# Pure log parser shared by the device reproducer and offline regression tests.
function Get-Issue138StormReport {
    [CmdletBinding()]
    param(
        [Parameter(Mandatory = $true)]
        [string]$Phase,

        [Parameter(Mandatory = $true)]
        [AllowEmptyCollection()]
        [string[]]$Lines,

        [ValidateRange(1, 180)]
        [int]$DurationSeconds = 35,

        [int]$AttemptedConnections = 0,

        [int]$RedirectResponses = 0,

        [int]$ConnectionFailures = 0
    )

    $rejectedLogLines = 0
    $fakeTlsExpectedLines = 0
    $unsupportedTransportLines = 0
    $handshakeReadFailureLines = 0
    $lastSampledClassCount = 0L
    $largestSampledClassCount = 0L
    $connected = 0
    $duplex = 0
    $awgDuplex = 0
    $routesUnavailable = 0
    $crashes = 0

    foreach ($line in $Lines) {
        if ($line -match 'MTProto route rejected .*reason=handshake_failed') {
            $rejectedLogLines++
            if ($line -match 'fake tls client hello expected|reject_class=unexpected_client_hello') {
                $fakeTlsExpectedLines++
            }
            if ($line -match 'unsupported mtproto transport|reject_class=unsupported_transport') {
                $unsupportedTransportLines++
            }
            if ($line -match 'mtproto handshake read failed|reject_class=handshake_read_failed') {
                $handshakeReadFailureLines++
            }
            if ($line -match '\bclass_count=(\d+)') {
                $lastSampledClassCount = [long]$Matches[1]
                $largestSampledClassCount = [Math]::Max(
                    $largestSampledClassCount, $lastSampledClassCount
                )
            }
        }
        if ($line -match 'MTProto route connected') { $connected++ }
        if ($line -match 'no_mtproto_route_available') { $routesUnavailable++ }
        if ($line -match 'FATAL EXCEPTION|ANR in') { $crashes++ }
        if ($line -match 'MTProto bridge closed .*up_bytes=(\d+)\s+down_bytes=(\d+)') {
            $up = [long]$Matches[1]
            $down = [long]$Matches[2]
            if ($up -gt 0 -and $down -gt 0) {
                $duplex++
                if ($line -match '\bactual_backend=awg_warp\b') { $awgDuplex++ }
            }
        }
    }

    $handshakeDiagnosis = 'No conclusive ingress-mode diagnosis from the available log entries.'
    if ($Phase -eq 'Inject' -and $AttemptedConnections -gt 0 -and
        $RedirectResponses -eq 0 -and $unsupportedTransportLines -gt 0 -and
        $fakeTlsExpectedLines -eq 0) {
        $handshakeDiagnosis = 'The listener rejected binary MTProto transport; verify that Fake TLS masking domain is configured and the installed APK listens on the expected port.'
    } elseif ($Phase -eq 'Inject' -and $AttemptedConnections -gt 0 -and
        $RedirectResponses -eq 0 -and $handshakeReadFailureLines -gt 0) {
        $handshakeDiagnosis = 'The listener reported a handshake read failure; inspect the captured log and the actual frontend configuration.'
    }

    [pscustomobject][ordered]@{
        Phase = $Phase
        DurationSeconds = $DurationSeconds
        AttemptedConnections = $AttemptedConnections
        RedirectResponses = $RedirectResponses
        ConnectionFailures = $ConnectionFailures
        RejectedLogLines = $rejectedLogLines
        FakeTlsExpectedLogLines = $fakeTlsExpectedLines
        UnsupportedTransportLogLines = $unsupportedTransportLines
        HandshakeReadFailureLogLines = $handshakeReadFailureLines
        HandshakeDiagnosis = $handshakeDiagnosis
        HighRejectionLogVolume = (
            ($rejectedLogLines / [double]$DurationSeconds -ge 100) -or
            ($Phase -eq 'Inject' -and $AttemptedConnections -ge 50 -and
                $rejectedLogLines -ge [Math]::Ceiling($AttemptedConnections * 0.25))
        )
        LastSampledClassCount = $lastSampledClassCount
        LargestSampledClassCount = $largestSampledClassCount
        RejectionLogRatePerSecond = [Math]::Round(
            $rejectedLogLines / [double]$DurationSeconds, 2
        )
        MtProtoConnections = $connected
        DuplexSessions = $duplex
        AwgDuplexSessions = $awgDuplex
        NoRouteErrors = $routesUnavailable
        CrashOrAnrMarkers = $crashes
    }
}
