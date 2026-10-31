[CmdletBinding()]
param(
    [string]$ApkPath = (Join-Path (Split-Path -Parent (Split-Path -Parent $PSScriptRoot)) 'app\build\outputs\apk\debug\app-debug.apk')
)

Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'

if (-not (Test-Path -LiteralPath $ApkPath -PathType Leaf)) {
    throw "Debug APK not found: $ApkPath"
}

$sdk = if ($env:ANDROID_HOME) { $env:ANDROID_HOME } elseif ($env:ANDROID_SDK_ROOT) { $env:ANDROID_SDK_ROOT } else { Join-Path $env:LOCALAPPDATA 'Android\Sdk' }
$aapt = Get-ChildItem -Path (Join-Path $sdk 'build-tools') -Filter 'aapt.exe' -Recurse -ErrorAction SilentlyContinue |
    Sort-Object FullName -Descending | Select-Object -First 1
if (-not $aapt) { throw 'Android SDK aapt.exe not found; cannot verify packaged application identity.' }

$badging = @(& $aapt.FullName dump badging $ApkPath)
if ($LASTEXITCODE -ne 0) { throw 'aapt dump badging failed.' }
if (-not ($badging | Where-Object { $_ -match "^package: name='io\.github\.regstar2\.tgwsproxy'" })) {
    throw 'Unexpected APK applicationId: expected io.github.regstar2.tgwsproxy.'
}
if (-not ($badging | Where-Object { $_ -match "^launchable-activity: name='com\.amurcanov\.tgwsproxy\.MainActivity'" })) {
    throw 'The original Kotlin MainActivity is not launchable under the new applicationId.'
}

$manifest = @(& $aapt.FullName dump xmltree $ApkPath AndroidManifest.xml)
if ($LASTEXITCODE -ne 0) { throw 'aapt dump xmltree failed.' }
if (-not ($manifest | Where-Object { $_ -match 'io\.github\.regstar2\.tgwsproxy\.fileprovider' })) {
    throw 'FileProvider authority does not match the new applicationId.'
}

Write-Host 'Packaged applicationId, launcher activity and FileProvider authority verified.'
