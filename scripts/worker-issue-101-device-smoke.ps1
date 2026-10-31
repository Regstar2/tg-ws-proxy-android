[CmdletBinding()]
param(
    [ValidatePattern('^[a-z0-9.-]+[.]workers[.]dev$')]
    [string]$ChunkRelayWorkerDomain = '',

    [ValidatePattern('^[a-z0-9.-]+[.]workers[.]dev$')]
    [string]$LegacyWorkerDomain = '',

    [ValidateRange(15, 900)]
    [int]$PhaseSeconds = 90,

    [string]$TelegramPackage = 'org.telegram.messenger',
    [switch]$LegacyOnly,
    [switch]$ChunkRelayOnly,
    # Compare two physical DC2 endpoints via the same Legacy Worker.
    # This is an on-device Telegram smoke, not a TLS ClientHello probe.
    [switch]$LegacyDC2AB,
    [switch]$InstallSignedDebug,
    [string]$OutputRoot = ''
)

Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'

if ($LegacyOnly -and $ChunkRelayOnly) {
    throw '-LegacyOnly and -ChunkRelayOnly cannot be used together.'
}
if ($LegacyDC2AB -and -not $LegacyOnly) {
    throw 'Legacy DC2 A/B is exclusively a LegacyOnly device smoke; pass -LegacyOnly.'
}
if (-not $LegacyOnly -and [string]::IsNullOrWhiteSpace($ChunkRelayWorkerDomain)) {
    throw 'ChunkRelayWorkerDomain is required unless -LegacyOnly is specified.'
}
if (-not $ChunkRelayOnly -and [string]::IsNullOrWhiteSpace($LegacyWorkerDomain)) {
    throw 'LegacyWorkerDomain is required unless -ChunkRelayOnly is specified.'
}

$repoRoot = Split-Path -Parent $PSScriptRoot
if ([string]::IsNullOrWhiteSpace($OutputRoot)) {
    $OutputRoot = Join-Path $repoRoot 'artifacts\worker-issue-101'
}
$OutputRoot = [System.IO.Path]::GetFullPath($OutputRoot)

$adbCommand = Get-Command 'adb' -ErrorAction SilentlyContinue
if ($adbCommand) {
    $adb = $adbCommand.Source
} else {
    $sdkRoot = if ($env:ANDROID_SDK_ROOT) { $env:ANDROID_SDK_ROOT } else { 'C:\Android\SDK' }
    $adb = Join-Path $sdkRoot 'platform-tools\adb.exe'
    if (-not (Test-Path -LiteralPath $adb)) {
        throw 'ADB not found in PATH or Android SDK. Set ANDROID_SDK_ROOT.'
    }
}

if ($InstallSignedDebug) {
    & (Join-Path $PSScriptRoot 'build-signed-worker-smoke.ps1') -Install
    if ($LASTEXITCODE -ne 0) {
        throw 'Signed Worker smoke APK build/install failed.'
    }
}

$connected = @(
    & $adb devices |
        Where-Object { $_ -match '^\S+\s+device\s*$' } |
        ForEach-Object { ($_ -split '\s+')[0] }
)
if ($LASTEXITCODE -ne 0) { throw 'adb devices failed.' }
if ($connected.Count -ne 1) {
    throw "Expected one authorized Android device, found $($connected.Count)."
}
$deviceId = $connected[0]
$package = 'io.github.regstar2.tgwsproxy'
$receiver = "$package/com.amurcanov.tgwsproxy.WorkerSmokeConfigReceiver"
$activity = "$package/com.amurcanov.tgwsproxy.MainActivity"

. (Join-Path $PSScriptRoot 'worker-issue-101-native-command.ps1')

function Invoke-CheckedAdb {
    param([Parameter(Mandatory = $true)][string[]]$Arguments)
    Invoke-WorkerSmokeNativeCommand -FilePath $adb -Arguments (@('-s', $deviceId) + $Arguments)
}

function Invoke-WorkerSmokeBroadcast {
    param(
        [Parameter(Mandatory = $true)][string]$Action,
        [string]$Domain = '',
        [string]$Transport = '',
        [string]$Dc2Target = ''
    )
    $arguments = @(
        'shell', 'am', 'broadcast', '--include-stopped-packages',
        '-n', $receiver, '-a', "$package.action.$Action"
    )
    if ($Action -eq 'WORKER_SMOKE_CONFIGURE') {
        $arguments += @('--es', 'domain', $Domain, '--es', 'transport', $Transport)
        if (-not [string]::IsNullOrWhiteSpace($Dc2Target)) {
            $arguments += @('--es', 'dc2_target', $Dc2Target)
        }
    }
    $output = @(Invoke-CheckedAdb -Arguments $arguments)
    if (-not ($output | Where-Object { $_ -match 'result=101' })) {
        throw "Debug smoke action $Action rejected: $($output -join '; ')"
    }
}

function Open-ProxyApp {
    Invoke-CheckedAdb -Arguments @('shell','am','start','-n',$activity) | Out-Null
}

function Stop-SmokeApp {
    Invoke-CheckedAdb -Arguments @('shell','am','force-stop',$package) | Out-Null
}

function Capture-SmokeLog {
    param([Parameter(Mandatory = $true)][string]$Destination)
    $lines = @(Invoke-CheckedAdb -Arguments @('logcat','-d','-v','threadtime','-s','TgWsProxy:*'))
    $lines | Set-Content -LiteralPath $Destination -Encoding UTF8
    return $lines
}

function Get-TransportReport {
    param(
        [Parameter(Mandatory = $true)][string]$Transport,
        [Parameter(Mandatory = $true)][string[]]$Lines,
        [Parameter(Mandatory = $true)][string]$LogName,
        [string]$ExpectedWorkerDst = ''
    )
    $closes = @($Lines | Where-Object { $_ -match 'MTProto bridge closed' })
    $workerOnlyAll = @($closes | Where-Object {
        $_ -match 'actual_backend=cf_worker_ws' -and $_ -match 'fallback_used=false'
    })
    $workerOnly = $workerOnlyAll
    if ($ExpectedWorkerDst) {
        $targetRegex = '(?:^|\s)worker_dst=' + [regex]::Escape($ExpectedWorkerDst) + '(?:\s|$)'
        $workerOnly = @($workerOnlyAll | Where-Object { $_ -match $targetRegex })
    }
    $bidirectional = @($workerOnly | Where-Object {
        $_ -match 'up_bytes=[1-9][0-9]*' -and $_ -match 'down_bytes=[1-9][0-9]*'
    })
    $zeroDown = @($workerOnly | Where-Object { $_ -match 'down_bytes=0(?:\D|$)' })
    $routes = @($Lines | Where-Object { $_ -match 'worker_transport=' })
    $matchedTransport = @($routes | Where-Object {
        $_ -match ("worker_transport=" + [regex]::Escape($Transport) + "(\s|$)")
    })
    $errors = @($Lines | Where-Object {
        $_ -match 'worker_protocol_incompatible|worker_websocket_handshake_failed|worker_connect_timeout|connection timed out|broken pipe|all_workers_failed|MTProto Worker TCP stalled|MTProto Worker Flowseal parity WS write failed|close_reason=read_error|error=write'
    })
    $tcpStalls = @($Lines | Where-Object { $_ -match 'MTProto Worker TCP stalled' })
    $writeFailures = @($Lines | Where-Object { $_ -match 'MTProto Worker Flowseal parity WS write failed' })
    # Telegram marks media routes with a negative signed DC. DC2 statistics
    # must include both +2 (ordinary) and -2 (media), without matching DC20.
    $dc2 = @($workerOnly | Where-Object { $_ -match '(?:^|\s)signed_dc=-?2(?:\s|$)' })
    $dc2Media = @($dc2 | Where-Object { $_ -match '(?:^|\s)signed_dc=-2(?:\s|$)' })
    $dc2MediaBidirectional = @($dc2Media | Where-Object {
        $_ -match 'up_bytes=[1-9][0-9]*' -and $_ -match 'down_bytes=[1-9][0-9]*'
    })
    $dc2MediaSilent = @($dc2Media | Where-Object { $_ -match 'down_bytes=0(?:\D|$)' })
    # Attribute the non-MTProto warnings to DC2 sessions from this phase,
    # instead of interpreting their HTTP bytes as valid MTProto downstream.
    $dc2SessionIds = @{}
    foreach ($entry in $dc2) {
        if ($entry -match '(?:^|\s)session_id=([a-zA-Z0-9_-]+)(?:\s|$)') {
            $dc2SessionIds[$Matches[1]] = $true
        }
    }
    $dc2PlaintextSessions = @{}
    $dc2HTTPStatuses = @{}
    foreach ($entry in $Lines) {
        if ($entry -notmatch 'MTProto Worker Legacy WS non-MTProto reply') { continue }
        if ($entry -notmatch '(?:^|\s)session_id=([a-zA-Z0-9_-]+)(?:\s|$)') { continue }
        $session = $Matches[1]
        if (-not $dc2SessionIds.ContainsKey($session)) { continue }
        $dc2PlaintextSessions[$session] = $true
        if ($entry -match '(?:^|\s)http_status=([0-9]{3})(?:\s|$)') {
            $status = $Matches[1]
            if (-not $dc2HTTPStatuses.ContainsKey($status)) {
                $dc2HTTPStatuses[$status] = 0
            }
            $dc2HTTPStatuses[$status]++
        }
    }
    $dc2Silent = @($dc2 | Where-Object { $_ -match 'down_bytes=0(?:\D|$)' })
    $dc2SilentUpBytes = [long]0
    foreach ($entry in $dc2Silent) {
        if ($entry -match 'up_bytes=([0-9]+)') {
            $dc2SilentUpBytes += [long]$Matches[1]
        }
    }
    # A fixed, tiny first response followed by immediate disconnect is not
    # sufficient evidence of an operational MTProto proxy. Record it as a
    # suspected short-reply pattern, without inspecting Telegram payloads.
    $dc2PositiveReplySizes = @()
    $allDc2RepliesClosedQuickly = $true
    foreach ($entry in $dc2) {
        if ($entry -match '(?:^|\s)down_bytes=([0-9]+)(?:\s|$)') {
            $replyBytes = [long]$Matches[1]
            if ($replyBytes -gt 0) {
                $dc2PositiveReplySizes += $replyBytes
                $durationMatch = [regex]::Match(
                    $entry, '(?:^|\s)duration_ms=([0-9]+)(?:\s|$)'
                )
                if (-not $durationMatch.Success -or
                    [long]$durationMatch.Groups[1].Value -gt 2000) {
                    $allDc2RepliesClosedQuickly = $false
                }
            }
        }
    }
    $sameShortReply = $false
    $shortReplyBytes = [long]0
    if ($dc2PositiveReplySizes.Count -ge 3 -and $allDc2RepliesClosedQuickly) {
        $shortReplyBytes = [long]$dc2PositiveReplySizes[0]
        $sameShortReply = $shortReplyBytes -le 512 -and
            @($dc2PositiveReplySizes | Where-Object { $_ -ne $shortReplyBytes }).Count -eq 0
    }

    $status = if ($workerOnly.Count -eq 0) {
        'no_worker_sessions_observed'
    } elseif ($dc2PlaintextSessions.Count -gt 0) {
        'dc2_non_mtproto_reply_detected'
    } elseif ($dc2.Count -ge 3 -and $dc2Silent.Count -ge 3 -and
              (2 * $dc2Silent.Count) -ge $dc2.Count) {
        'dc2_no_downstream_regression'
    } elseif ($bidirectional.Count -eq 0) {
        'no_bidirectional_sessions_observed'
    } elseif ($matchedTransport.Count -eq 0) {
        'two_way_bytes_transport_not_verified'
    } elseif ($sameShortReply) {
        'dc2_uniform_short_replies_unverified'
    } else {
        'two_way_bytes_observed_media_unverified'
    }
    return [ordered]@{
        transport = $Transport
        observedNetworkStatus = $status
        manualTelegramTest = 'not_machine_verified'
        bridgeCloseCount = $closes.Count
        workerOnlyCloseCount = $workerOnly.Count
        workerOnlyTotalCloseCount = $workerOnlyAll.Count
        workerOnlyOtherTargetCloseCount = $workerOnlyAll.Count - $workerOnly.Count
        bidirectionalBridgeCount = $bidirectional.Count
        zeroDownstreamCount = $zeroDown.Count
        transportLogLines = $routes.Count
        selectedTransportLines = $matchedTransport.Count
        errorLogLines = $errors.Count
        dc2CloseCount = $dc2.Count
        dc2MediaCloseCount = $dc2Media.Count
        dc2MediaBidirectionalBridgeCount = $dc2MediaBidirectional.Count
        dc2MediaZeroDownstreamCount = $dc2MediaSilent.Count
        dc2ZeroDownstreamCount = $dc2Silent.Count
        dc2ZeroDownstreamUpBytes = $dc2SilentUpBytes
        dc2PositiveReplyCount = $dc2PositiveReplySizes.Count
        dc2PlaintextReplyCount = $dc2PlaintextSessions.Count
        dc2HTTPStatusCounts = $dc2HTTPStatuses
        dc2UniformShortReplies = [bool]$sameShortReply
        dc2UniformShortReplyBytes = if ($sameShortReply) { $shortReplyBytes } else { 0 }
        tcpStallLogLines = $tcpStalls.Count
        websocketWriteFailureLines = $writeFailures.Count
        log = $LogName
    }
}

function Get-Dc2ABDestinationEvidence {
    param(
        [Parameter(Mandatory = $true)][string[]]$Lines,
        [Parameter(Mandatory = $true)][string]$ExpectedWorkerDst
    )
    $dstPattern = '(?:^|\s)worker_dst=' + [regex]::Escape($ExpectedWorkerDst) + '(?:\s|$)'
    $matched = @($Lines | Where-Object {
        # Count either logical DC2 sign, and reject DC20 / accidental partial matches.
        $_ -match '(?:^|\s)(?:signed_dc=-?2|dc=2|effective_dc=2)(?:\s|$)|\bDC2(?![0-9])' -and
            $_ -match $dstPattern
    })
    return $matched.Count
}

$timestamp = Get-Date -Format 'yyyyMMdd-HHmmss'
$sessionDir = Join-Path $OutputRoot "issue-101-$timestamp"
New-Item -ItemType Directory -Force -Path $sessionDir | Out-Null
$report = [ordered]@{
    issue = 101
    device = $deviceId
    run = $timestamp
    phaseSeconds = $PhaseSeconds
    phases = @()
    note = 'Two-way byte counters do NOT verify Telegram messages/media. Check Telegram UI separately.'
    comparison = if ($LegacyDC2AB) { 'legacy_dc2_dst_ab' } else { 'none' }
}

function Get-WorkerSmokePhases {
    [CmdletBinding()]
    param(
        [string]$LegacyWorkerDomain = '',
        [string]$ChunkRelayWorkerDomain = '',
        [switch]$LegacyOnly,
        [switch]$ChunkRelayOnly,
        [switch]$LegacyDC2AB
    )
    $phases = @()
    if ($ChunkRelayOnly) {
        $phases = @(
            @{ Name = 'chunk_relay'; Label = 'HTTPS Chunk Relay'; Domain = $ChunkRelayWorkerDomain; Transport = 'chunk_relay'; Dc2Target = '' }
        )
    } elseif ($LegacyDC2AB) {
        $phases = @(
            @{ Name = 'legacy_dc2_51'; Label = 'Flowseal Legacy DC2 via 149.154.167.51'; Domain = $LegacyWorkerDomain; Transport = 'legacy_websocket'; Dc2Target = '149.154.167.51' },
            @{ Name = 'legacy_dc2_220'; Label = 'Flowseal Legacy DC2 via 149.154.167.220'; Domain = $LegacyWorkerDomain; Transport = 'legacy_websocket'; Dc2Target = '149.154.167.220' }
        )
    } else {
        $phases = @(
            @{ Name = 'legacy_websocket'; Label = 'Flowseal Legacy WebSocket'; Domain = $LegacyWorkerDomain; Transport = 'legacy_websocket'; Dc2Target = '' }
        )
        if (-not $LegacyOnly) {
            $phases += @{ Name = 'chunk_relay'; Label = 'HTTPS Chunk Relay'; Domain = $ChunkRelayWorkerDomain; Transport = 'chunk_relay'; Dc2Target = '' }
        }
    }
    return $phases
}
$phases = @(Get-WorkerSmokePhases -LegacyWorkerDomain $LegacyWorkerDomain `
    -ChunkRelayWorkerDomain $ChunkRelayWorkerDomain -LegacyOnly:$LegacyOnly `
    -ChunkRelayOnly:$ChunkRelayOnly -LegacyDC2AB:$LegacyDC2AB)

Write-Host "ADB device: $deviceId"
Write-Host "Automatic test: $($phases.Count) transport phases, $PhaseSeconds seconds each."
Write-Host 'No Enter prompts. Telegram will open; send/receive messages and large media during each timed phase.'
Write-Host 'Application settings are restored when the run finishes, including after failures.'
Write-Host 'The script never sends Telegram messages or uploads media itself.'

# Capture failures and partial logcat before cleanup: a result.json with zero
# phases must still explain WHY the acceptance test did not execute.
$activePhase = ''
$activeStage = 'preparation'
$testError = $null
$report['outcome'] = 'incomplete'
$report['restoration'] = 'not_attempted'

try {
    foreach ($phase in $phases) {
        $name = $phase.Name
        $transport = $phase.Transport
        $dc2Target = $phase.Dc2Target
        $activePhase = $name
        $activeStage = 'stop_previous_proxy'
        Write-Host ""
        Write-Host "==> Starting automatic $name phase ($PhaseSeconds seconds)"
        Stop-SmokeApp
        $activeStage = 'configure_transport'
        Invoke-WorkerSmokeBroadcast -Action 'WORKER_SMOKE_CONFIGURE' -Domain $phase.Domain -Transport $transport -Dc2Target $dc2Target
        $activeStage = 'clear_logcat'
        Invoke-CheckedAdb -Arguments @('logcat','-c') | Out-Null
        $activeStage = 'launch_proxy_app'
        Open-ProxyApp
        Start-Sleep -Seconds 3
        $activeStage = 'start_proxy_service'
        Invoke-WorkerSmokeBroadcast -Action 'WORKER_SMOKE_START'

        $activeStage = 'launch_telegram'
        if (-not [string]::IsNullOrWhiteSpace($TelegramPackage)) {
            try {
                # Launching Telegram is best-effort: neither package discovery nor
                # ActivityManager errors should abort proxy data collection.
                $installed = @(Invoke-CheckedAdb -Arguments @('shell','pm','list','packages',$TelegramPackage))
                if ($installed | Where-Object { $_ -eq "package:$TelegramPackage" }) {
                    if ($LegacyDC2AB) {
                        # Force new Telegram MTProto connections for each DC2
                        # destination. Otherwise stale sockets can pollute A/B.
                        Invoke-CheckedAdb -Arguments @('shell','am','force-stop',$TelegramPackage) | Out-Null
                    }
                    $launchOutput = @(Invoke-CheckedAdb -Arguments @(
                        'shell', 'am', 'start',
                        '-a', 'android.intent.action.MAIN',
                        '-c', 'android.intent.category.LAUNCHER',
                        '-p', $TelegramPackage
                    ))
                    if (($launchOutput -join ' ') -match 'unable to resolve|Error:|SecurityException') {
                        throw "Android did not resolve the Telegram launcher: $($launchOutput -join ' ')"
                    }
                    Write-Host "Telegram started: $TelegramPackage"
                    if ($LegacyDC2AB) {
                        # Opening a public channel via Telegram's VIEW intent
                        # encourages a fresh network request in BOTH A/B phases
                        # without modifying Telegram's proxy configuration.
                        try {
                            $deepLink = @(Invoke-CheckedAdb -Arguments @(
                                'shell', 'am', 'start',
                                '-a', 'android.intent.action.VIEW',
                                '-d', 'tg://resolve?domain=telegram',
                                '-p', $TelegramPackage
                            ))
                            if (($deepLink -join ' ') -match 'unable to resolve|Error:|SecurityException') {
                                throw "Android rejected Telegram traffic trigger: $($deepLink -join ' ')"
                            }
                            Write-Host 'Telegram public channel opened as A/B traffic trigger.'
                        }
                        catch {
                            Write-Warning "Telegram traffic trigger unavailable: $_"
                            Write-Warning 'Proxy smoke remains active. Open a Telegram chat manually if needed.'
                        }
                    }
                } else {
                    Write-Warning "Telegram package $TelegramPackage not installed. Run passive proxy test only."
                }
            }
            catch {
                Write-Warning "Telegram could not be opened automatically: $_"
                Write-Warning 'Open Telegram manually; proxy and log capture will continue.'
            }
        }
        $activeStage = 'observe_telegram'
        Write-Host "Use Telegram NOW for $PhaseSeconds seconds: messages, download and upload a large file."
        Start-Sleep -Seconds $PhaseSeconds

        # Bring our Activity foreground before requesting service stop on Android 14+.
        $activeStage = 'stop_proxy_service'
        Open-ProxyApp
        Invoke-WorkerSmokeBroadcast -Action 'WORKER_SMOKE_STOP'
        Start-Sleep -Seconds 3

        $activeStage = 'capture_logcat'
        $logName = "$name.log"
        $logPath = Join-Path $sessionDir $logName
        $lines = @(Capture-SmokeLog -Destination $logPath)
        $phaseReport = Get-TransportReport -Transport $transport -Lines $lines -LogName $logName -ExpectedWorkerDst $dc2Target
        $phaseReport['name'] = $name
        if ($LegacyDC2AB) {
            $phaseReport['dc2ExpectedWorkerDst'] = $dc2Target
            # Verify the actual native selected route, not just a saved UI pref.
            $phaseReport['dc2ExpectedDestinationLogLines'] = Get-Dc2ABDestinationEvidence -Lines $lines -ExpectedWorkerDst $dc2Target
            $phaseReport['dc2ExpectedDestinationObserved'] =
                $phaseReport['dc2ExpectedDestinationLogLines'] -gt 0
            if (-not $phaseReport['dc2ExpectedDestinationObserved']) {
                $phaseReport['observedNetworkStatus'] = 'dc2_target_not_observed'
            }
        }
        $report.phases += $phaseReport
        $activeStage = 'phase_completed'
        $activePhase = ''
        Write-Host ("{0}: {1}; Worker-only closes={2}; bidirectional={3}; down=0={4}" -f
            $name, $phaseReport.observedNetworkStatus, $phaseReport.workerOnlyCloseCount,
            $phaseReport.bidirectionalBridgeCount, $phaseReport.zeroDownstreamCount)
        Write-Host ("DC2: {0}/{1} sessions with zero downstream; upstream bytes without response={2}; TCP stalls={3}" -f
            $phaseReport.dc2ZeroDownstreamCount, $phaseReport.dc2CloseCount,
            $phaseReport.dc2ZeroDownstreamUpBytes, $phaseReport.tcpStallLogLines)
        Write-Host ("DC2 media: {0} sessions, {1} with two-way bytes, {2} with zero downstream (Telegram UI/media acceptance remains unverified)." -f
            $phaseReport.dc2MediaCloseCount, $phaseReport.dc2MediaBidirectionalBridgeCount,
            $phaseReport.dc2MediaZeroDownstreamCount)
        if ($LegacyDC2AB) {
            if ($phaseReport.observedNetworkStatus -eq 'dc2_non_mtproto_reply_detected') {
                Write-Warning ("DC2 {0}: {1} HTTP/HTML upstream responses rejected; NOT MTProto connectivity. HTTP status counts: {2}" -f
                    $dc2Target, $phaseReport.dc2PlaintextReplyCount,
                    (($phaseReport.dc2HTTPStatusCounts.Keys | ForEach-Object { "$_=$($phaseReport.dc2HTTPStatusCounts[$_])" }) -join ', '))
            }
            if ($phaseReport.observedNetworkStatus -eq 'dc2_uniform_short_replies_unverified') {
                Write-Warning ("DC2 {0}: {1} sessions received exactly {2} bytes and closed quickly; NOT proven Telegram connectivity." -f
                    $dc2Target, $phaseReport.dc2PositiveReplyCount, $phaseReport.dc2UniformShortReplyBytes)
            }
            if ($phaseReport.observedNetworkStatus -eq 'dc2_target_not_observed') {
                Write-Warning ("DC2 {0}: no matching MTProto destination seen. This phase is INCONCLUSIVE; do not compare it with the other endpoint." -f $dc2Target)
            }
            Write-Host ("DC2 expected physical dst={0}; observed in native log={1}" -f
                $dc2Target, $phaseReport.dc2ExpectedDestinationObserved)
            if ($phaseReport.workerOnlyOtherTargetCloseCount -gt 0) {
                Write-Warning ("{0} session(s) selected a different physical destination and were excluded from this phase's counters." -f
                    $phaseReport.workerOnlyOtherTargetCloseCount)
            }
        }
    }
    if ($LegacyDC2AB) {
        $validPhases = @($report.phases | Where-Object {
            $_['dc2ExpectedDestinationObserved'] -eq $true -and
            $_['dc2CloseCount'] -gt 0
        })
        $report['dc2ComparisonValid'] = $validPhases.Count -eq 2
        if ($validPhases.Count -ne 2) {
            $report['dc2ComparisonStatus'] = 'inconclusive_missing_dc2_sessions'
            Write-Warning 'A/B comparison INCONCLUSIVE: both DC2 destinations must have real MTProto traffic.'
        } elseif (@($validPhases | Where-Object {
            $_['observedNetworkStatus'] -eq 'dc2_uniform_short_replies_unverified' -or
            $_['observedNetworkStatus'] -eq 'dc2_non_mtproto_reply_detected'
        }).Count -gt 0) {
            $report['dc2ComparisonStatus'] = 'non_mtproto_or_short_replies_not_acceptance'
            Write-Warning 'HTTP/HTML or short, uniform downstream frames are not proof of usable Telegram connectivity.'
        } else {
            $report['dc2ComparisonStatus'] = 'two_phases_observed_media_unverified'
        }
    }
    $report['outcome'] = 'completed_telegram_media_unverified'
}
catch {
    $testError = $_
    $report['outcome'] = 'failed_before_acceptance'
    $report['failure'] = [ordered]@{
        phase = $activePhase
        stage = $activeStage
        errorType = $_.Exception.GetType().FullName
        message = $_.Exception.Message
    }
    $failurePath = Join-Path $sessionDir 'failure.txt'
    @(
        "Phase: $activePhase"
        "Stage: $activeStage"
        "Error: $($_.Exception.Message)"
        "Stack: $($_.ScriptStackTrace)"
    ) | Set-Content -LiteralPath $failurePath -Encoding UTF8
    Write-Warning "Issue #101 smoke failed during phase '$activePhase', stage '$activeStage'."
}
finally {
    # Preserve the failing stage's log BEFORE force-stop and restore, even if
    # another ADB command raised an exception.
    if ($null -ne $testError -and -not [string]::IsNullOrWhiteSpace($activePhase)) {
        $partialName = "$activePhase.partial.log"
        try {
            $partialLogPath = Join-Path $sessionDir $partialName
            $partialLines = @(Invoke-CheckedAdb -Arguments @(
                'logcat','-d','-v','threadtime','-t','1500','-s',
                'TgWsProxy:*','AndroidRuntime:*','ActivityManager:*','ActivityTaskManager:*'
            ))
            $partialLines | Set-Content -LiteralPath $partialLogPath -Encoding UTF8
            $report['failure']['partialLog'] = $partialName
        }
        catch {
            $report['failure']['captureError'] = $_.Exception.Message
        }
    }

    try {
        Stop-SmokeApp
        Invoke-WorkerSmokeBroadcast -Action 'WORKER_SMOKE_RESTORE'
        $report['restoration'] = 'restored'
        Write-Host 'Original Worker and route configuration restored.'
    }
    catch {
        $report['restoration'] = 'failed'
        $report['restorationError'] = $_.Exception.Message
        Write-Warning "Automatic restore failed: $_"
        Write-Warning 'Keep the debug APK installed; the original settings backup is still saved.'
    }

    # Save partial evidence even if a phase fails.
    $reportPath = Join-Path $sessionDir 'result.json'
    $report | ConvertTo-Json -Depth 8 | Set-Content -LiteralPath $reportPath -Encoding UTF8
    $zipPath = "$sessionDir.zip"
    Compress-Archive -Path (Join-Path $sessionDir '*') -DestinationPath $zipPath -Force
    Write-Host "LOGS TO SHARE: $zipPath"
    Write-Host "REPORT: $reportPath"
}
if ($null -ne $testError) {
    throw $testError
}
