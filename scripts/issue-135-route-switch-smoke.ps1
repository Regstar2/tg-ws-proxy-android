# Capture an isolated Android process log while exercising the live ordered route editor.
# This script NEVER uninstalls packages, clears data, or runs Gradle connected tests.
param([string]$Serial = "")

$ErrorActionPreference = "Stop"
$Root = (Resolve-Path (Join-Path $PSScriptRoot "..")).Path
Set-Location $Root

if (-not (Get-Command adb -ErrorAction SilentlyContinue)) {
    throw "adb is not in PATH"
}
if ([string]::IsNullOrWhiteSpace($Serial)) {
    $Devices = @(adb devices | Where-Object { $_ -match '^\S+\s+device\s*$' })
    if ($LASTEXITCODE -ne 0 -or $Devices.Count -ne 1) {
        throw "Exactly one authorized Android device is required"
    }
    $Serial = ($Devices[0] -split '\s+')[0]
}

$Package = "io.github.regstar2.tgwsproxy"
$Installed = @(adb -s $Serial shell pm path $Package)
if ($LASTEXITCODE -ne 0 -or @($Installed | Where-Object {
    $_ -match '^package:'
}).Count -eq 0) {
    throw "Expected production application is not installed"
}

adb -s $Serial shell am start -n "$Package/com.amurcanov.tgwsproxy.MainActivity"
if ($LASTEXITCODE -ne 0) { throw "Cannot open target application" }

# Starting an Activity returns before Android always publishes its process PID.
# Wait for one stable app PID instead of failing the first time the Activity opens.
$PidValue = $null
$ObservedPids = @()
for ($Attempt = 0; $Attempt -lt 20; $Attempt++) {
    $PidOutput = @(adb -s $Serial shell pidof $Package 2>$null)
    if ($LASTEXITCODE -eq 0) {
        $ObservedPids = @(($PidOutput -join " ") -split '\s+' |
            Where-Object { $_ -match '^\d+$' } | Select-Object -Unique)
        if ($ObservedPids.Count -eq 1) {
            $PidValue = $ObservedPids[0]
            break
        }
    }
    Start-Sleep -Milliseconds 500
}
if (-not $PidValue) {
    throw "Timed out waiting for one running process for $Package; last PIDs: $($ObservedPids -join ',')"
}

$Dir = Join-Path $Root "artifacts/issue135"
New-Item -ItemType Directory -Force -Path $Dir | Out-Null
$Stamp = Get-Date -Format "yyyyMMdd-HHmmss"
$Log = Join-Path $Dir "route-switch-$Stamp.txt"
$Errors = Join-Path $Dir "route-switch-$Stamp.stderr.txt"
$AdbPath = (Get-Command adb).Source

Write-Host "Capturing only PID=$PidValue from $Package"
Write-Host "Log file: $Log"
$Capture = Start-Process -FilePath $AdbPath -ArgumentList @(
    "-s", $Serial, "logcat", "--pid=$PidValue", "-v", "threadtime",
    "TgWsProxy:V", "AndroidRuntime:E", "*:S"
) -RedirectStandardOutput $Log -RedirectStandardError $Errors -PassThru -NoNewWindow
try {
    Read-Host "1. Enable only Cloudflare Proxy on the ACTIVE network profile. Start proxy, send a Telegram message. Press Enter"
    Read-Host "2. Enable a second configured route and change priority. Send a Telegram message. Press Enter"
    Read-Host "3. Return to Cloudflare Proxy only. Send a message, then stop proxy to flush traffic logs. Press Enter"
} finally {
    if (-not $Capture.HasExited) {
        Stop-Process -Id $Capture.Id -ErrorAction SilentlyContinue
        $Capture.WaitForExit(5000) | Out-Null
    }
}
if (-not (Test-Path -LiteralPath $Log)) { throw "ADB log was not saved" }
$Lines = @(Get-Content -LiteralPath $Log)
$Updates = @($Lines | Where-Object { $_ -match 'MTProto route policy updated|Policy changed' })
$Connected = @($Lines | Where-Object { $_ -match 'MTProto route connected' })
$Duplex = @($Lines | Where-Object {
    $match = [regex]::Match($_, 'MTProto bridge closed.*?up_bytes=(\d+)\s+down_bytes=(\d+)')
    $match.Success -and [long]$match.Groups[1].Value -gt 0 -and
        [long]$match.Groups[2].Value -gt 0
})
$NoRoute = @($Lines | Where-Object { $_ -match 'no_mtproto_route_available' })
$HandshakeErrors = @($Lines | Where-Object {
    $_ -match 'fake tls client hello expected|unsupported mtproto transport'
})
Write-Host "=== ROUTE SWITCH ACCEPTANCE ==="
Write-Host "Hot policy updates: $($Updates.Count)"
Write-Host "Connected MTProto sessions: $($Connected.Count)"
Write-Host "Duplex MTProto sessions: $($Duplex.Count)"
Write-Host "No-route failures: $($NoRoute.Count)"
Write-Host "Ingress handshake rejections: $($HandshakeErrors.Count)"
# A preferred route is not proof of traffic through it. Report real duplex backends.
$BackendGroups = @($Duplex | ForEach-Object {
    if ($_ -match '\bactual_backend=([^\s]+)') { $Matches[1] }
} | Group-Object | Sort-Object Name)
$GenerationGroups = @($Duplex | ForEach-Object {
    if ($_ -match '\broute_generation=([0-9]+)') { $Matches[1] }
} | Group-Object | Sort-Object { [int]$_.Name })
Write-Host "Duplex sessions by actual backend:"
foreach ($Group in $BackendGroups) {
    Write-Host ("  {0}: {1}" -f $Group.Name, $Group.Count)
}
Write-Host "Duplex sessions by policy generation:"
foreach ($Group in $GenerationGroups) {
    Write-Host ("  generation {0}: {1}" -f $Group.Name, $Group.Count)
}
if (@($Updates | Where-Object { $_ -match '\bnewRoutes=awg_warp' }).Count -gt 0 -and
    @($BackendGroups | Where-Object { $_.Name -eq 'awg_warp' }).Count -eq 0) {
    Write-Warning "AWG was first in an applied policy, but AWG duplex traffic was not proven."
}
$Updates | Select-Object -Last 8 | ForEach-Object { Write-Host $_ }
$Duplex | Select-Object -Last 5 | ForEach-Object { Write-Host $_ }
Write-Host "Full log: $Log"

if ($NoRoute.Count -gt 0) {
    throw "FAIL: Native reported no_mtproto_route_available; inspect $Log"
}
if ($Updates.Count -lt 2) {
    Write-Warning "INCOMPLETE: fewer than two runtime policy transitions were captured"
}
if ($Connected.Count -eq 0 -or $Duplex.Count -eq 0) {
    Write-Warning "INCOMPLETE: real MTProto two-way traffic was not proven"
}
