[CmdletBinding()]
param(
    [string]$ApkPath = (Join-Path (Split-Path -Parent (Split-Path -Parent $PSScriptRoot)) 'app\build\outputs\apk\debug\app-debug.apk'),
    [ValidateSet('Debug', 'Production', 'Either')]
    [string]$ExpectedVariant = 'Debug'
)

Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'

if (-not (Test-Path -LiteralPath $ApkPath -PathType Leaf)) {
    throw "APK not found: $ApkPath"
}

$sdk = if ($env:ANDROID_HOME) {
    $env:ANDROID_HOME
} elseif ($env:ANDROID_SDK_ROOT) {
    $env:ANDROID_SDK_ROOT
} else {
    Join-Path $env:LOCALAPPDATA 'Android\Sdk'
}

$aapt = Get-ChildItem -Path (Join-Path $sdk 'build-tools') -Filter 'aapt.exe' -Recurse -ErrorAction SilentlyContinue |
    Sort-Object FullName -Descending |
    Select-Object -First 1

if (-not $aapt) {
    throw 'Android SDK aapt.exe not found; cannot verify packaged application identity.'
}

$badging = @(& $aapt.FullName dump badging $ApkPath)
if ($LASTEXITCODE -ne 0) {
    throw 'aapt dump badging failed.'
}

$baseApplicationId = 'io.github.regstar2.tgwsproxy'
$basePattern = [regex]::Escape($baseApplicationId)

$expectedApplicationId = switch ($ExpectedVariant) {
    'Debug'      { "$baseApplicationId.debug" }
    'Production' { $baseApplicationId }
    'Either'     { $null }
}

if ($ExpectedVariant -eq 'Either') {
    $packagePattern = "^package:\s+name='(?<id>$basePattern(?:\.debug)?)'"
} else {
    $packagePattern = "^package:\s+name='$([regex]::Escape($expectedApplicationId))'"
}

$packageLine = $badging |
    Where-Object { $_ -match $packagePattern } |
    Select-Object -First 1

if (-not $packageLine) {
    $expectedText = if ($ExpectedVariant -eq 'Either') {
        "$baseApplicationId or $baseApplicationId.debug"
    } else {
        $expectedApplicationId
    }
    throw "Unexpected APK applicationId. Expected: $expectedText"
}

if ($ExpectedVariant -eq 'Either') {
    $packageMatch = [regex]::Match([string]$packageLine, $packagePattern)
    $applicationId = $packageMatch.Groups['id'].Value
} else {
    $applicationId = $expectedApplicationId
}

if (-not ($badging | Where-Object {
    $_ -match "^launchable-activity: name='com\.amurcanov\.tgwsproxy\.MainActivity'"
})) {
    throw 'The original Kotlin MainActivity is not launchable under the packaged applicationId.'
}

$manifest = @(& $aapt.FullName dump xmltree $ApkPath AndroidManifest.xml)
if ($LASTEXITCODE -ne 0) {
    throw 'aapt dump xmltree failed.'
}

$expectedFileProviderAuthority = "$applicationId.fileprovider"
if (-not ($manifest | Where-Object {
    $_ -match [regex]::Escape($expectedFileProviderAuthority)
})) {
    throw "FileProvider authority does not match the APK applicationId ($applicationId)."
}

Write-Host "PASS: applicationId = $applicationId"
Write-Host "PASS: launcher activity = com.amurcanov.tgwsproxy.MainActivity"
Write-Host "PASS: FileProvider authority = $expectedFileProviderAuthority"