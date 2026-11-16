[CmdletBinding()]
param(
    [switch]$SelfTest,
    [switch]$InstallSignedDebug,
    [string]$SigningRoot = '',
    [ValidateRange(20, 600)][int]$Seconds = 90,
    [string]$TelegramPackage = 'org.telegram.messenger'
)
Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'

function Get-Issue156Report([string[]]$Lines) {
    $entries = @()
    foreach ($line in @($Lines | Where-Object { $_ -match 'MTProto bridge closed' })) {
        $fields = @{}
        foreach ($key in @('actual_backend','selected_backend','fallback_used','signed_dc','up_bytes','down_bytes')) {
            $match = [regex]::Match($line, '(?:^|\s)' + $key + '=([^\s]+)')
            if ($match.Success) { $fields[$key] = $match.Groups[1].Value }
        }
        $up = [long]0
        $down = [long]0
        if ($fields['up_bytes'] -match '^\d+$') { $up = [long]$fields['up_bytes'] }
        if ($fields['down_bytes'] -match '^\d+$') { $down = [long]$fields['down_bytes'] }
        $entries += [pscustomobject]@{
            backend = $fields['actual_backend']
            selected = $fields['selected_backend']
            fallback = $fields['fallback_used']
            dc = $fields['signed_dc']
            up = $up
            down = $down
        }
    }
    $cf = @($entries | Where-Object {
        $_.backend -eq 'cf_proxy_ws' -and $_.selected -eq 'cf_proxy_ws' -and $_.fallback -eq 'false'
    })
    $other = @($entries | Where-Object {
        $_.backend -ne 'cf_proxy_ws' -or $_.selected -ne 'cf_proxy_ws' -or $_.fallback -ne 'false'
    })
    $duplex = @($cf | Where-Object { $_.up -gt 0 -and $_.down -gt 0 })
    $status = if ($other.Count) { 'FAIL_OFF_ROUTE' }
        elseif (-not $cf.Count) { 'INCONCLUSIVE_NO_CLOSED_CF_SESSIONS' }
        elseif (-not $duplex.Count) { 'FAIL_NO_DUPLEX' }
        else { 'CF_DUPLEX_OBSERVED_MEDIA_UNVERIFIED' }
    return [ordered]@{
        status = $status
        cfSessions = $cf.Count
        cfDuplex = $duplex.Count
        cfUpOnly = @($cf | Where-Object { $_.up -gt 0 -and $_.down -eq 0 }).Count
        offRoute = $other.Count
        dc2Duplex = @($duplex | Where-Object { $_.dc -in @('2','-2') }).Count
        dc4Duplex = @($duplex | Where-Object { $_.dc -in @('4','-4') }).Count
        mediaDcDuplex = @($duplex | Where-Object { $_.dc -match '^-\d+$' }).Count
        telegramMediaVerified = $false
        note = 'Bytes in both directions do not prove message/media delivery.'
    }
}
if ($SelfTest) {
    $ok = 'MTProto bridge closed signed_dc=2 selected_backend=cf_proxy_ws actual_backend=cf_proxy_ws fallback_used=false up_bytes=1024 down_bytes=2048'
    $silent = 'MTProto bridge closed signed_dc=-2 selected_backend=cf_proxy_ws actual_backend=cf_proxy_ws fallback_used=false up_bytes=5000 down_bytes=0'
    $bad = 'MTProto bridge closed signed_dc=2 selected_backend=cf_proxy_ws actual_backend=tcp_fallback fallback_used=true up_bytes=100 down_bytes=300'
    if ((Get-Issue156Report @($ok,$silent)).status -ne 'CF_DUPLEX_OBSERVED_MEDIA_UNVERIFIED') { throw 'Duplex result incorrect' }
    if ((Get-Issue156Report @($silent)).status -ne 'FAIL_NO_DUPLEX') { throw 'One-way result incorrect' }
    if ((Get-Issue156Report @($bad)).status -ne 'FAIL_OFF_ROUTE') { throw 'Fallback result incorrect' }
    if ((Get-Issue156Report @()).status -ne 'INCONCLUSIVE_NO_CLOSED_CF_SESSIONS') { throw 'Empty result incorrect' }
    Write-Host 'Issue #156 CF report regression: PASS'
    return
}
$root = Split-Path -Parent $PSScriptRoot
Set-Location -LiteralPath $root
$adb = (Get-Command adb -ErrorAction Stop).Source
$devices = @(& $adb devices | ForEach-Object {
    if ($_ -match '^(\S+)\s+device\s*$') { $Matches[1] }
})
if ($LASTEXITCODE -ne 0 -or $devices.Count -ne 1) {
    throw 'One authorized Android device required.'
}
$serial = $devices[0]
$package = 'io.github.regstar2.tgwsproxy'
$component = "$package/com.amurcanov.tgwsproxy.CfProxySmokeConfigReceiver"
$activity = "$package/com.amurcanov.tgwsproxy.MainActivity"
function Invoke-CfSmoke([string]$Action) {
    $result = @(& $adb -s $serial shell am broadcast --include-stopped-packages -n $component -a "$package.action.CF_SMOKE_$Action")
    if ($LASTEXITCODE -ne 0 -or -not @($result | Where-Object { $_ -match 'result=101' }).Count) {
        throw "Debug-only CF smoke action $Action failed: $($result -join '; ')"
    }
}
if ($InstallSignedDebug) {
    $params = @{ SkipInstrumentedTests = $true }
    if ($SigningRoot) { $params.SigningRoot = $SigningRoot }
    & (Join-Path $PSScriptRoot 'issue-135-rebuild-device.ps1') @params
}
$installed = @(& $adb -s $serial shell pm path $package)
if ($LASTEXITCODE -ne 0 -or -not @($installed | Where-Object { $_ -match '^package:' }).Count) {
    throw 'Proxy APK is not installed. No uninstall or data deletion will be performed.'
}
$dir = Join-Path $root ('artifacts\issue156\' + (Get-Date -Format 'yyyyMMdd-HHmmss'))
New-Item -ItemType Directory -Path $dir -Force | Out-Null
$raw = Join-Path $dir 'temporary-logcat.txt'
$err = Join-Path $dir 'temporary-stderr.txt'
$log = $null
$configured = $false
$started = $false
$restored = $false
$failure = ''
try {
    & $adb -s $serial shell am force-stop $package
    if ($LASTEXITCODE -ne 0) { throw 'Unable to stop previous proxy process' }
    $configured = $true
    Invoke-CfSmoke 'CONFIGURE'
    & $adb -s $serial shell am start -n $activity | Out-Null
    if ($LASTEXITCODE -ne 0) { throw 'Unable to open proxy app' }
    Start-Sleep -Seconds 2
    $log = Start-Process -FilePath $adb -ArgumentList @('-s',$serial,'logcat','-T','1',
        '-v','threadtime','TgWsProxy:V','AndroidRuntime:E','*:S') -RedirectStandardOutput $raw -RedirectStandardError $err -PassThru -NoNewWindow
    Start-Sleep -Seconds 2
    if ($log.HasExited) { throw 'Logcat capture did not start' }
    Invoke-CfSmoke 'START'
    $started = $true
    Start-Sleep -Seconds 3
    $launch = @(& $adb -s $serial shell am start -a android.intent.action.VIEW -d 'tg://resolve?domain=telegram' -p $TelegramPackage)
    if ($LASTEXITCODE -ne 0) { Write-Warning 'Telegram public channel did not open' }
    Write-Host "CF-only automatic capture: $Seconds seconds; existing settings will be restored."
    Start-Sleep -Seconds $Seconds
} catch {
    $failure = $_.Exception.Message
    Write-Warning "CF smoke failed: $failure"
} finally {
    try {
        if ($started) {
            & $adb -s $serial shell am start -n $activity | Out-Null
            if ($LASTEXITCODE -ne 0) { throw 'Unable to foreground proxy for stop' }
            Invoke-CfSmoke 'STOP'
            Start-Sleep -Seconds 3
        }
    } catch { Write-Warning "Proxy stop failed: $_" }
    if ($null -ne $log -and -not $log.HasExited) {
        Stop-Process -Id $log.Id -ErrorAction SilentlyContinue
        [void]$log.WaitForExit(5000)
    }
    if ($configured) {
        try {
            & $adb -s $serial shell am force-stop $package | Out-Null
            if ($LASTEXITCODE -ne 0) { throw 'Unable to stop proxy before restoring' }
            Invoke-CfSmoke 'RESTORE'
            $restored = $true
        } catch { Write-Warning "CF preference restoration failed: $_" }
    }
}
try {
    $lines = if (Test-Path -LiteralPath $raw) { @(Get-Content -LiteralPath $raw) } else { @() }
    $report = Get-Issue156Report $lines
    $report['commit'] = (& git rev-parse HEAD)
    $report['captureSeconds'] = $Seconds
    $report['settingsRestored'] = $restored
    $report['runnerError'] = $failure
    $file = Join-Path $dir 'report.json'
    $report | ConvertTo-Json -Depth 4 | Set-Content -LiteralPath $file -Encoding UTF8
    $report | Format-Table -AutoSize | Out-String | Write-Host
    Write-Host "Aggregate report: $file"
    if (-not $restored) { throw 'CF settings not confirmed restored; inspect debug backup' }
    if ($failure) { throw "CF smoke interrupted: $failure" }
    if ($report.status -ne 'CF_DUPLEX_OBSERVED_MEDIA_UNVERIFIED') {
        throw "CF acceptance is incomplete: $($report.status)"
    }
} finally {
    Remove-Item -LiteralPath $raw,$err -Force -ErrorAction SilentlyContinue
}
