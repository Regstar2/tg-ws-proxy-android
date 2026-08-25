# Audit debug APK for legacy icon resources.
param(
    [string]$ApkPath = "$PSScriptRoot\..\app\build\outputs\apk\debug\app-debug.apk"
)

$ErrorActionPreference = "Stop"
if (-not (Test-Path $ApkPath)) {
    Write-Error "APK not found: $ApkPath. Run: .\gradlew assembleDebug"
}

Write-Host "=== aapt badging (application icon) ===" -ForegroundColor Cyan
$sdk = $env:ANDROID_HOME
if (-not $sdk) { $sdk = "$env:LOCALAPPDATA\Android\Sdk" }
$aapt = Get-ChildItem -Path "$sdk\build-tools" -Recurse -Filter "aapt.exe" -ErrorAction SilentlyContinue |
    Sort-Object FullName -Descending | Select-Object -First 1
if ($aapt) {
    & $aapt.FullName dump badging $ApkPath | Select-String "application-icon|icon|versionCode"
} else {
    Write-Warning "aapt not found; skip badging"
}

# Keep temporary extraction under the repository build tree instead of $env:TEMP.
# Some Windows profiles with non-ASCII user names expose an invalid 8.3 TEMP path
# (for example C:\Users\XXXX~1), which breaks Remove-Item in Windows PowerShell 5.1.
$checkDir = [System.IO.Path]::GetFullPath(
    (Join-Path $PSScriptRoot "..\app\build\tmp\tgwsproxy_apk_check")
)
if (Test-Path -LiteralPath $checkDir) {
    Remove-Item -LiteralPath $checkDir -Recurse -Force
}
New-Item -ItemType Directory -Path $checkDir -Force | Out-Null
Copy-Item -LiteralPath $ApkPath -Destination (Join-Path $checkDir "app.zip")
Expand-Archive -LiteralPath (Join-Path $checkDir "app.zip") -DestinationPath (Join-Path $checkDir "unzipped") -Force

Write-Host "`n=== Icon-like files in APK ===" -ForegroundColor Cyan
$patterns = @(
    "*ic_launcher*",
    "*notification*",
    "*ic_stat*",
    "*ic_stop*",
    "*tgwsproxy*"
)
$hits = Get-ChildItem (Join-Path $checkDir "unzipped") -Recurse -File |
    Where-Object {
        $n = $_.Name
        $patterns | ForEach-Object { if ($n -like $_) { return $true } }
        $false
    } |
    Sort-Object FullName

$legacy = $hits | Where-Object {
    $_.Name -match '^ic_launcher\.(png|xml)$' -or
    $_.Name -match '^ic_launcher_round\.(png|xml)$' -or
    $_.Name -eq 'ic_launcher_foreground.png' -or
    $_.Name -eq 'ic_notification.png' -or
    $_.Name -eq 'notification_app_icon.png' -or
    $_.Name -match 'ic_stat_connected' -or
    $_.Name -match 'ic_stop\.png'
}

$hits | ForEach-Object { $_.FullName.Replace((Join-Path $checkDir "unzipped"), "") }

if ($legacy) {
    Write-Host "`nLEGACY ICON FILES STILL IN APK:" -ForegroundColor Red
    $legacy | ForEach-Object { Write-Host "  $($_.Name)" -ForegroundColor Red }
    exit 1
}

Write-Host "`nNo legacy icon filenames found in APK." -ForegroundColor Green
