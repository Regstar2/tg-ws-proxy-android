# Issue #127: bounded Direct WS Wi-Fi smoke. No CF/TLS ClientHello experiments.
# Debug-only receiver backs up/restores Wi-Fi settings. Never restarts Telegram
# or uninstalls/clears application data.
[CmdletBinding()]
param(
    [ValidateRange(1, 3)][int]$AttemptsPerProfile = 1,
    [ValidateRange(20, 120)][int]$AttemptSeconds = 23,
    [string]$SigningRoot = "",
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

function Get-Issue127NativeCounter {
    param(
        [Parameter(Mandatory = $true)][string]$Broadcast,
        [Parameter(Mandatory = $true)][string]$Name
    )
    $match = [regex]::Match($Broadcast, "(?:^|;)" + [regex]::Escape($Name) + "=([0-9]+)")
    if (-not $match.Success) {
        throw "Missing native counter $Name in ADB status broadcast"
    }
    return [long]$match.Groups[1].Value
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

$results = [System.Collections.Generic.List[object]]::new()
$originalError = $null
$restoreError = $null
$backupMayExist = $false
$reportPath = Join-Path $SessionDir "direct-wifi-smoke.json"
$csvPath = Join-Path $SessionDir "direct-wifi-smoke.csv"

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
    if (-not (Test-Path -LiteralPath (Join-Path $Root "scripts/issue-135-rebuild-device.ps1"))) {
        throw "Required signed-safe installer was not found"
    }

    foreach ($profile in @("direct_reliability")) {
        Write-Host "=== Issue127: $profile / direct_ws / Wi-Fi ==="
        Assert-WifiActive

        # Preserve Telegram's data, Android app data and signing certificate.
        Invoke-CheckedAdb -Arguments @("shell", "am", "force-stop", $App) | Out-Null
        & (Join-Path $Root "scripts/issue-135-rebuild-device.ps1") -SigningRoot $SigningRoot -SkipInstrumentedTests
        if ($LASTEXITCODE -ne 0) {
            throw "Signed debug APK installation/build failed for $profile"
        }
        if (-not (Test-Installed $App)) {
            throw "App disappeared after safe installer"
        }

        # Open the app before asking its receiver to start a foreground service;
        # Android background-start limits can reject a receiver-only launch.
        Invoke-CheckedAdb -Arguments @("shell", "am", "start", "-n", $Activity) | Out-Null
        Start-Sleep -Seconds 2
        $backupMayExist = $true
        Invoke-Issue127Action -Name "CONFIGURE" | Out-Null
        Invoke-Issue127Action -Name "START" | Out-Null
        Start-Sleep -Seconds 4

        # START is only an Android service request. Confirm that the native
        # listener actually started, then snapshot its inbound-connection count.
        $nativeStart = (Invoke-Issue127Action -Name "STATUS") -join " "
        if ($nativeStart -notmatch 'status=LISTENING_ROUTE_READY;' -or
            $nativeStart -notmatch 'selected_backend=direct_ws;') {
            throw "Native Direct-only MTProto listener is not ready: $nativeStart"
        }
        $totalBefore = Get-Issue127NativeCounter -Broadcast $nativeStart -Name "total"
        $nativeEnd = ""

        # Stream only current-phase logs. Do not clear system-wide logcat.
        $log = Join-Path $SessionDir "$profile.log"
        $stderr = Join-Path $SessionDir "$profile.stderr.log"
        $capture = Start-Process -FilePath $Adb -ArgumentList @(
            "-s", $Serial, "logcat", "-T", "1", "-v", "threadtime",
            "-s", "TgWsProxy:*", "AndroidRuntime:E"
        ) -RedirectStandardOutput $log -RedirectStandardError $stderr -PassThru -NoNewWindow
        try {
            for ($i = 1; $i -le $AttemptsPerProfile; $i++) {
                Assert-WifiActive
                Write-Host "$profile trial $i/$AttemptsPerProfile"
                # Only the authenticated synthetic Direct probe runs. No
                # Telegram force-stop or launch: prior testing triggered a
                # large reconnect storm that obscured transport evidence.
                Invoke-Issue127Action -Name "PROBE" | Out-Null
                Start-Sleep -Seconds $AttemptSeconds
                Start-Sleep -Seconds 2
            }
            $nativeEnd = (Invoke-Issue127Action -Name "STATUS") -join " "
        } finally {
            # Stop the proxy while logcat is still recording so the final
            # MTProto bridge close counters are not lost to an early capture stop.
            try {
                Invoke-Issue127Action -Name "STOP" | Out-Null
                Start-Sleep -Seconds 3
            } catch {
                Write-Warning "Proxy STOP broadcast failed: $_"
            }
            if (-not $capture.HasExited) {
                Stop-Process -Id $capture.Id -ErrorAction SilentlyContinue
                [void]$capture.WaitForExit(5000)
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
        $routeFailures = @($lines | Where-Object {
            $_ -match 'MTProto route failed' -and $_ -match 'selected_backend=direct_ws'
        })
        $routeRejections = @($lines | Where-Object {
            $_ -match 'MTProto route rejected' -and $_ -match 'reason=handshake_failed'
        })
        $totalAfter = Get-Issue127NativeCounter -Broadcast $nativeEnd -Name "total"
        $acceptedInbounds = $totalAfter - $totalBefore
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
            nativeInboundConnections = $acceptedInbounds
            nativeInboundTotalBefore = $totalBefore
            nativeInboundTotalAfter = $totalAfter
            directRouteFailures = $routeFailures.Count
            localHandshakeRejections = $routeRejections.Count
            syntheticProbeAttempts = $AttemptsPerProfile
            directBridgeCloses = $closed.Count
            directDuplexSessions = $duplex.Count
            classifiedErrorLogLines = $connectFailures.Count
            status = if ($duplex.Count -gt 0) {
                "DIRECT_DUPLEX_OBSERVED"
            } elseif ($routeConnects.Count -gt 0) {
                "DIRECT_HANDSHAKE_OBSERVED"
            } elseif ($routeFailures.Count -gt 0) {
                "DIRECT_UPSTREAM_FAILED"
            } elseif ($acceptedInbounds -lt $AttemptsPerProfile) {
                "SYNTHETIC_INGRESS_NOT_OBSERVED"
            } elseif ($routeRejections.Count -gt 0) {
                "LOCAL_HANDSHAKE_REJECTED"
            } else {
                "DIRECT_ATTEMPT_UNCLASSIFIED"
            }
            telegramMessagesAndMedia = "NOT_VERIFIED"
            log = $log
        }
        $results.Add($result)
        Write-Host "$profile => $($result.status); inbounds=$acceptedInbounds; Direct connects=$($routeConnects.Count); route failures=$($routeFailures.Count)"
    }
} catch {
    $originalError = $_
    Write-Warning "Issue127 Direct smoke stopped: $($_.Exception.Message)"
} finally {
    # Preserve first failure and restore original Wi-Fi settings on exit.
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
    # No PoC remains. The newly installed ordinary signed debug APK stays
    # installed (the original release APK is not restored).
    $report = [ordered]@{
        issue = 127
        commit = (git -C $Root rev-parse HEAD)
        result = if ($restoreError) { "RESTORE_FAILED" } elseif ($originalError) { "FAILED" } else { "INCONCLUSIVE" }
        note = "Synthetic ingress only. Telegram message/media delivery remains NOT_VERIFIED; signed debug APK remains installed."
        results = @($results.ToArray())
        originalSettingsRestored = ($backupMayExist -and $null -eq $restoreError)
        error = if ($originalError) { $originalError.Exception.Message } else { $null }
        restoreError = if ($restoreError) { $restoreError.Exception.Message } else { $null }
    }
    $report | ConvertTo-Json -Depth 6 | Set-Content -LiteralPath $reportPath -Encoding UTF8
    $results | Export-Csv -LiteralPath $csvPath -Delimiter ';' -NoTypeInformation -Encoding UTF8
    Write-Host "Direct smoke JSON: $reportPath"
    Write-Host "Direct smoke CSV: $csvPath"
    $results | Format-Table profile, status, nativeInboundConnections, routeConnects, directRouteFailures -AutoSize
}
if ($restoreError) {
    throw "Could not restore original Wi-Fi settings. Inspect $reportPath; retry with -RestoreOnly."
}
if ($originalError) {
    throw $originalError
}
