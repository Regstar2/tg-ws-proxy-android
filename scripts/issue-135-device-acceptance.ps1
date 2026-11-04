# Issue #135: release-signed debug APK, installed over the existing application.
param(
    [switch]$SkipBuild,
    [switch]$SkipInstall,
    [string]$OutputDirectory = "",
    [string]$SigningRoot = ""
)

$ErrorActionPreference = "Stop"
$Root = (Resolve-Path (Join-Path $PSScriptRoot "..")).Path
Set-Location $Root

$TestPackage = "io.github.regstar2.tgwsproxy"
if ([string]::IsNullOrWhiteSpace($SigningRoot)) {
    $SigningRoot = $Root
}
$SigningRoot = (Resolve-Path -LiteralPath $SigningRoot).Path

# The original checkout may hold an ignored, locally configured signing loader.
$SigningLoader = Join-Path $SigningRoot "scripts/load-release-signing.ps1"
if (-not (Test-Path -LiteralPath $SigningLoader)) {
    $SigningLoader = Join-Path $Root "scripts/load-release-signing.ps1"
}
if (Test-Path -LiteralPath $SigningLoader) {
    & $SigningLoader | Out-Null
}

if ([string]::IsNullOrWhiteSpace($env:KEYSTORE_FILE)) {
    $env:KEYSTORE_FILE = "tgwsproxy-release.jks"
}
foreach ($Name in @("KEYSTORE_PASSWORD", "KEY_PASSWORD")) {
    if ([string]::IsNullOrWhiteSpace([Environment]::GetEnvironmentVariable($Name))) {
        throw "Missing $Name. Configure the existing release signing key locally; never generate a replacement."
    }
}
if ([string]::IsNullOrWhiteSpace($env:KEY_ALIAS)) {
    $env:KEY_ALIAS = "tgwsproxy"
}

$KeystorePath = $env:KEYSTORE_FILE
if (-not [IO.Path]::IsPathRooted($KeystorePath)) {
    $KeystorePath = Join-Path $SigningRoot $KeystorePath
}
if (-not (Test-Path -LiteralPath $KeystorePath -PathType Leaf)) {
    throw "Original release keystore not found: $KeystorePath. No app was replaced."
}
$env:KEYSTORE_FILE = (Resolve-Path -LiteralPath $KeystorePath).ProviderPath

if (-not (Get-Command adb -ErrorAction SilentlyContinue)) {
    throw "adb not found in PATH"
}
if (-not (Test-Path ".\gradlew.bat")) {
    throw "Gradle wrapper missing"
}

$Connected = @(adb devices | Where-Object { $_ -match '^\S+\s+device\s*$' })
if ($LASTEXITCODE -ne 0) { throw "adb devices failed" }
if ($Connected.Count -ne 1) {
    throw "Expected exactly one authorized device; got $($Connected.Count)"
}
$Serial = ($Connected[0] -split '\s+')[0]

$Sdk = if ($env:ANDROID_HOME) {
    $env:ANDROID_HOME
} elseif ($env:ANDROID_SDK_ROOT) {
    $env:ANDROID_SDK_ROOT
} else {
    Join-Path $env:LOCALAPPDATA "Android\Sdk"
}
$BuildTools = Join-Path $Sdk "build-tools"
$ApkSigner = Get-ChildItem -Path $BuildTools -Filter "apksigner.bat" -Recurse -ErrorAction SilentlyContinue |
    Sort-Object FullName -Descending | Select-Object -First 1
$Aapt = Get-ChildItem -Path $BuildTools -Filter "aapt.exe" -Recurse -ErrorAction SilentlyContinue |
    Sort-Object FullName -Descending | Select-Object -First 1
if (-not $ApkSigner -or -not $Aapt) {
    throw "Android SDK build-tools: apksigner.bat and aapt.exe are required."
}

$Stamp = Get-Date -Format "yyyyMMdd-HHmmss"
if ([string]::IsNullOrWhiteSpace($OutputDirectory)) {
    $OutputDirectory = Join-Path $Root "artifacts/issue-135-device/$Stamp"
}
New-Item -ItemType Directory -Force -Path $OutputDirectory | Out-Null
$OutputDirectory = (Resolve-Path $OutputDirectory).Path

$Status = "FAIL"
$PreviousSignedFlag = [Environment]::GetEnvironmentVariable("TGWS_SIGNED_WORKER_SMOKE")
try {
    # This existing Gradle opt-in signs debug with the ORIGINAL release key and
    # retains the release applicationId and versionCode.
    $env:TGWS_SIGNED_WORKER_SMOKE = "1"

    @(
        "serial=$Serial",
        "android_release=$(adb -s $Serial shell getprop ro.build.version.release)",
        "device_model=$(adb -s $Serial shell getprop ro.product.model)",
        "git_head=$(git rev-parse HEAD)",
        "application_id=$TestPackage",
        "build_signing=original_release_key"
    ) | Set-Content -Path (Join-Path $OutputDirectory "device.txt") -Encoding UTF8

    if (-not $SkipBuild) {
        & .\gradlew.bat :app:testDebugUnitTest :app:lintDebug :app:assembleDebug :app:assembleDebugAndroidTest
        if ($LASTEXITCODE -ne 0) { throw "Build, lint or JVM tests failed" }
    }

    $Apk = Join-Path $Root "app/build/outputs/apk/debug/app-debug.apk"
    if (-not (Test-Path -LiteralPath $Apk -PathType Leaf)) {
        throw "Signed debug APK not found: $Apk"
    }

    $Badging = @(& $Aapt.FullName dump badging $Apk)
    if ($LASTEXITCODE -ne 0) { throw "Failed to inspect APK application ID" }
    if (-not ($Badging | Where-Object { $_ -match "^package: name='io\.github\.regstar2\.tgwsproxy'" })) {
        throw "APK application ID does not match the installed release: $TestPackage"
    }

    $CertReport = @(& $ApkSigner.FullName verify --print-certs $Apk)
    if ($LASTEXITCODE -ne 0) {
        throw "Signed debug APK failed apksigner verification"
    }
    $CertLine = $CertReport | Where-Object {
        $_ -match '^Signer #1 certificate SHA-256 digest:\s*([0-9a-fA-F]+)'
    } | Select-Object -First 1
    if (-not $CertLine) {
        throw "APK signing certificate digest not found; installation stopped"
    }
    $CertificateDigest = ([regex]::Match($CertLine, '([0-9a-fA-F]{64})\s*$')).Groups[1].Value
    if ($CertificateDigest.Length -ne 64) {
        throw "APK signing certificate SHA-256 digest is invalid"
    }

    @(
        "apk_sha256=$((Get-FileHash -LiteralPath $Apk -Algorithm SHA256).Hash)",
        "certificate_sha256=$CertificateDigest",
        "application_id=$TestPackage"
    ) | Set-Content -Path (Join-Path $OutputDirectory "apk-signature.txt") -Encoding UTF8

    # Instrumentation must use the same certificate as the target app.
    $TestApk = Join-Path $Root "app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk"
    if (-not (Test-Path -LiteralPath $TestApk -PathType Leaf)) {
        throw "Android instrumentation APK missing: $TestApk"
    }
    $TestCertReport = @(& $ApkSigner.FullName verify --print-certs $TestApk)
    if ($LASTEXITCODE -ne 0) { throw "Android instrumentation APK signature verification failed" }
    $TestCertLine = $TestCertReport | Where-Object {
        $_ -match '^Signer #1 certificate SHA-256 digest:\s*([0-9a-fA-F]+)'
    } | Select-Object -First 1
    if (-not $TestCertLine) { throw "Instrumentation APK certificate digest unavailable" }
    $TestCertDigest = ([regex]::Match($TestCertLine, '([0-9a-fA-F]{64})\s*$')).Groups[1].Value
    if ($TestCertDigest -ine $CertificateDigest) {
        throw "Instrumentation APK certificate differs from the release-signed debug APK; refusing device test"
    }

    if (-not $SkipInstall) {
        Write-Host "Installing RELEASE-SIGNED debug APK over $TestPackage (no uninstall or data deletion)"
        adb -s $Serial install -r $Apk
        if ($LASTEXITCODE -ne 0) {
            throw "Install rejected. Confirm the LOCAL keystore is the ORIGINAL signing key. Existing app/data were not removed."
        }
        $PackagePaths = @(adb -s $Serial shell pm path $TestPackage)
        if ($LASTEXITCODE -ne 0 -or @($PackagePaths | Where-Object { $_ -match '^package:' }).Count -eq 0) {
            throw "Installed package not found: $TestPackage"
        }
    }

    & .\gradlew.bat :app:connectedDebugAndroidTest
    if ($LASTEXITCODE -ne 0) { throw "Instrumentation tests failed" }
    $Status = "PASS"
}
finally {
    if ($null -eq $PreviousSignedFlag) {
        Remove-Item Env:TGWS_SIGNED_WORKER_SMOKE -ErrorAction SilentlyContinue
    } else {
        $env:TGWS_SIGNED_WORKER_SMOKE = $PreviousSignedFlag
    }

    $Results = "app/build/outputs/androidTest-results/connected"
    $Report = "app/build/reports/androidTests/connected"
    if (Test-Path $Results) {
        Copy-Item $Results (Join-Path $OutputDirectory "instrumentation-results") -Recurse -Force
    }
    if (Test-Path $Report) {
        Copy-Item $Report (Join-Path $OutputDirectory "instrumentation-report") -Recurse -Force
    }

    $RouteLog = @(adb -s $Serial logcat -d -v time '*:I' 2>$null |
        Where-Object { $_ -match 'MTProto route truth|MTProto fallback activated|Route policy' } |
        ForEach-Object {
            $_ -replace 'session_id=\S+', 'session_id=<redacted>' -replace '(target|domain|worker_url|inner_target)=\S+', '$1=<redacted>' -replace '((?:[0-9]{1,3}\.){3}[0-9]{1,3})', '<ip-redacted>'
        })
    $RouteLog | Set-Content -Path (Join-Path $OutputDirectory "route-log-redacted.txt") -Encoding UTF8
    "status=$Status" | Set-Content -Path (Join-Path $OutputDirectory "status.txt") -Encoding UTF8
    Write-Host "Issue #135 device gate: $Status"
    Write-Host "Evidence: $OutputDirectory"
}
)).Groups[1].Value
    if ($TestCertDigest -ine $CertificateDigest) {
        throw "Instrumentation APK certificate differs from the release-signed debug APK; refusing device test"
    }

    if (-not $SkipInstall) {
        Write-Host "Installing RELEASE-SIGNED debug APK over $TestPackage (no uninstall or data deletion)"
        adb -s $Serial install -r $Apk
        if ($LASTEXITCODE -ne 0) {
            throw "Install rejected. Confirm the LOCAL keystore is the ORIGINAL signing key. Existing app/data were not removed."
        }
        $PackagePaths = @(adb -s $Serial shell pm path $TestPackage)
        if ($LASTEXITCODE -ne 0 -or @($PackagePaths | Where-Object { $_ -match '^package:' }).Count -eq 0) {
            throw "Installed package not found: $TestPackage"
        }
    }

    & .\gradlew.bat :app:connectedDebugAndroidTest
    if ($LASTEXITCODE -ne 0) { throw "Instrumentation tests failed" }
    $Status = "PASS"
}
finally {
    if ($null -eq $PreviousSignedFlag) {
        Remove-Item Env:TGWS_SIGNED_WORKER_SMOKE -ErrorAction SilentlyContinue
    } else {
        $env:TGWS_SIGNED_WORKER_SMOKE = $PreviousSignedFlag
    }

    $Results = "app/build/outputs/androidTest-results/connected"
    $Report = "app/build/reports/androidTests/connected"
    if (Test-Path $Results) {
        Copy-Item $Results (Join-Path $OutputDirectory "instrumentation-results") -Recurse -Force
    }
    if (Test-Path $Report) {
        Copy-Item $Report (Join-Path $OutputDirectory "instrumentation-report") -Recurse -Force
    }

    $RouteLog = @(adb -s $Serial logcat -d -v time '*:I' 2>$null |
        Where-Object { $_ -match 'MTProto route truth|MTProto fallback activated|Route policy' } |
        ForEach-Object {
            $_ -replace 'session_id=\S+', 'session_id=<redacted>' -replace '(target|domain|worker_url|inner_target)=\S+', '$1=<redacted>' -replace '((?:[0-9]{1,3}\.){3}[0-9]{1,3})', '<ip-redacted>'
        })
    $RouteLog | Set-Content -Path (Join-Path $OutputDirectory "route-log-redacted.txt") -Encoding UTF8
    "status=$Status" | Set-Content -Path (Join-Path $OutputDirectory "status.txt") -Encoding UTF8
    Write-Host "Issue #135 device gate: $Status"
    Write-Host "Evidence: $OutputDirectory"
}
