[CmdletBinding()]
param(
    [Parameter(Mandatory = $true)]
    [ValidateNotNullOrEmpty()]
    [string]$Version,

    [switch]$PreflightOnly
)

Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'

$root = Split-Path -Parent $PSScriptRoot
Set-Location $root

if ($Version -notmatch '^v\d+\.\d+\.\d+([-.][0-9A-Za-z.-]+)?$') {
    throw "Invalid release version '$Version'. Expected vX.Y.Z or a SemVer-compatible prerelease tag."
}

$versionName = $Version.Substring(1)
$gradleFile = Join-Path $root 'app\build.gradle.kts'
$gradleText = Get-Content $gradleFile -Raw -Encoding UTF8
$versionMatch = [regex]::Match($gradleText, 'val\s+releaseVersionName\s*=\s*"([^"]+)"')
$versionCodeMatch = [regex]::Match($gradleText, 'val\s+releaseVersionCode\s*=\s*(\d+)')
if (-not $versionMatch.Success) {
    throw 'Could not read releaseVersionName from app/build.gradle.kts.'
}
if (-not $versionCodeMatch.Success) {
    throw 'Could not read releaseVersionCode from app/build.gradle.kts.'
}
if ($versionMatch.Groups[1].Value -ne $versionName) {
    throw "Release tag $Version does not match app releaseVersionName '$($versionMatch.Groups[1].Value)'."
}
$expectedVersionCode = [int]$versionCodeMatch.Groups[1].Value

$releaseNotes = Join-Path $root ("docs\releases\RELEASE_NOTES_$Version.md")
if (-not (Test-Path $releaseNotes)) {
    throw "Release notes were not found for $Version: $releaseNotes"
}

$artifactName = "TgWsProxy-Android-$Version-arm64-v8a.apk"
Write-Host "Release metadata preflight: tag=$Version versionName=$versionName versionCode=$expectedVersionCode artifact=$artifactName"

if ($PreflightOnly) {
    Write-Host 'Release metadata preflight passed.'
    exit 0
}

$signingLoader = Join-Path $PSScriptRoot 'load-release-signing.ps1'
if (Test-Path $signingLoader) {
    & $signingLoader | Out-Null
}

$requiredSigningVariables = @('KEYSTORE_FILE', 'KEYSTORE_PASSWORD', 'KEY_PASSWORD')
foreach ($name in $requiredSigningVariables) {
    $value = [Environment]::GetEnvironmentVariable($name)
    if ([string]::IsNullOrWhiteSpace($value)) {
        throw "Required release signing environment variable '$name' is not configured."
    }
}

if ([string]::IsNullOrWhiteSpace($env:KEY_ALIAS)) {
    $env:KEY_ALIAS = 'tgwsproxy'
}

$keystorePath = $env:KEYSTORE_FILE
if (-not [System.IO.Path]::IsPathRooted($keystorePath)) {
    $keystorePath = Join-Path $root $keystorePath
}
if (-not (Test-Path $keystorePath)) {
    throw "Release keystore was not found: $keystorePath"
}

$dist = Join-Path $root 'dist'
if (Test-Path $dist) {
    Remove-Item $dist -Recurse -Force
}
New-Item -ItemType Directory -Path $dist -Force | Out-Null

$buildScript = Join-Path $PSScriptRoot 'build-apk.ps1'
if (-not (Test-Path $buildScript)) {
    throw "Release build script not found: $buildScript"
}

Write-Host "==> Build signed release APK for $Version"
& $buildScript -Configuration Release
if ($LASTEXITCODE -ne 0) {
    throw "Release APK build failed with exit code $LASTEXITCODE."
}

$sourceApk = Join-Path $root 'artifacts\apk\release\tgwsproxy-release.apk'
if (-not (Test-Path $sourceApk)) {
    throw "Expected release APK was not produced: $sourceApk"
}

$sdkRoot = $env:ANDROID_SDK_ROOT
if ([string]::IsNullOrWhiteSpace($sdkRoot)) {
    $sdkRoot = $env:ANDROID_HOME
}
if ([string]::IsNullOrWhiteSpace($sdkRoot) -and (Test-Path 'C:\Android\SDK')) {
    $sdkRoot = 'C:\Android\SDK'
}
if ([string]::IsNullOrWhiteSpace($sdkRoot) -or -not (Test-Path $sdkRoot)) {
    throw 'Android SDK root is not configured; aapt/apksigner are required for release verification.'
}

$buildToolsRoot = Join-Path $sdkRoot 'build-tools'
$apksigner = Get-ChildItem $buildToolsRoot -Recurse -Filter 'apksigner.bat' -File -ErrorAction SilentlyContinue |
    Sort-Object FullName -Descending |
    Select-Object -First 1
if (-not $apksigner) {
    throw "apksigner.bat was not found under $buildToolsRoot."
}

$aapt = Get-ChildItem $buildToolsRoot -Recurse -Filter 'aapt.exe' -File -ErrorAction SilentlyContinue |
    Sort-Object FullName -Descending |
    Select-Object -First 1
if (-not $aapt) {
    throw "aapt.exe was not found under $buildToolsRoot."
}

Write-Host '==> Verify APK package/version metadata'
$badgingLines = @(& $aapt.FullName dump badging $sourceApk)
if ($LASTEXITCODE -ne 0) {
    throw "aapt dump badging failed with exit code $LASTEXITCODE."
}
$badging = $badgingLines -join "`n"
$escapedVersionName = [regex]::Escape($versionName)
$escapedVersionCode = [regex]::Escape($expectedVersionCode.ToString())
if ($badging -notmatch "package:\s+name='com\.amurcanov\.tgwsproxy'.*versionCode='$escapedVersionCode'.*versionName='$escapedVersionName'") {
    throw "Release APK metadata mismatch. Expected com.amurcanov.tgwsproxy versionName=$versionName versionCode=$expectedVersionCode."
}
Write-Host "Verified package metadata: versionName=$versionName versionCode=$expectedVersionCode"

Write-Host '==> Verify APK signature'
& $apksigner.FullName verify --verbose --print-certs $sourceApk
if ($LASTEXITCODE -ne 0) {
    throw "APK signature verification failed with exit code $LASTEXITCODE."
}

$artifactPath = Join-Path $dist $artifactName
Copy-Item -Force $sourceApk $artifactPath

$hash = (Get-FileHash -Path $artifactPath -Algorithm SHA256).Hash.ToLowerInvariant()
$checksumPath = "$artifactPath.sha256"
Set-Content -Path $checksumPath -Value "$hash  $artifactName" -Encoding ascii

$files = @(Get-ChildItem $dist -File)
if ($files.Count -ne 2) {
    throw "Expected exactly APK and SHA-256 checksum in dist/, found $($files.Count) files."
}

Write-Host "Release artifacts prepared:"
$files | ForEach-Object { Write-Host " - $($_.FullName)" }
Write-Host "SHA-256: $hash"
