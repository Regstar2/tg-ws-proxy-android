[CmdletBinding()]
param(
    [Parameter(Mandatory = $true)]
    [ValidatePattern('^[a-z0-9.-]+[.]workers[.]dev$')]
    [string]$WorkerDomain,

    [ValidateRange(1, 5)]
    [int]$Rounds = 2,

    [ValidateRange(2, 60)]
    [int]$TimeoutSeconds = 15,

    [string]$IPv6Address = ''
)

Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'
$root = Split-Path -Parent $PSScriptRoot
$source = Join-Path $PSScriptRoot 'flowseal-ip-probe\main.go'
$unitTest = Join-Path $PSScriptRoot 'flowseal-ip-probe\main_test.go'
$helper = Join-Path $PSScriptRoot 'worker-issue-101-native-command.ps1'
. $helper

$go = (Get-Command go -ErrorAction Stop).Source
$adbPath = Get-Command adb -ErrorAction SilentlyContinue
$adb = if ($adbPath) { $adbPath.Source } else { 'C:\Android\Sdk\platform-tools\adb.exe' }
if (-not (Test-Path -LiteralPath $adb)) { throw "ADB missing: $adb" }

$devices = @(& $adb devices | ForEach-Object {
    if ($_ -match '^(\S+)\s+device\s*$') { $Matches[1] }
})
if ($devices.Count -ne 1) { throw "Expected one authorized Android device, found $($devices.Count)" }
$device = $devices[0]

# DNS is intentionally resolved by Windows, then each numeric address is
# contacted FROM THE PHONE. This avoids Android's CGO-less DNS limitations.
$addresses = [System.Net.Dns]::GetHostAddresses($WorkerDomain)
$ipv4 = @($addresses | Where-Object {
    $_.AddressFamily -eq [System.Net.Sockets.AddressFamily]::InterNetwork
} | ForEach-Object { $_.ToString() } | Select-Object -Unique)
$ipv6 = @($addresses | Where-Object {
    $_.AddressFamily -eq [System.Net.Sockets.AddressFamily]::InterNetworkV6
} | ForEach-Object { $_.ToString() } | Select-Object -Unique)

if (-not [string]::IsNullOrWhiteSpace($IPv6Address)) {
    $parsed = $null
    if (-not [System.Net.IPAddress]::TryParse($IPv6Address, [ref]$parsed) -or
        $parsed.AddressFamily -ne [System.Net.Sockets.AddressFamily]::InterNetworkV6) {
        throw "-IPv6Address must be a numeric IPv6 address: $IPv6Address"
    }
    $ipv6 = @($parsed.ToString())
}
if ($ipv4.Count -eq 0 -and $ipv6.Count -eq 0) { throw 'No A or AAAA records found for Worker domain' }

function Get-WorkerProbeSummary {
    param([Parameter(Mandatory = $true)][object]$Result)
    $phaseProperty = $Result.PSObject.Properties['phase']
    $responseProperty = $Result.PSObject.Properties['response_bytes']
    $errorProperty = $Result.PSObject.Properties['error']
    $phase = if ($null -ne $phaseProperty -and $null -ne $phaseProperty.Value) {
        [string]$phaseProperty.Value
    } else { 'unknown' }
    $responseBytes = [long]0
    if ($null -ne $responseProperty -and $null -ne $responseProperty.Value -and
        "$($responseProperty.Value)" -match '^[0-9]+

$out = Join-Path $root 'artifacts\worker-issue-101'
New-Item -ItemType Directory -Path $out -Force | Out-Null
$stamp = Get-Date -Format 'yyyyMMdd-HHmmss'
$exe = Join-Path $out "flowseal-ip-probe-$stamp"
$reportPath = Join-Path $out "flowseal-ip-ab-$stamp.json"
$remote = "/data/local/tmp/tgwsproxy-flowseal-ip-probe-101"

Write-Host "Testing on Android device: $device"
Write-Host 'This test does NOT change TgWsProxy or Telegram settings.'
Write-Host 'It tests WebSocket TCP forwarding to www.google.com, not MTProto to Telegram DC2.'
Write-Host "Resolved IPv4: $($ipv4 -join ', ')"
Write-Host "IPv6 target: $($ipv6 -join ', ')"
if ($IPv6Address) {
    Write-Host 'IPv6 address supplied from earlier Android log, not current Windows DNS.'
}

$previousGOOS = $env:GOOS
$previousGOARCH = $env:GOARCH
$previousCGO = $env:CGO_ENABLED
$results = @()
$uploaded = $false
try {
    Push-Location $root
    try {
        & $go test $source $unitTest
        if ($LASTEXITCODE -ne 0) { throw 'Flowseal IP probe unit tests failed' }

        $env:GOOS = 'android'
        $env:GOARCH = 'arm64'
        $env:CGO_ENABLED = '0'
        & $go build -trimpath -o $exe $source
        if ($LASTEXITCODE -ne 0) { throw 'Android arm64 IP probe compilation failed' }
    }
    finally {
        Pop-Location
        $env:GOOS = $previousGOOS
        $env:GOARCH = $previousGOARCH
        $env:CGO_ENABLED = $previousCGO
    }

    Invoke-WorkerSmokeNativeCommand -FilePath $adb -Arguments @(
        '-s', $device, 'push', $exe, $remote
    ) | Out-Null
    $uploaded = $true

    Invoke-WorkerSmokeNativeCommand -FilePath $adb -Arguments @(
        '-s', $device, 'shell', 'chmod', '700', $remote
    ) | Out-Null

    $families = @(
        @{ Name = 'tcp4'; Addresses = $ipv4 },
        @{ Name = 'tcp6'; Addresses = $ipv6 }
    )
    for ($round = 1; $round -le $Rounds; $round++) {
        foreach ($family in $families) {
            if ($family.Addresses.Count -eq 0) {
                Write-Warning "No $($family.Name) address available: skipping"
                $results += [pscustomobject]@{
                    network = $family.Name
                    phase = 'skipped_no_address'
                    error = 'No DNS address or explicit override'
                    response_bytes = 0
                }
                continue
            }

            $ip = $family.Addresses[($round - 1) % $family.Addresses.Count]
            Write-Host "Round $round/$Rounds | $($family.Name) | $ip ..."
            try {
                $lines = @(Invoke-WorkerSmokeNativeCommand -FilePath $adb -Arguments @(
                    '-s', $device, 'shell', $remote,
                    '-network', $family.Name,
                    '-worker', $WorkerDomain,
                    '-ip', $ip,
                    '-timeout', "$($TimeoutSeconds)s"
                ))
                $jsonLines = @($lines | Where-Object { $_.TrimStart().StartsWith('{') })
                if ($jsonLines.Count -eq 0) {
                    throw "No JSON from Android probe: $($lines -join ' ')"
                }
                $result = $jsonLines[-1] | ConvertFrom-Json
            }
            catch {
                $result = [pscustomobject]@{
                    network = $family.Name
                    dial_address = $ip
                    phase = 'adb_execution'
                    error = $_.Exception.Message
                    response_bytes = 0
                }
            }
            $results += $result
            $summary = Get-WorkerProbeSummary -Result $result
            Write-Host "$($family.Name): $($summary.status) | phase=$($summary.phase) | response_bytes=$($summary.response_bytes) | error=$($summary.error)"
        }
    }
}
finally {
    if ($uploaded) {
        try {
            Invoke-WorkerSmokeNativeCommand -FilePath $adb -Arguments @(
                '-s', $device, 'shell', 'rm', '-f', $remote
            ) | Out-Null
        }
        catch { Write-Warning "Temporary probe binary cleanup failed: $_" }
    }
    $report = [ordered]@{
        issue = 101
        target = 'Flowseal Legacy WebSocket'
        device = $device
        domain = $WorkerDomain
        rounds = $Rounds
        ipv4Addresses = $ipv4
        ipv6Addresses = $ipv6
        ipv6AddressSource = if ($IPv6Address) { 'explicit_prior_device_log' } else { 'windows_dns' }
        scope = 'Android -> Cloudflare Worker /apiws -> www.google.com:443 TLS response'
        limitation = 'Does not test Telegram DC2; failure can also indicate upstream Google connection restrictions'
        results = $results
    }
    $report | ConvertTo-Json -Depth 7 | Set-Content -LiteralPath $reportPath -Encoding UTF8
    Write-Host "REPORT TO SHARE: $reportPath"
}
) {
        $responseBytes = [long]$responseProperty.Value
    }
    $errorMessage = if ($null -ne $errorProperty -and $null -ne $errorProperty.Value) {
        [string]$errorProperty.Value
    } else { '' }
    [pscustomobject]@{
        status = if ($phase -eq 'upstream_reply_received' -and $responseBytes -gt 0) { 'PASS' } else { 'FAIL' }
        phase = $phase
        response_bytes = $responseBytes
        error = $errorMessage
    }
}

$out = Join-Path $root 'artifacts\worker-issue-101'
New-Item -ItemType Directory -Path $out -Force | Out-Null
$stamp = Get-Date -Format 'yyyyMMdd-HHmmss'
$exe = Join-Path $out "flowseal-ip-probe-$stamp"
$reportPath = Join-Path $out "flowseal-ip-ab-$stamp.json"
$remote = "/data/local/tmp/tgwsproxy-flowseal-ip-probe-101"

Write-Host "Testing on Android device: $device"
Write-Host 'This test does NOT change TgWsProxy or Telegram settings.'
Write-Host 'It tests WebSocket TCP forwarding to www.google.com, not MTProto to Telegram DC2.'
Write-Host "Resolved IPv4: $($ipv4 -join ', ')"
Write-Host "Resolved IPv6: $($ipv6 -join ', ')"

$previousGOOS = $env:GOOS
$previousGOARCH = $env:GOARCH
$previousCGO = $env:CGO_ENABLED
$results = @()
$uploaded = $false
try {
    Push-Location $root
    try {
        & $go test $source $unitTest
        if ($LASTEXITCODE -ne 0) { throw 'Flowseal IP probe unit tests failed' }

        $env:GOOS = 'android'
        $env:GOARCH = 'arm64'
        $env:CGO_ENABLED = '0'
        & $go build -trimpath -o $exe $source
        if ($LASTEXITCODE -ne 0) { throw 'Android arm64 IP probe compilation failed' }
    }
    finally {
        Pop-Location
        $env:GOOS = $previousGOOS
        $env:GOARCH = $previousGOARCH
        $env:CGO_ENABLED = $previousCGO
    }

    Invoke-WorkerSmokeNativeCommand -FilePath $adb -Arguments @(
        '-s', $device, 'push', $exe, $remote
    ) | Out-Null
    $uploaded = $true

    Invoke-WorkerSmokeNativeCommand -FilePath $adb -Arguments @(
        '-s', $device, 'shell', 'chmod', '700', $remote
    ) | Out-Null

    $families = @(
        @{ Name = 'tcp4'; Addresses = $ipv4 },
        @{ Name = 'tcp6'; Addresses = $ipv6 }
    )
    for ($round = 1; $round -le $Rounds; $round++) {
        foreach ($family in $families) {
            if ($family.Addresses.Count -eq 0) {
                Write-Warning "No $($family.Name) DNS address found: skipping"
                continue
            }

            $ip = $family.Addresses[($round - 1) % $family.Addresses.Count]
            Write-Host "Round $round/$Rounds | $($family.Name) | $ip ..."
            try {
                $lines = @(Invoke-WorkerSmokeNativeCommand -FilePath $adb -Arguments @(
                    '-s', $device, 'shell', $remote,
                    '-network', $family.Name,
                    '-worker', $WorkerDomain,
                    '-ip', $ip,
                    '-timeout', "$($TimeoutSeconds)s"
                ))
                $jsonLines = @($lines | Where-Object { $_.TrimStart().StartsWith('{') })
                if ($jsonLines.Count -eq 0) {
                    throw "No JSON from Android probe: $($lines -join ' ')"
                }
                $result = $jsonLines[-1] | ConvertFrom-Json
            }
            catch {
                $result = [pscustomobject]@{
                    network = $family.Name
                    dial_address = $ip
                    phase = 'adb_execution'
                    error = $_.Exception.Message
                    response_bytes = 0
                }
            }
            $results += $result
            $status = if ($result.phase -eq 'upstream_reply_received' -and
                          $result.response_bytes -gt 0) { 'PASS' } else { 'FAIL' }
            Write-Host "$($family.Name): $status | phase=$($result.phase) | response_bytes=$($result.response_bytes) | error=$($result.error)"
        }
    }
}
finally {
    if ($uploaded) {
        try {
            Invoke-WorkerSmokeNativeCommand -FilePath $adb -Arguments @(
                '-s', $device, 'shell', 'rm', '-f', $remote
            ) | Out-Null
        }
        catch { Write-Warning "Temporary probe binary cleanup failed: $_" }
    }
    $report = [ordered]@{
        issue = 101
        target = 'Flowseal Legacy WebSocket'
        device = $device
        domain = $WorkerDomain
        rounds = $Rounds
        scope = 'Android -> Cloudflare Worker /apiws -> www.google.com:443 TLS response'
        limitation = 'Does not test Telegram DC2; failure can also indicate upstream Google connection restrictions'
        results = $results
    }
    $report | ConvertTo-Json -Depth 7 | Set-Content -LiteralPath $reportPath -Encoding UTF8
    Write-Host "REPORT TO SHARE: $reportPath"
}
