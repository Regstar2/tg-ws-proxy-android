[CmdletBinding()]
param(
    [switch]$InstallDebug,
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

if ($InstallDebug) {
    & (Join-Path $PSScriptRoot 'install-apk.ps1') -Configuration Debug
    if ($LASTEXITCODE -ne 0) {
        throw 'Debug APK installation failed.'
    }
}

$connected = @(
    & $adb devices |
        Where-Object { $_ -match '^\S+\s+device\s*$' } |
        ForEach-Object { ($_ -split '\s+')[0] }
)
if ($LASTEXITCODE -ne 0) {
    throw 'adb devices failed.'
}
if ($connected.Count -ne 1) {
    throw "Expected one authorized Android device, found $($connected.Count)."
}
$deviceId = $connected[0]
$timestamp = Get-Date -Format 'yyyyMMdd-HHmmss'
$sessionDir = Join-Path $OutputRoot "issue-101-$timestamp"
New-Item -ItemType Directory -Force -Path $sessionDir | Out-Null

$report = [ordered]@{
    issue = 101
    device = $deviceId
    run = $timestamp
    phases = @()
    note = 'Transport log checks are not proof of actual Telegram media transfer. Confirm send/receive and media in the Telegram UI.'
}

foreach ($phase in @(
    @{ Name = 'legacy_websocket'; Label = 'Flowseal Legacy WebSocket' },
    @{ Name = 'chunk_relay'; Label = 'HTTPS Chunk Relay' }
)) {
    Write-Host ''
    Write-Host "Configure Worker-only mode with $($phase.Label) and a matching deployed Worker."
    Read-Host 'After configuring and starting the proxy, press Enter to clear logcat and begin'
    & $adb -s $deviceId logcat -c
    if ($LASTEXITCODE -ne 0) {
        throw "Unable to clear logcat for $($phase.Name)."
    }

    Write-Host 'In Telegram: connect, send and receive messages, download and upload a large media file.'
    Write-Host 'Disconnect Telegram from the proxy or stop the proxy to flush bridge-close counters.'
    Read-Host 'After finishing this phase, press Enter to capture logs'

    $logPath = Join-Path $sessionDir "$($phase.Name).log"
    $logLines = @(& $adb -s $deviceId logcat -d -v threadtime -s 'TgWsProxy:*')
    if ($LASTEXITCODE -ne 0) {
        throw "Unable to read logcat for $($phase.Name)."
    }
    $logLines | Set-Content -LiteralPath $logPath -Encoding UTF8

    $closed = @($logLines | Where-Object { $_ -match 'MTProto bridge closed' })
    $bidirectional = @($closed | Where-Object {
        ($_ -match 'actual_backend=cf_worker_ws') -and
        ($_ -match 'fallback_used=false') -and
        ($_ -match 'up_bytes=[1-9][0-9]*') -and
        ($_ -match 'down_bytes=[1-9][0-9]*')
    })
    $transportLines = @($logLines | Where-Object {
        $_ -match 'worker_transport=|Worker transport=|transport=chunk_relay_http|Flowseal parity transport connected'
    })

    $phaseResult = [ordered]@{
        transport = $phase.Name
        bridgeCloseCount = $closed.Count
        bidirectionalBridgeCount = $bidirectional.Count
        transportLogLines = $transportLines.Count
        manualTelegramTest = 'not_machine_verified'
        log = [System.IO.Path]::GetFileName($logPath)
    }
    $report.phases += $phaseResult
    Write-Host ("Captured {0}: bridge closes={1}; two-way Worker closes={2}" -f
        $phase.Name, $closed.Count, $bidirectional.Count)
}

$reportPath = Join-Path $sessionDir 'result.json'
$report | ConvertTo-Json -Depth 6 | Set-Content -LiteralPath $reportPath -Encoding UTF8
$zipPath = "$sessionDir.zip"
Compress-Archive -Path (Join-Path $sessionDir '*') -DestinationPath $zipPath -Force

Write-Host ''
Write-Host 'Manually confirm Telegram connection, message and media results for both protocols.'
Write-Host "LOGS TO SHARE: $zipPath"
Write-Host "REPORT: $reportPath"
