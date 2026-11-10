[CmdletBinding()]
param(
    [ValidateRange(5, 180)]
    [int]$DurationSeconds = 35,

    [ValidateRange(1, 65535)]
    [int]$Port = 1443,

    [string]$Serial = ''
)

Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'
$root = Split-Path -Parent $PSScriptRoot
Set-Location -LiteralPath $root

if (-not (Get-Command adb -ErrorAction SilentlyContinue)) {
    throw 'adb is required.'
}
$available = @(
    & adb devices | ForEach-Object {
        if ($_ -match '^(\S+)\s+device\s*$') { $Matches[1] }
    }
)
if ($available.Count -ne 1) {
    throw "Exactly one authorized Android device is required; found $($available.Count)."
}
if ($Serial -ne '' -and $Serial -ne $available[0]) {
    throw 'The connected device serial does not match -Serial.'
}
$Serial = $available[0]

$timestamp = Get-Date -Format 'yyyyMMdd-HHmmss'
$artifactRoot = Join-Path $root 'artifacts\issue138'
$runDir = Join-Path $artifactRoot "ingress-$timestamp"
New-Item -Path $runDir -ItemType Directory -Force | Out-Null

function Write-AdbSnapshot {
    param([string]$Label, [string[]]$AdbArgs)
    $file = Join-Path $runDir "$Label.txt"
    try {
        $result = & adb -s $Serial @AdbArgs 2>&1 | Out-String
        $result | Set-Content -LiteralPath $file -Encoding UTF8
    }
    catch {
        "Unavailable: $($_.Exception.Message)" | Set-Content -LiteralPath $file -Encoding UTF8
    }
}

$start = Get-Date
Write-AdbSnapshot -Label 'device' -AdbArgs @('shell', 'getprop', 'ro.build.fingerprint')
Write-AdbSnapshot -Label 'packages' -AdbArgs @('shell', 'pm', 'list', 'packages', '-U')
Write-AdbSnapshot -Label 'processes-before' -AdbArgs @('shell', 'ps', '-A')
Write-AdbSnapshot -Label 'listener-pid-before' -AdbArgs @('shell', 'pidof', 'io.github.regstar2.tgwsproxy')
Write-AdbSnapshot -Label 'legacy-pid-before' -AdbArgs @('shell', 'pidof', 'com.amurcanov.tgwsproxy')

$socketFile = Join-Path $runDir 'socket-samples.txt'
$endTime = $start.AddSeconds($DurationSeconds)
$sampleIndex = 0
while ((Get-Date) -lt $endTime) {
    $sampleIndex++
    "sample=$sampleIndex time=$((Get-Date).ToString('o')) port=$Port" |
        Add-Content -LiteralPath $socketFile -Encoding UTF8
    try {
        # ss may omit owners on unrooted Android; never infer a UID/PID from the port alone.
        $socketOutput = & adb -s $Serial shell ss -tnpe 2>&1 | Out-String
        $portRows = @($socketOutput -split "\r?\n" | Where-Object {
            $_ -match "(^|[:\s])$Port(\s|$)" -or $_ -match '^(State|Netid)'
        })
        if ($portRows.Count -gt 0) {
            $portRows | Add-Content -LiteralPath $socketFile -Encoding UTF8
        } else {
            'No matching sockets or ss is not supported.' |
                Add-Content -LiteralPath $socketFile -Encoding UTF8
            if ($socketOutput -match '(permission denied|not found|invalid option)') {
                $socketOutput | Add-Content -LiteralPath $socketFile -Encoding UTF8
            }
        }
    }
    catch {
        "ss unavailable: $($_.Exception.Message)" |
            Add-Content -LiteralPath $socketFile -Encoding UTF8
    }
    Start-Sleep -Milliseconds 500
}

Write-AdbSnapshot -Label 'processes-after' -AdbArgs @('shell', 'ps', '-A')
Write-AdbSnapshot -Label 'listener-pid-after' -AdbArgs @('shell', 'pidof', 'io.github.regstar2.tgwsproxy')
Write-AdbSnapshot -Label 'legacy-pid-after' -AdbArgs @('shell', 'pidof', 'com.amurcanov.tgwsproxy')
Write-AdbSnapshot -Label 'logcat' -AdbArgs @('logcat', '-d', '-t', '10000', '-v', 'threadtime', '-s', 'TgWsProxy:I', '*:S')
@(
    "started=$($start.ToString('o'))",
    "ended=$((Get-Date).ToString('o'))",
    "port=$Port",
    "serial=$Serial",
    'This capture is read-only: no logcat clear, package uninstall, or data reset.',
    'Unrooted Android can conceal socket owner PID/UID; missing owner is not evidence of Telegram.',
    'Do not publish proxy link, secret, WARP configuration, or raw packet payloads.'
) | Set-Content -LiteralPath (Join-Path $runDir 'README.txt') -Encoding UTF8

$zip = Join-Path $artifactRoot "ingress-$timestamp.zip"
Compress-Archive -Path (Join-Path $runDir '*') -DestinationPath $zip -Force
Write-Host "Evidence ZIP: $zip"
Write-Host "Review process PIDs and socket samples alongside the logcat timestamps."
