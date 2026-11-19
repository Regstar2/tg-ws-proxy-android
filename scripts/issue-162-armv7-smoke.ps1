[CmdletBinding()]
param(
    [string]$Serial = 'emulator-5558',
    [ValidateSet('armeabi-v7a','arm64-v8a')][string]$Abi = 'armeabi-v7a',
    [ValidateSet('armeabi-v7a','arm64-v8a','universal')][string]$Variant = 'armeabi-v7a',
    [string]$Avd = 'TgWsProxy_Issue162_API30_x86',
    [string]$ApkDirectory,
    [switch]$Build,
    [ValidateRange(60,1200)][int]$BootTimeoutSeconds = 600
)
Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'
$root = Split-Path -Parent $PSScriptRoot
Set-Location $root
$sdk = $env:ANDROID_SDK_ROOT
if (-not $sdk) { $sdk = 'C:\Android\Sdk' }
$adb = Join-Path $sdk 'platform-tools\adb.exe'
$aapt = (Get-ChildItem (Join-Path $sdk 'build-tools') -Filter aapt.exe -Recurse | Sort-Object FullName -Descending | Select-Object -First 1).FullName
$run = Join-Path $root ("artifacts\issue-162\smoke-" + $Serial + '-' + $Variant + '-' + $Abi + '-' + (Get-Date -Format yyyyMMdd-HHmmss))
New-Item -ItemType Directory -Force -Path $run | Out-Null
if (-not $ApkDirectory) { $ApkDirectory = Join-Path $root 'artifacts\issue-162\isolated' }
$package = 'io.github.regstar2.tgwsproxy.abismoke'
$counter = 0

function Invoke-Adb([string[]]$Arguments, [int]$TimeoutSeconds = 30) {
    $script:counter++
    $stdout = Join-Path $run "$script:counter-out.txt"
    $stderr = Join-Path $run "$script:counter-err.txt"
    # Arguments in this runner are controlled paths/identifiers without embedded quotes.
    $quoted = @('-s', $Serial) + $Arguments | ForEach-Object { '"' + $_.Replace('"','\"') + '"' }
    $process = Start-Process -FilePath $adb -ArgumentList $quoted -PassThru -WindowStyle Hidden -RedirectStandardOutput $stdout -RedirectStandardError $stderr
    if (-not $process.WaitForExit($TimeoutSeconds * 1000)) {
        $process.Kill()
        throw "ADB timeout ($TimeoutSeconds seconds): $($Arguments -join ' ')"
    }
    $text = (Get-Content -LiteralPath $stdout -Raw) + (Get-Content -LiteralPath $stderr -Raw)
    if ($process.ExitCode -ne 0) { throw "ADB failed: $text" }
    return $text
}

try {
    if (-not (Test-Path $adb)) { throw "ADB missing: $adb" }
    if ($Build) {
        & (Join-Path $root 'gradlew.bat') --no-daemon assembleDebug assembleDebugAndroidTest '-PisolatedAbiSmoke=true'
        if ($LASTEXITCODE -ne 0) { throw 'Gradle build failed' }
        New-Item -ItemType Directory -Force -Path $ApkDirectory | Out-Null
        Copy-Item (Join-Path $root 'app\build\outputs\apk\debug\app-*-debug.apk') $ApkDirectory -Force
        Copy-Item (Join-Path $root 'app\build\outputs\apk\androidTest\debug\app-debug-androidTest.apk') $ApkDirectory -Force
    }
    $devices = (& $adb devices) -join "`n"
    if ($devices -notmatch "(?m)^$([regex]::Escape($Serial))\s+device\b") {
        if ($Serial -ne 'emulator-5558') { throw "Device $Serial is unavailable; no automatic device substitution" }
        $emulator = Join-Path $sdk 'emulator\emulator.exe'
        Start-Process -FilePath $emulator -ArgumentList @('-avd',$Avd,'-port','5558','-no-window','-no-audio','-no-snapshot','-gpu','swiftshader_indirect') -WindowStyle Hidden -RedirectStandardOutput (Join-Path $run 'emulator-out.txt') -RedirectStandardError (Join-Path $run 'emulator-err.txt') | Out-Null
    }
    $deadline = (Get-Date).AddSeconds($BootTimeoutSeconds)
    $booted = $false
    while ((Get-Date) -lt $deadline) {
        try { $booted = (Invoke-Adb @('shell','getprop','sys.boot_completed')).Trim() -eq '1' } catch { $booted = $false }
        if ($booted) { break }
        Start-Sleep -Seconds 2
    }
    if (-not $booted) { throw 'Android boot timed out' }
    $abis = (Invoke-Adb @('shell','getprop','ro.product.cpu.abilist')).Trim()
    if ($Abi -notin ($abis -split ',')) { throw "ABI $Abi not supported: $abis" }
    $apk = Join-Path $ApkDirectory "app-$Variant-debug.apk"
    $testApk = Join-Path $ApkDirectory 'app-debug-androidTest.apk'
    & py -3 (Join-Path $PSScriptRoot 'audit-apk-abis.py') $apk $Variant
    if ($LASTEXITCODE -ne 0) { throw 'APK ABI audit failed' }
    foreach ($candidate in @(@{ Path=$apk; Package=$package }, @{ Path=$testApk; Package="$package.test" })) {
        $badging = (& $aapt dump badging $candidate.Path) -join "`n"
        if ($LASTEXITCODE -ne 0 -or $badging -notmatch ("package: name='" + [regex]::Escape($candidate.Package) + "'")) {
            throw 'Refusing to install APK outside the isolated test package'
        }
    }
    Invoke-Adb @('install','--abi',$Abi,'-r',$apk) 90 | Write-Host
    Invoke-Adb @('install','-r',$testApk) 90 | Write-Host
    $bits = if ($Abi -eq 'armeabi-v7a') { '32' } else { '64' }
    $result = Invoke-Adb @('shell','am','instrument','-w','-r','-e','class','com.amurcanov.tgwsproxy.NativeAbiIssue162Test','-e','expectedBits',$bits,"$package.test/androidx.test.runner.AndroidJUnitRunner") 120
    $result | Set-Content (Join-Path $run 'instrumentation.txt')
    if ($result -notmatch 'OK \(1 test\)' -or $result -match 'FAILURES|INSTRUMENTATION_FAILED|Process crashed') { throw "Instrumentation failed: $result" }
    $logs = Invoke-Adb @('logcat','-d','-s','Issue162:I')
    # Keep only this test's tagged evidence; do not copy other apps' private logs.
    $evidence = ($logs -split "`n" | Where-Object { $_ -match 'Issue162|io.github.regstar2.tgwsproxy.abismoke|Fatal signal|UnsatisfiedLinkError' }) -join "`n"
    $evidence | Set-Content (Join-Path $run 'runtime.txt')
    if ($evidence -notmatch "NATIVE_BITS=$bits" -or $evidence -notmatch "PASS native=$bits") { throw 'Missing actual native process evidence' }
    if ($evidence -match 'Fatal signal|UnsatisfiedLinkError|FATAL EXCEPTION') { throw 'Native/Android crash in logcat' }
    @{ Result='PASS'; Serial=$Serial; DeviceAbis=$abis; Abi=$Abi; Variant=$Variant; Bits=$bits; Evidence=$run } | ConvertTo-Json | Set-Content (Join-Path $run 'result.json')
    Write-Host "PASS: $Serial $Variant running $bits-bit JNA/Go. Evidence: $run"
} catch {
    $failure = $_
    # Preserve tagged diagnostics on failure too; never clear the device logcat.
    try {
        $failureLog = Invoke-Adb @('logcat','-d','-s','Issue162:I') 10
        $failureLog | Set-Content (Join-Path $run 'failure-runtime.txt')
        $pidMatches = [regex]::Matches($failureLog, '(?m)^\S+\s+\S+\s+(\d+)\s+\d+\s+I\s+Issue162\s*:')
        if ($pidMatches.Count -gt 0) {
            $nativePid = $pidMatches[$pidMatches.Count - 1].Groups[1].Value
            Invoke-Adb @('logcat','-d','-b','crash',"--pid=$nativePid") 10 | Set-Content (Join-Path $run 'failure-crash.txt')
        }
    } catch { Write-Warning 'Device log capture unavailable; inspect saved ADB stdout/stderr.' }
    @{ Result='FAIL'; Serial=$Serial; Abi=$Abi; Variant=$Variant; Error=$failure.Exception.Message } | ConvertTo-Json | Set-Content (Join-Path $run 'result.json')
    throw $failure
}
