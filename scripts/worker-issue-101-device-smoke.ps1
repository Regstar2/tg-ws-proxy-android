[CmdletBinding()]
param(
    [Parameter(Mandatory = $true)]
    [ValidatePattern('^[a-z0-9.-]+[.]workers[.]dev$')]
    [string]$ChunkRelayWorkerDomain,

    [Parameter(Mandatory = $true)]
    [ValidatePattern('^[a-z0-9.-]+[.]workers[.]dev$')]
    [string]$LegacyWorkerDomain,

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
    # install-apk.ps1 may otherwise reuse a previously copied APK.
    # Rebuild the current branch so the debug-only ADB receiver is present.
    & (Join-Path $PSScriptRoot 'build-apk.ps1') -Configuration Debug
    if ($LASTEXITCODE -ne 0) {
        throw 'Debug APK build failed.'
    }
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
$package = 'com.amurcanov.tgwsproxy'
$receiver = "$package/$package.WorkerSmokeConfigReceiver"

function Invoke-WorkerSmokeBroadcast {
    param(
        [Parameter(Mandatory = $true)][string]$Action,
        [string]$Domain = '',
        [string]$Transport = ''
    )

    $arguments = @(
        '-s', $deviceId, 'shell', 'am', 'broadcast', '--include-stopped-packages',
        '-n', $receiver, '-a', "com.amurcanov.tgwsproxy.action.$Action"
    )
    if ($Action -eq 'WORKER_SMOKE_CONFIGURE') {
        $arguments += @('--es', 'domain', $Domain, '--es', 'transport', $Transport)
    }
    $output = @(& $adb @arguments 2>&1)
    if ($LASTEXITCODE -ne 0 -or -not ($output | Where-Object { $_ -match 'result=101' })) {
        throw "Debug Worker fixture command $Action failed: $($output -join '; ')"
    }
}

function Stop-SmokeApp {
    & $adb -s $deviceId shell am force-stop $package
    if ($LASTEXITCODE -ne 0) {
        throw 'Could not stop TgWsProxy before updating test configuration.'
    }
}

$timestamp = Get-Date -Format 'yyyyMMdd-HHmmss'
$sessionDir = Join-Path $OutputRoot "issue-101-$timestamp"
New-Item -ItemType Directory -Force -Path $sessionDir | Out-Null

$report = [ordered]@{
    issue = 101
    device = $deviceId
    run = $timestamp
    phases = @()
    note = 'Handshake and log counters do not prove successful MTProto media transfer. Inspect the Telegram UI.'
}

$phases = @(
    @{ Name = 'legacy_websocket'; Label = 'Flowseal Legacy WebSocket'; Domain = $LegacyWorkerDomain },
    @{ Name = 'chunk_relay'; Label = 'HTTPS Chunk Relay'; Domain = $ChunkRelayWorkerDomain }
)

Write-Host ''
Write-Host 'The debug fixture will temporarily select a single Worker for each phase.'
Write-Host 'Existing Worker Pool, route policies and single-Worker settings will be restored afterward.'
Write-Host 'Worker-only route policies are configured automatically for Wi-Fi and mobile networks.'
Write-Host 'Only the TEST debug APK has the ADB fixture; the release APK does not.'
Read-Host 'When ready, press Enter'

try {
    foreach ($phase in $phases) {
        Stop-SmokeApp
        Invoke-WorkerSmokeBroadcast -Action 'WORKER_SMOKE_CONFIGURE' `
            -Domain $phase.Domain -Transport $phase.Name

        Write-Host ''
        Write-Host "Configured $($phase.Label) at $($phase.Domain) in the debug app."
        & $adb -s $deviceId shell monkey -p $package -c android.intent.category.LAUNCHER 1 | Out-Null
        if ($LASTEXITCODE -ne 0) {
            throw "Could not launch debug app for $($phase.Name)."
        }

        Write-Host 'Start the proxy in the app and verify that Telegram uses its local MTProto proxy.'
        Read-Host 'Once the proxy is running, press Enter to clear logcat and begin'
        & $adb -s $deviceId logcat -c
        if ($LASTEXITCODE -ne 0) {
            throw "Unable to clear logcat for $($phase.Name)."
        }

        Write-Host 'Telegram test: reconnect, send/receive messages, download/upload large media.'
        Write-Host 'Stop the proxy after testing to flush final bridge-close counters.'
        Read-Host 'After completing the Telegram test, press Enter to capture logs'

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

        $observed = (Read-Host 'Telegram result for messages AND media (pass / fail / not-tested)').Trim().ToLowerInvariant()
        if ($observed -notin @('pass', 'fail', 'not-tested')) {
            $observed = 'not-tested'
        }
        $report.phases += [ordered]@{
            transport = $phase.Name
            bridgeCloseCount = $closed.Count
            bidirectionalBridgeCount = $bidirectional.Count
            transportLogLines = $transportLines.Count
            manualTelegramTest = $observed
            log = [System.IO.Path]::GetFileName($logPath)
        }
        Write-Host ("Captured {0}: bridge closes={1}; two-way Worker closes={2}" -f
            $phase.Name, $closed.Count, $bidirectional.Count)
    }
}
finally {
    # Restore the backed-up Worker, selection mode and route policy preferences.
    try {
        Stop-SmokeApp
        Invoke-WorkerSmokeBroadcast -Action 'WORKER_SMOKE_RESTORE'
        Write-Host 'Original Worker configuration restored.'
    }
    catch {
        Write-Warning "Automatic restore failed: $_"
        Write-Warning 'Do not uninstall the debug APK: the original configuration backup is still present.'
        Write-Warning 'Retry the debug receiver restore action before using your normal configuration.'
    }

    # Always preserve the captured evidence, including when the second phase fails.
    $reportPath = Join-Path $sessionDir 'result.json'
    $report | ConvertTo-Json -Depth 6 | Set-Content -LiteralPath $reportPath -Encoding UTF8
    $zipPath = "$sessionDir.zip"
    Compress-Archive -Path (Join-Path $sessionDir '*') -DestinationPath $zipPath -Force

    Write-Host ''
    Write-Host 'Do not publish raw diagnostic logs containing private Worker domains.'
    Write-Host "LOGS TO SHARE: $zipPath"
    Write-Host "REPORT: $reportPath"
}
