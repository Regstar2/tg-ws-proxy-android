# Issue #127: fully scripted Wi-Fi Direct WS baseline/tcp_split device A/B.
# Debug-only receiver backs up/restores the original Wi-Fi policy and runtime
# preferences. No root, VPN, data reset, APK uninstall, UI taps or Docker.
[CmdletBinding()]
param(
    [ValidateRange(1, 10)][int]$AttemptsPerProfile = 3,
    [ValidateRange(10, 120)][int]$AttemptSeconds = 25,
    [string]$SigningRoot = "",
    [string]$TelegramPackage = "org.telegram.messenger",
    [string]$OutputRoot = "",
    [switch]$RestoreOnly
)

Set-StrictMode -Version Latest
$ErrorActionPreference = "Stop"

$Root = (Resolve-Path -LiteralPath (Join-Path $PSScriptRoot "..")).Path
Set-Location -LiteralPath $Root
if ([string]::IsNullOrWhiteSpace($SigningRoot)) { $SigningRoot = $Root }
if ([string]::IsNullOrWhiteSpace($OutputRoot)) {
    $OutputRoot = Join-Path $Root "artifacts/issue127"
}
New-Item -ItemType Directory -Path $OutputRoot -Force | Out-Null
$SessionDir = Join-Path $OutputRoot (Get-Date -Format "yyyyMMdd-HHmmss")
New-Item -ItemType Directory -Path $SessionDir -Force | Out-Null

$App = "io.github.regstar2.tgwsproxy"
$Receiver = "$App/com.amurcanov.tgwsproxy.Issue127DirectSmokeReceiver"
$Activity = "$App/com.amurcanov.tgwsproxy.MainActivity"
$Adb = (Get-Command adb -ErrorAction Stop).Source
. (Join-Path $PSScriptRoot "worker-issue-101-native-command.ps1")

$devices = @(& $Adb devices | Where-Object { $_ -match '^\S+\s+device\s*$' })
if ($LASTEXITCODE -ne 0 -or $devices.Count -ne 1) {
    throw "Exactly one authorized Android device must be attached"
}
$Serial = ($devices[0] -split '\s+')[0]

function Invoke-CheckedAdb {
    param([Parameter(Mandatory = $true)][string[]]$Arguments)
    return @(Invoke-WorkerSmokeNativeCommand -FilePath $Adb -Arguments (@("-s", $Serial) + $Arguments))
}

function Invoke-Issue127Action {
    param([Parameter(Mandatory = $true)][string]$Name)
    $action = "$App.action.ISSUE127_DIRECT_$Name"
    $lines = @(Invoke-CheckedAdb -Arguments @(
        "shell", "am", "broadcast", "--include-stopped-packages",
        "-n", $Receiver, "-a", $action
    ))
    if (-not @($lines | Where-Object { $_ -match 'result=101(?:\W|$)' }).Count) {
        throw "Issue127 $Name rejected or unavailable: $($lines -join ' ')"
    }
    return $lines
}

function Test-Installed([string]$Package) {
    $paths = @(Invoke-CheckedAdb -Arguments @("shell", "pm", "path", $Package))
    return @($paths | Where-Object { $_ -match '^package:' }).Count -gt 0
}

function Assert-WifiActive {
    # The debug fixture also checks Android's NetworkProfileProvider immediately
    # before configuration/start, so shell output alone cannot prove Wi-Fi.
    $state = @(Invoke-CheckedAdb -Arguments @("shell", "cmd", "wifi", "status"))
    if (($state -join " ") -notmatch '(?i)wi-?fi is connected') {
        throw "Wi-Fi connection was not confirmed by Android: $($state -join ' ')"
    }
}

$previousProfile = $env:TGWS_CF_CLIENTHELLO_POC
$results = [System.Collections.Generic.List[object]]::new()
$originalError = $null
$restoreError = $null
$backupMayExist = $false
$experimentalApkInstalled = $false
$standardRuntimeRestored = $false
$standardRuntimeError = $null
$reportPath = Join-Path $SessionDir "direct-wifi-ab.json"
$csvPath = Join-Path $SessionDir "direct-wifi-ab.csv"

try {
    if (-not (Test-Installed $App)) {
        throw "Existing app is missing; no fresh installation is permitted"
    }
    if ($RestoreOnly) {
        Invoke-CheckedAdb -Arguments @("shell", "am", "force-stop", $App) | Out-Null
        Invoke-Issue127Action -Name "RESTORE" | Out-Null
        Write-Host "Original Wi-Fi policy restored without APK uninstall"
        return
    }

    Assert-WifiActive
    if (-not (Test-Installed $TelegramPackage)) {
        throw "Telegram package not installed: $TelegramPackage"
    }
    if (-not (Test-Path -LiteralPath (Join-Path $Root "scripts/issue-135-rebuild-device.ps1"))) {
        throw "Required signed-safe installer was not found"
    }

    for ($profileIndex = 0; $profileIndex -lt 2; $profileIndex++) {
        $profile = @("baseline", "tcp_split")[$profileIndex]
        Write-Host "=== Issue127: $profile / direct_ws / Wi-Fi ==="
        Assert-WifiActive

        # Preserve Telegram's data, Android app data and signing certificate.
        Invoke-CheckedAdb -Arguments @("shell", "am", "force-stop", $App) | Out-Null
        $env:TGWS_CF_CLIENTHELLO_POC = $profile
        & (Join-Path $Root "scripts/issue-135-rebuild-device.ps1") -SigningRoot $SigningRoot -SkipInstrumentedTests
        if ($LASTEXITCODE -ne 0) {
            throw "Signed debug APK installation/build failed for $profile"
        }
        if (-not (Test-Installed $App)) {
            throw "App disappeared after safe installer"
        }
        $experimentalApkInstalled = $true

        # Open the app before asking its receiver to start a foreground service;
        # Android background-start limits can reject a receiver-only launch.
        Invoke-CheckedAdb -Arguments @("shell", "am", "start", "-n", $Activity) | Out-Null
        Start-Sleep -Seconds 2
        $backupMayExist = $true
        Invoke-Issue127Action -Name "CONFIGURE" | Out-Null
        Invoke-Issue127Action -Name "START" | Out-Null
        Start-Sleep -Seconds 4

        # Stream only current-phase logs. Do not clear system-wide logcat.
        $log = Join-Path $SessionDir "$profile.log"
        $stderr = Join-Path $SessionDir "$profile.stderr.log"
        $capture = Start-Process -FilePath $Adb -ArgumentList @(
            "-s", $Serial, "logcat", "-v", "threadtime",
            "-s", "TgWsProxy:*", "AndroidRuntime:E"
        ) -RedirectStandardOutput $log -RedirectStandardError $stderr -PassThru -NoNewWindow
        try {
            for ($i = 1; $i -le $AttemptsPerProfile; $i++) {
                Assert-WifiActive
                Write-Host "$profile trial $i/$AttemptsPerProfile"
                Invoke-CheckedAdb -Arguments @("shell", "am", "force-stop", $TelegramPackage) | Out-Null
                Invoke-CheckedAdb -Arguments @(
                    "shell", "monkey", "-p", $TelegramPackage,
                    "-c", "android.intent.category.LAUNCHER", "1"
                ) | Out-Null
                Start-Sleep -Seconds $AttemptSeconds
                # Force-close only Telegram to flush session accounting.
                Invoke-CheckedAdb -Arguments @("shell", "am", "force-stop", $TelegramPackage) | Out-Null
                Start-Sleep -Seconds 3
            }
        } finally {
            if (-not $capture.HasExited) {
                Stop-Process -Id $capture.Id -ErrorAction SilentlyContinue
                [void]$capture.WaitForExit(5000)
            }
            try {
                Invoke-Issue127Action -Name "STOP" | Out-Null
            } catch {
                Write-Warning "Proxy STOP broadcast failed: $_"
            }
            Invoke-CheckedAdb -Arguments @("shell", "am", "force-stop", $App) | Out-Null
        }

        # Connection success is not inferred from a TLS handshake, a selected
        # route, an HTTP 101, or a profile-specific marker alone.
        $lines = @(Get-Content -LiteralPath $log -ErrorAction Stop)
        $closed = @($lines | Where-Object { $_ -match 'MTProto bridge closed' -and $_ -match '\bactual_backend=direct_ws\b' })
        $duplex = @($closed | Where-Object {
            $_ -match '\bup_bytes=[1-9][0-9]*\b' -and
            $_ -match '\bdown_bytes=[1-9][0-9]*\b'
        })
        $splitWrites = @($lines | Where-Object {
            $_ -match 'WebSocket PoC: ClientHello tcp_split performed two writes'
        })
        $connectFailures = @($lines | Where-Object {
            $_ -match 'direct_ws_connect_failed|direct_ws_ip_timeout|tls_handshake|connection timed out'
        })
        $routeConnects = @($lines | Where-Object {
            $_ -match 'MTProto route connected' -and $_ -match '\bactual_backend=direct_ws\b'
        })
        $result = [pscustomobject]@{
            profile = $profile
            route = "direct_ws"
            network = "wifi"
            attempts = $AttemptsPerProfile
            routeConnects = $routeConnects.Count
            directBridgeCloses = $closed.Count
            directDuplexSessions = $duplex.Count
            splitWriteMessages = $splitWrites.Count
            classifiedErrorLogLines = $connectFailures.Count
            status = if ($duplex.Count -gt 0) { "DIRECT_DUPLEX_OBSERVED" } else { "INCONCLUSIVE" }
            telegramMessagesAndMedia = "NOT_VERIFIED"
            tcpSegmentation = "NOT_VERIFIED"
            log = $log
        }
        $results.Add($result)
        Write-Host "$profile => $($result.status); Direct duplex=$($duplex.Count); split writes=$($splitWrites.Count)"
    }
} catch {
    $originalError = $_
    Write-Warning "Issue127 A/B stopped: $($_.Exception.Message)"
} finally {
    # Preserve the first failure; restore Wi-Fi settings even when baseline,
    # split build or log capture fails halfway through the comparison.
    try {
        if ($backupMayExist) {
            Invoke-CheckedAdb -Arguments @("shell", "am", "force-stop", $App) | Out-Null
            Invoke-Issue127Action -Name "RESTORE" | Out-Null
            Write-Host "Original Wi-Fi policy and runtime settings restored"
        }
    } catch {
        $restoreError = $_
        Write-Warning "RESTORE FAILED. Debug app retains the original snapshot for -RestoreOnly: $_"
    }
    # The experiment must not stay enabled in the installed binary after
    # A/B. Rebuild and upgrade back to the ordinary, zero-profile debug APK.
    # The original *release* APK is not recreated here.
    if ($experimentalApkInstalled) {
        try {
            $env:TGWS_CF_CLIENTHELLO_POC = ""
            & (Join-Path $Root "scripts/issue-135-rebuild-device.ps1") -SigningRoot $SigningRoot -SkipInstrumentedTests
            if ($LASTEXITCODE -ne 0) {
                throw "Ordinary debug runtime rebuild/installation failed"
            }
            $standardRuntimeRestored = $true
            Write-Host "Default (non-PoC) native debug runtime reinstalled"
        } catch {
            $standardRuntimeError = $_
            Write-Warning "Default native runtime restore failed. Experimental APK may remain installed: $_"
        }
    }
    if ($null -eq $previousProfile) {
        Remove-Item Env:TGWS_CF_CLIENTHELLO_POC -ErrorAction SilentlyContinue
    } else {
        $env:TGWS_CF_CLIENTHELLO_POC = $previousProfile
    }
    $report = [ordered]@{
        issue = 127
        commit = (git -C $Root rev-parse HEAD)
        result = if ($restoreError -or $standardRuntimeError) { "RESTORE_FAILED" } elseif ($originalError) { "FAILED" } else { "INCONCLUSIVE" }
        note = "Telegram launch is not proof of delivered messages/media; duplex counters are observational only."
        results = @($results.ToArray())
        originalSettingsRestored = ($backupMayExist -and $null -eq $restoreError)
        ordinaryDebugRuntimeRestored = $standardRuntimeRestored
        ordinaryDebugRuntimeRestoreError = if ($standardRuntimeError) { $standardRuntimeError.Exception.Message } else { $null }
        error = if ($originalError) { $originalError.Exception.Message } else { $null }
        restoreError = if ($restoreError) { $restoreError.Exception.Message } else { $null }
    }
    $report | ConvertTo-Json -Depth 6 | Set-Content -LiteralPath $reportPath -Encoding UTF8
    $results | Export-Csv -LiteralPath $csvPath -Delimiter ';' -NoTypeInformation -Encoding UTF8
    Write-Host "A/B JSON: $reportPath"
    Write-Host "A/B CSV: $csvPath"
    $results | Format-Table profile, status, routeConnects, directDuplexSessions, splitWriteMessages -AutoSize
}
if ($restoreError) {
    throw "Could not restore original Wi-Fi settings. Inspect $reportPath; retry with -RestoreOnly."
}
if ($standardRuntimeError) {
    throw "Could not reinstall ordinary native debug runtime. Inspect $reportPath."
}
if ($originalError) {
    throw $originalError
}
