[CmdletBinding()]
param(
    [string]$ApkPath = (Join-Path (Split-Path -Parent (Split-Path -Parent $PSScriptRoot)) 'app\build\outputs\apk\debug\app-universal-debug.apk'),
    [ValidateSet('Debug', 'Production', 'IsolatedAbiSmoke', 'Either')]
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
$expectedIds = switch ($ExpectedVariant) {
    'Debug'           { @("$baseApplicationId.debug") }
    'Production'      { @($baseApplicationId) }
    'IsolatedAbiSmoke' { @("$baseApplicationId.abismoke") }
    'Either'          { @($baseApplicationId, "$baseApplicationId.debug", "$baseApplicationId.abismoke") }
}

$packageLine = $badging |
    Where-Object { $_ -match "^package:\s+name='(?<id>[^']+)'" } |
    Select-Object -First 1

if (-not $packageLine) {
    throw 'APK package name was not found.'
}

$packageMatch = [regex]::Match([string]$packageLine, "^package:\s+name='(?<id>[^']+)'")
$applicationId = $packageMatch.Groups['id'].Value

if ($applicationId -notin $expectedIds) {
    throw "Unexpected applicationId '$applicationId'. Expected: $($expectedIds -join ', ')"
}

if (-not ($badging | Where-Object {
    $_ -match "^launchable-activity: name='com\.amurcanov\.tgwsproxy\.MainActivity'"
})) {
    throw 'The original Kotlin MainActivity is not launchable.'
}

$manifest = @(& $aapt.FullName dump xmltree $ApkPath AndroidManifest.xml)
if ($LASTEXITCODE -ne 0) {
    throw 'aapt dump xmltree failed.'
}

$expectedAuthority = "$applicationId.fileprovider"
if (-not ($manifest | Where-Object {
    $_ -match [regex]::Escape($expectedAuthority)
})) {
    throw "FileProvider authority does not match applicationId '$applicationId'."
}

Write-Host "PASS: applicationId = $applicationId"
Write-Host 'PASS: launcher activity = com.amurcanov.tgwsproxy.MainActivity'
Write-Host "PASS: FileProvider authority = $expectedAuthority"