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
            if ($line -match 'fake tls client hello expected') {
                $fakeTlsExpectedLines++
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

    [pscustomobject][ordered]@{
        Phase = $Phase
        DurationSeconds = $DurationSeconds
        AttemptedConnections = $AttemptedConnections
        RedirectResponses = $RedirectResponses
        ConnectionFailures = $ConnectionFailures
        RejectedLogLines = $rejectedLogLines
        FakeTlsExpectedLogLines = $fakeTlsExpectedLines
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
