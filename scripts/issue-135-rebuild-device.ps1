# Build and update an original-release-key-signed debug APK for Issue #135.
# No uninstall/clear-data commands. A missing installed app blocks silent reinstalls.
param(
    [string]$SigningRoot = "",
    [switch]$SkipInstrumentedTests,
    [switch]$AllowFreshInstall,
    [ValidateRange(30, 900)]
    [int]$InstrumentTimeoutSeconds = 240
)

$ErrorActionPreference = "Stop"
$Root = (Resolve-Path (Join-Path $PSScriptRoot "..")).Path
Set-Location $Root

if ([string]::IsNullOrWhiteSpace($SigningRoot)) { $SigningRoot = $Root }
$SigningRoot = (Resolve-Path -LiteralPath $SigningRoot).Path
$SigningLoader = Join-Path $SigningRoot "scripts/load-release-signing.ps1"
if (Test-Path -LiteralPath $SigningLoader) {
    & $SigningLoader | Out-Null
}

if ([string]::IsNullOrWhiteSpace($env:KEYSTORE_FILE)) {
    $env:KEYSTORE_FILE = "tgwsproxy-release.jks"
}
$Keystore = $env:KEYSTORE_FILE
if (-not [IO.Path]::IsPathRooted($Keystore)) {
    $Keystore = Join-Path $SigningRoot $Keystore
}
if (-not (Test-Path -LiteralPath $Keystore -PathType Leaf)) {
    throw "Original release keystore is missing. Supply -SigningRoot. Installation was not attempted."
}
foreach ($name in @("KEYSTORE_PASSWORD", "KEY_PASSWORD")) {
    if ([string]::IsNullOrWhiteSpace([Environment]::GetEnvironmentVariable($name))) {
        throw "Missing original release signing variable: $name"
    }
}
if ([string]::IsNullOrWhiteSpace($env:KEY_ALIAS)) { $env:KEY_ALIAS = "tgwsproxy" }
$env:KEYSTORE_FILE = (Resolve-Path -LiteralPath $Keystore).Path

foreach ($tool in @("adb", "git")) {
    if (-not (Get-Command $tool -ErrorAction SilentlyContinue)) {
        throw "Required executable not in PATH: $tool"
    }
}
if (-not (Test-Path -LiteralPath "./gradlew.bat")) { throw "Gradle wrapper is missing" }

$devices = @(adb devices | Where-Object { $_ -match '^\S+\s+device\s*$' })
if ($LASTEXITCODE -ne 0 -or $devices.Count -ne 1) {
    throw "Exactly one authorized Android device is required."
}
$serial = ($devices[0] -split '\s+')[0]
$applicationId = "io.github.regstar2.tgwsproxy"
$runner = "$applicationId.test/androidx.test.runner.AndroidJUnitRunner"

function Test-ApplicationInstalled {
    $paths = @(adb -s $serial shell pm path $applicationId)
    return $LASTEXITCODE -eq 0 -and @($paths | Where-Object { $_ -match '^package:' }).Count -gt 0
}

$appWasInstalled = Test-ApplicationInstalled
if (-not $appWasInstalled -and -not $AllowFreshInstall) {
    throw "The existing application is missing from Android. The earlier Gradle device test may have removed it and its data. No installation will be attempted. Inspect backups first; use -AllowFreshInstall only after accepting that previous settings may be gone."
}

$androidSdk = if ($env:ANDROID_HOME) { $env:ANDROID_HOME } elseif ($env:ANDROID_SDK_ROOT) {
    $env:ANDROID_SDK_ROOT
} else { Join-Path $env:LOCALAPPDATA "Android/Sdk" }
$buildTools = Join-Path $androidSdk "build-tools"
$signer = Get-ChildItem -LiteralPath $buildTools -Filter "apksigner.bat" -Recurse -ErrorAction Stop |
    Sort-Object FullName -Descending | Select-Object -First 1
$aapt = Get-ChildItem -LiteralPath $buildTools -Filter "aapt.exe" -Recurse -ErrorAction Stop |
    Sort-Object FullName -Descending | Select-Object -First 1
if (-not $signer -or -not $aapt) { throw "Android apksigner.bat or aapt.exe is missing" }

function Get-CertificateDigest([string]$filePath) {
    $output = @(& $signer.FullName verify --print-certs $filePath)
    if ($LASTEXITCODE -ne 0) { throw "APK signature verification failed: $filePath" }
    $digests = @($output | ForEach-Object {
        if ($_ -match '(?i)certificate SHA-256 digest:\s*([0-9a-f:\s]{64,})') {
            $matches[1] -replace '[^0-9a-fA-F]', ''
        }
    } | Where-Object { $_ -match '^[0-9a-fA-F]{64}$' } | ForEach-Object { $_.ToLowerInvariant() } | Select-Object -Unique)
    if ($digests.Count -ne 1) { throw "Certificate digest absent or conflicting: $filePath" }
    return $digests[0]
}

$previousSignedFlag = $env:TGWS_SIGNED_WORKER_SMOKE
try {
    # The existing build setting uses the ORIGINAL release certificate, not the debug keystore.
    $env:TGWS_SIGNED_WORKER_SMOKE = "1"
    & .\gradlew.bat :app:testDebugUnitTest :app:lintDebug :app:assembleDebug :app:assembleDebugAndroidTest
    if ($LASTEXITCODE -ne 0) { throw "Gradle build, lint or tests failed. Device was not changed." }

    $apk = Join-Path $Root "app/build/outputs/apk/debug/app-debug.apk"
    $testApk = Join-Path $Root "app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk"
    if (-not (Test-Path -LiteralPath $apk) -or -not (Test-Path -LiteralPath $testApk)) {
        throw "One of the required APKs is missing. Device was not changed."
    }
    $badging = @(& $aapt.FullName dump badging $apk)
    if ($LASTEXITCODE -ne 0 -or -not ($badging | Where-Object {
        $_ -match "^package:\s+name='io\.github\.regstar2\.tgwsproxy'"
    })) { throw "APK package ID is not the expected production application" }

    $appCert = Get-CertificateDigest $apk
    $testCert = Get-CertificateDigest $testApk
    if ($appCert -ne $testCert) {
        throw "Android test APK has a different signing certificate. Device was not changed."
    }
    Write-Host "Package: io.github.regstar2.tgwsproxy"
    Write-Host "Commit: $(git rev-parse HEAD)"
    Write-Host "Certificate SHA-256: $appCert"
    Write-Host "APK SHA-256: $((Get-FileHash -LiteralPath $apk -Algorithm SHA256).Hash)"
    if (-not $appWasInstalled) {
        Write-Warning "Fresh install explicitly authorized. Previous application settings cannot be guaranteed."
    } else {
        Write-Host "Upgrading release-signed DEBUG APK with adb install -r; no app uninstall or data clear."
    }
    adb -s $serial install -r $apk
    if ($LASTEXITCODE -ne 0) {
        throw "Android rejected the upgrade. Check original key/version; do not uninstall."
    }
    if (-not (Test-ApplicationInstalled)) {
        throw "Production application is missing after adb install -r."
    }

    if (-not $SkipInstrumentedTests) {
        # Gradle's connected test task can uninstall the production package on some
        # Android Gradle Plugin versions. Run the signed test APK directly instead.
        adb -s $serial install -r -t $testApk
        if ($LASTEXITCODE -ne 0) {
            throw "Android rejected the instrumentation APK; production app was not uninstalled."
        }
        $instrumentations = @(adb -s $serial shell pm list instrumentation)
        $expectedRunner = "instrumentation:$runner (target=$applicationId)"
        if ($LASTEXITCODE -ne 0 -or -not ($instrumentations | Where-Object { $_.Trim() -eq $expectedRunner })) {
            throw "Instrumentation runner missing or targeting a different application."
        }

        $testReportDirectory = Join-Path $env:TEMP "tgwsproxy-issue135-instrumentation"
        New-Item -ItemType Directory -Force -Path $testReportDirectory | Out-Null
        $outputPath = Join-Path $testReportDirectory "instrumentation.txt"
        $errorPath = Join-Path $testReportDirectory "instrumentation-errors.txt"
        $logcatPath = Join-Path $testReportDirectory "logcat.txt"
        Write-Host "Running on-device UI tests directly: $runner"
        Write-Host "Instrumentation results: $outputPath"

        $process = Start-Process -FilePath (Get-Command adb).Source -ArgumentList @(
            "-s", $serial, "shell", "am", "instrument", "-w", "-r", $runner
        ) -RedirectStandardOutput $outputPath -RedirectStandardError $errorPath -NoNewWindow -PassThru
        $completed = $process.WaitForExit($InstrumentTimeoutSeconds * 1000)
        if (-not $completed) {
            Stop-Process -Id $process.Id -Force -ErrorAction SilentlyContinue
        }
        $process.Refresh()
        $testOutput = @(Get-Content -LiteralPath $outputPath -ErrorAction SilentlyContinue)
        $testErrors = @(Get-Content -LiteralPath $errorPath -ErrorAction SilentlyContinue)
        $testOutput | ForEach-Object { Write-Host $_ }
        $testErrors | ForEach-Object { Write-Warning $_ }

        $passed = $completed -and $process.ExitCode -eq 0 -and
            @($testOutput | Where-Object { $_ -match '^INSTRUMENTATION_CODE:\s*-1\s*
} finally {
    if ($null -eq $previousSignedFlag) {
        Remove-Item Env:TGWS_SIGNED_WORKER_SMOKE -ErrorAction SilentlyContinue
    } else {
        $env:TGWS_SIGNED_WORKER_SMOKE = $previousSignedFlag
    }
}
 }).Count -eq 1
        if (-not $passed) {
            adb -s $serial logcat -d -b main -b system -b crash -v threadtime -t 10000 |
                Out-File -LiteralPath $logcatPath -Encoding UTF8
            throw "Android UI tests failed or timed out; see $outputPath, $errorPath and $logcatPath."
        }
    }

    if (-not (Test-ApplicationInstalled)) {
        throw "Production app disappeared during device testing. Do not repeat tests; inspect app data."
    }
    Write-Host "Signed debug build, upgrade and device verification completed."
} finally {
    if ($null -eq $previousSignedFlag) {
        Remove-Item Env:TGWS_SIGNED_WORKER_SMOKE -ErrorAction SilentlyContinue
    } else {
        $env:TGWS_SIGNED_WORKER_SMOKE = $previousSignedFlag
    }
}
