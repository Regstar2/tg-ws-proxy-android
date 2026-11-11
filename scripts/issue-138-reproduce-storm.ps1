[CmdletBinding()]
param(
    [ValidateSet('Baseline', 'Inject', 'Both')]
    [string]$Mode = 'Both',

    [ValidateRange(5, 60)]
    [int]$DurationSeconds = 35,

    [ValidateRange(1, 400)]
    [int]$RatePerSecond = 40,

    [ValidateRange(1, 65535)]
    [int]$Port = 1443,

    [string]$Serial = ''
)

Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'
$root = Split-Path -Parent $PSScriptRoot
Set-Location -LiteralPath $root
. (Join-Path $PSScriptRoot 'issue-138-storm-report.ps1')

$adbCommand = Get-Command adb -ErrorAction Stop
$adbPath = $adbCommand.Source
$devices = @(
    & $adbPath devices | ForEach-Object {
        if ($_ -match '^(\S+)\s+device\s*$') { $Matches[1] }
    }
)
if ($LASTEXITCODE -ne 0 -or $devices.Count -ne 1) {
    throw "Exactly one authorized Android device is required; found $($devices.Count)."
}
if ($Serial -ne '' -and $Serial -ne $devices[0]) {
    throw 'Connected ADB device differs from -Serial.'
}
$Serial = $devices[0]
$package = 'io.github.regstar2.tgwsproxy'

$installed = @(& $adbPath -s $Serial shell pm path $package 2>$null)
if ($LASTEXITCODE -ne 0 -or @($installed | Where-Object { $_ -match '^package:' }).Count -eq 0) {
    throw 'TgWsProxy application must already be installed. No installation was attempted.'
}

function Get-ProxyPid {
    $pidOutput = @(& $adbPath -s $Serial shell pidof $package 2>$null)
    $ids = @(($pidOutput -join ' ') -split '\s+' |
        Where-Object { $_ -match '^\d+$' } | Select-Object -Unique)
    if ($ids.Count -ne 1) {
        throw "Expected one running TgWsProxy process; got $($ids -join ','). Start the proxy first."
    }
    return $ids[0]
}

function Save-AdbEvidence {
    param(
        [string]$Name,
        [string[]]$CommandArgs
    )
    $target = Join-Path $runDir "$Name.txt"
    try {
        (& $adbPath -s $Serial @CommandArgs 2>&1 | Out-String) |
            Set-Content -LiteralPath $target -Encoding UTF8
    }
    catch {
        "Unavailable: $($_.Exception.Message)" | Set-Content -LiteralPath $target -Encoding UTF8
    }
}

function Invoke-InvalidFakeTlsConnection {
    param([int]$HostPort)
    # No secret is needed: the 64-byte payload is deliberately unauthenticated.
    $payload = [byte[]](0..63 | ForEach-Object { [byte]0x7f })
    $client = New-Object System.Net.Sockets.TcpClient
    try {
        $client.ReceiveTimeout = 1500
        $client.SendTimeout = 1500
        $client.Connect('127.0.0.1', $HostPort)
        $stream = $client.GetStream()
        $stream.Write($payload, 0, $payload.Length)
        $buffer = New-Object byte[] 256
        $read = $stream.Read($buffer, 0, $buffer.Length)
        if ($read -le 0) { return $false }
        $response = [System.Text.Encoding]::ASCII.GetString($buffer, 0, $read)
        return $response.StartsWith('HTTP/1.1 301')
    }
    finally {
        $client.Close()
    }
}

$stamp = Get-Date -Format 'yyyyMMdd-HHmmss'
$artifactRoot = Join-Path $root 'artifacts\issue138'
$runDir = Join-Path $artifactRoot "repro-$stamp"
New-Item -Path $runDir -ItemType Directory -Force | Out-Null
$zip = Join-Path $artifactRoot "repro-$stamp.zip"
$forwardPort = 0
$allResults = @()
$failure = $null

try {
    Save-AdbEvidence -Name 'device' -CommandArgs @('shell', 'getprop', 'ro.build.fingerprint')
    Save-AdbEvidence -Name 'packages' -CommandArgs @('shell', 'pm', 'list', 'packages', '-U')
    Save-AdbEvidence -Name 'processes-before' -CommandArgs @('shell', 'ps', '-A')
    Save-AdbEvidence -Name 'legacy-app-pid' -CommandArgs @('shell', 'pidof', 'com.amurcanov.tgwsproxy')
    $initialPid = Get-ProxyPid

    if ($Mode -ne 'Baseline') {
        # tcp:0 allocates a free host port; only that mapping is removed in finally.
        $output = (& $adbPath -s $Serial forward 'tcp:0' "tcp:$Port" 2>&1 | Out-String).Trim()
        if ($LASTEXITCODE -ne 0 -or $output -notmatch '^\d+$') {
            throw "adb forward could not allocate a temporary port: $output"
        }
        $forwardPort = [int]$output
    }

    $phases = if ($Mode -eq 'Both') { @('Baseline', 'Inject') } else { @($Mode) }
    foreach ($phase in $phases) {
        $processId = Get-ProxyPid
        $stdoutFile = Join-Path $runDir "$phase-logcat.txt"
        $stderrFile = Join-Path $runDir "$phase-logcat-stderr.txt"
        Save-AdbEvidence -Name "$phase-sockets-before" -CommandArgs @('shell', 'ss', '-tnpe')
        Save-AdbEvidence -Name "$phase-cpu-before" -CommandArgs @('shell', 'top', '-b', '-n', '1', '-p', $processId)
        Write-Host "Phase=$phase listener_pid=$processId duration=$DurationSeconds seconds"

        $attempted = 0
        $redirects = 0
        $failures = 0
        $failureSamples = New-Object 'System.Collections.Generic.List[string]'
        $capture = Start-Process -FilePath $adbPath -ArgumentList @(
            '-s', $Serial, 'logcat', "--pid=$processId", '-v', 'threadtime',
            'TgWsProxy:V', 'AndroidRuntime:E', '*:S'
        ) -RedirectStandardOutput $stdoutFile -RedirectStandardError $stderrFile -PassThru -NoNewWindow
        try {
            Start-Sleep -Milliseconds 500
            if ($capture.HasExited) {
                throw "logcat exited before the $phase phase started."
            }
            $clock = [System.Diagnostics.Stopwatch]::StartNew()
            if ($phase -eq 'Baseline') {
                Start-Sleep -Seconds $DurationSeconds
            } else {
                $intervalMs = 1000.0 / $RatePerSecond
                $consecutiveFailures = 0
                while ($clock.Elapsed.TotalSeconds -lt $DurationSeconds) {
                    $attempted++
                    try {
                        if (Invoke-InvalidFakeTlsConnection -HostPort $forwardPort) {
                            $redirects++
                            $consecutiveFailures = 0
                        } else {
                            $failures++
                            $consecutiveFailures++
                            if ($failureSamples.Count -lt 20) {
                                $failureSamples.Add("Attempt $attempted : response was not HTTP 301")
                            }
                        }
                    }
                    catch {
                        $failures++
                        $consecutiveFailures++
                        if ($failureSamples.Count -lt 20) {
                            $failureSamples.Add("Attempt $attempted : $($_.Exception.GetType().Name)")
                        }
                    }
                    if ($consecutiveFailures -ge 10) {
                        throw 'Ten consecutive bad responses: listener/Fake TLS setup may be invalid.'
                    }
                    $sleepMs = $attempted * $intervalMs - $clock.Elapsed.TotalMilliseconds
                    if ($sleepMs -ge 1) {
                        Start-Sleep -Milliseconds ([int][Math]::Floor($sleepMs))
                    }
                }
            }
            $clock.Stop()
        }
        finally {
            if (-not $capture.HasExited) {
                Stop-Process -Id $capture.Id -ErrorAction SilentlyContinue
                [void]$capture.WaitForExit(5000)
            }
        }

        $failureSamples | Set-Content -LiteralPath (Join-Path $runDir "$phase-failures.txt") -Encoding UTF8
        Save-AdbEvidence -Name "$phase-sockets-after" -CommandArgs @('shell', 'ss', '-tnpe')
        Save-AdbEvidence -Name "$phase-cpu-after" -CommandArgs @('shell', 'top', '-b', '-n', '1', '-p', $processId)
        $afterPid = Get-ProxyPid
        $logLines = if (Test-Path -LiteralPath $stdoutFile) {
            @(Get-Content -LiteralPath $stdoutFile)
        } else {
            @()
        }
        $report = Get-Issue138StormReport -Phase $phase -Lines $logLines -DurationSeconds $DurationSeconds -AttemptedConnections $attempted -RedirectResponses $redirects -ConnectionFailures $failures
        $report | Add-Member -NotePropertyName 'ListenerPidBefore' -NotePropertyValue $processId
        $report | Add-Member -NotePropertyName 'ListenerPidAfter' -NotePropertyValue $afterPid
        $report | Add-Member -NotePropertyName 'ProcessStable' -NotePropertyValue ($processId -eq $afterPid)
        $report | Add-Member -NotePropertyName 'InjectionRatePerSecond' -NotePropertyValue (
            [Math]::Round($attempted / [Math]::Max(1, $clock.Elapsed.TotalSeconds), 2)
        )
        $allResults += $report
        $report | ConvertTo-Json -Depth 4 |
            Set-Content -LiteralPath (Join-Path $runDir "$phase-result.json") -Encoding UTF8
        $report | Format-List | Out-String | Write-Host
        if ($processId -ne $afterPid -or $report.CrashOrAnrMarkers -gt 0) {
            throw "Listener PID changed or an app crash marker was captured during $phase."
        }
        if ($phase -eq 'Inject' -and ($attempted -eq 0 -or $redirects -lt [Math]::Ceiling($attempted * 0.9))) {
            throw 'Fewer than 90 percent of invalid connections received the Fake TLS redirect.'
        }
    }

    Save-AdbEvidence -Name 'processes-after' -CommandArgs @('shell', 'ps', '-A')
}
catch {
    $failure = $_.Exception.Message
}
finally {
    if ($forwardPort -gt 0) {
        & $adbPath -s $Serial forward --remove "tcp:$forwardPort" 2>$null | Out-Null
    }
    @(
        "mode=$Mode"
        "serial=$Serial"
        "device_port=$Port"
        "duration_seconds_per_phase=$DurationSeconds"
        "target_injection_rate_per_second=$RatePerSecond"
        "failure=$failure"
        'Baseline captures external traffic; Inject connections originate from this ADB harness.'
        'ADB forwarding makes the peer appear as Android loopback; this does not identify Telegram.'
        'The script never reads a proxy secret, edits app settings, uninstalls packages or clears data.'
        'Unrooted Android may not show socket ownership. Review evidence for private details.'
    ) | Set-Content -LiteralPath (Join-Path $runDir 'README.txt') -Encoding UTF8
    $allResults | ConvertTo-Json -Depth 5 |
        Set-Content -LiteralPath (Join-Path $runDir 'results.json') -Encoding UTF8
    Compress-Archive -Path (Join-Path $runDir '*') -DestinationPath $zip -Force
    Write-Host "Evidence ZIP: $zip"
}
if ($failure) { throw "Issue #138 reproduction failed: $failure. Evidence: $zip" }
Write-Host 'Capture completed. Successful injection does NOT identify the original reconnecting app.'
