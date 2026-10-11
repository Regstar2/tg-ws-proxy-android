[CmdletBinding()]
param(
    [Parameter(Mandatory = $true)]
    [ValidatePattern('^[a-z0-9.-]+[.]workers[.]dev$')]
    [string]$ChunkRelayWorkerDomain,

    [Parameter(Mandatory = $true)]
    [ValidatePattern('^[a-z0-9.-]+[.]workers[.]dev$')]
    [string]$LegacyWorkerDomain,

    [ValidateRange(15, 900)]
    [int]$PhaseSeconds = 90,

    [string]$TelegramPackage = 'org.telegram.messenger',
    [switch]$LegacyOnly,
    [switch]$InstallSignedDebug,
    [string]$OutputRoot = ''
)

Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'

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
$package = 'com.amurcanov.tgwsproxy'
$receiver = "$package/$package.WorkerSmokeConfigReceiver"
$activity = "$package/$package.MainActivity"

function Invoke-CheckedAdb {
    param([Parameter(Mandatory = $true)][string[]]$Arguments)
    $output = @(& $adb -s $deviceId @Arguments 2>&1)
    if ($LASTEXITCODE -ne 0) {
        throw "ADB command failed: $($Arguments -join ' ') | $($output -join ' ')"
    }
    return $output
}

function Invoke-WorkerSmokeBroadcast {
    param(
        [Parameter(Mandatory = $true)][string]$Action,
        [string]$Domain = '',
        [string]$Transport = ''
    )
    $arguments = @(
        'shell', 'am', 'broadcast', '--include-stopped-packages',
        '-n', $receiver, '-a', "com.amurcanov.tgwsproxy.action.$Action"
    )
    if ($Action -eq 'WORKER_SMOKE_CONFIGURE') {
        $arguments += @('--es', 'domain', $Domain, '--es', 'transport', $Transport)
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
        [Parameter(Mandatory = $true)][string]$LogName
    )
    $closes = @($Lines | Where-Object { $_ -match 'MTProto bridge closed' })
    $workerOnly = @($closes | Where-Object {
        $_ -match 'actual_backend=cf_worker_ws' -and $_ -match 'fallback_used=false'
    })
    $bidirectional = @($workerOnly | Where-Object {
        $_ -match 'up_bytes=[1-9][0-9]*' -and $_ -match 'down_bytes=[1-9][0-9]*'
    })
    $zeroDown = @($workerOnly | Where-Object { $_ -match 'down_bytes=0(?:\D|$)' })
    $routes = @($Lines | Where-Object { $_ -match 'worker_transport=' })
    $matchedTransport = @($routes | Where-Object {
        $_ -match ("worker_transport=" + [regex]::Escape($Transport) + "(\s|$)")
    })
    $errors = @($Lines | Where-Object {
        $_ -match 'worker_protocol_incompatible|worker_websocket_handshake_failed|worker_connect_timeout|connection timed out|broken pipe|all_workers_failed'
    })
    $status = if ($workerOnly.Count -eq 0) {
        'no_worker_sessions_observed'
    } elseif ($bidirectional.Count -eq 0) {
        'no_bidirectional_sessions_observed'
    } elseif ($matchedTransport.Count -eq 0) {
        'two_way_bytes_transport_not_verified'
    } else {
        'two_way_bytes_observed_media_unverified'
    }
    return [ordered]@{
        transport = $Transport
        observedNetworkStatus = $status
        manualTelegramTest = 'not_machine_verified'
        bridgeCloseCount = $closes.Count
        workerOnlyCloseCount = $workerOnly.Count
        bidirectionalBridgeCount = $bidirectional.Count
        zeroDownstreamCount = $zeroDown.Count
        transportLogLines = $routes.Count
        selectedTransportLines = $matchedTransport.Count
        errorLogLines = $errors.Count
        log = $LogName
    }
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
}

$phases = @(
    @{ Name = 'legacy_websocket'; Label = 'Flowseal Legacy WebSocket'; Domain = $LegacyWorkerDomain }
)
if (-not $LegacyOnly) {
    $phases += @{ Name = 'chunk_relay'; Label = 'HTTPS Chunk Relay'; Domain = $ChunkRelayWorkerDomain }
}

Write-Host "ADB device: $deviceId"
Write-Host "Automatic test: $($phases.Count) transport phases, $PhaseSeconds seconds each."
Write-Host 'No Enter prompts. Telegram will open; send/receive messages and large media during each timed phase.'
Write-Host 'Application settings are restored when the run finishes, including after failures.'
Write-Host 'The script never sends Telegram messages or uploads media itself.'

try {
    foreach ($phase in $phases) {
        $name = $phase.Name
        Write-Host ""
        Write-Host "==> Starting automatic $name phase ($PhaseSeconds seconds)"
        Stop-SmokeApp
        Invoke-WorkerSmokeBroadcast -Action 'WORKER_SMOKE_CONFIGURE' -Domain $phase.Domain -Transport $name
        Invoke-CheckedAdb -Arguments @('logcat','-c') | Out-Null
        Open-ProxyApp
        Start-Sleep -Seconds 3
        Invoke-WorkerSmokeBroadcast -Action 'WORKER_SMOKE_START'

        if (-not [string]::IsNullOrWhiteSpace($TelegramPackage)) {
            $installed = @(Invoke-CheckedAdb -Arguments @('shell','pm','list','packages',$TelegramPackage))
            if ($installed | Where-Object { $_ -eq "package:$TelegramPackage" }) {
                $null = & $adb -s $deviceId shell monkey -p $TelegramPackage -c android.intent.category.LAUNCHER 1 2>&1
            } else {
                Write-Warning "Telegram package $TelegramPackage not installed. Run passive proxy test only."
            }
        }
        Write-Host "Use Telegram NOW for $PhaseSeconds seconds: messages, download and upload a large file."
        Start-Sleep -Seconds $PhaseSeconds

        # Bring our Activity foreground before requesting service stop on Android 14+.
        Open-ProxyApp
        Invoke-WorkerSmokeBroadcast -Action 'WORKER_SMOKE_STOP'
        Start-Sleep -Seconds 3

        $logName = "$name.log"
        $logPath = Join-Path $sessionDir $logName
        $lines = @(Capture-SmokeLog -Destination $logPath)
        $phaseReport = Get-TransportReport -Transport $name -Lines $lines -LogName $logName
        $report.phases += $phaseReport
        Write-Host ("{0}: {1}; Worker-only closes={2}; bidirectional={3}; down=0={4}" -f
            $name, $phaseReport.observedNetworkStatus, $phaseReport.workerOnlyCloseCount,
            $phaseReport.bidirectionalBridgeCount, $phaseReport.zeroDownstreamCount)
    }
}
finally {
    try {
        Stop-SmokeApp
        Invoke-WorkerSmokeBroadcast -Action 'WORKER_SMOKE_RESTORE'
        Write-Host 'Original Worker and route configuration restored.'
    }
    catch {
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
