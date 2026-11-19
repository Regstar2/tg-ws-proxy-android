[CmdletBinding()]
param([string]$SdkRoot = 'C:\Android\Sdk')
$ErrorActionPreference = 'Stop'
$root = Split-Path -Parent $PSScriptRoot
$logs = Join-Path $root 'artifacts\issue-162'
New-Item -ItemType Directory -Force -Path $logs | Out-Null
$env:ANDROID_SDK_ROOT = $SdkRoot
$env:ANDROID_HOME = $SdkRoot
$manager = Join-Path $SdkRoot 'cmdline-tools\latest\bin\sdkmanager.bat'
$avdmanager = Join-Path $SdkRoot 'cmdline-tools\latest\bin\avdmanager.bat'
if (-not (Test-Path $manager)) { throw 'Install official Android SDK command-line tools before running this script.' }
foreach ($tool in @('java','go','py')) { Get-Command $tool -ErrorAction Stop | Select-Object Name, Source }
$image = 'system-images;android-30;google_apis;x86'
$imageProperties = Join-Path $SdkRoot 'system-images\android-30\google_apis\x86\source.properties'
if (-not (Test-Path $imageProperties) -or -not (Test-Path (Join-Path $SdkRoot 'emulator\emulator.exe'))) {
    # Dependency download only, no changes to Windows virtualization/security settings.
    $process = Start-Process -FilePath $manager -ArgumentList @('emulator', $image) -PassThru -WindowStyle Hidden -RedirectStandardOutput (Join-Path $logs 'sdk-setup-out.txt') -RedirectStandardError (Join-Path $logs 'sdk-setup-err.txt')
    if (-not $process.WaitForExit(1200000)) { $process.Kill(); throw 'SDK dependency download timed out; see artifacts/issue-162/sdk-setup-*.txt' }
    if ($process.ExitCode -ne 0) { throw 'Official SDK package installation failed' }
}
$name = 'TgWsProxy_Issue162_API30_x86'
$avds = (& (Join-Path $SdkRoot 'emulator\emulator.exe') -list-avds) -join "`n"
if ($avds -notmatch "(?m)^$name\s*$") {
    'no' | & $avdmanager create avd -n $name -k $image -d pixel_2
    # Recent avdmanager may warn about absent devices.xml after creating the AVD.
    $avds = (& (Join-Path $SdkRoot 'emulator\emulator.exe') -list-avds) -join "`n"
    if ($avds -notmatch "(?m)^$name\s*$") { throw 'Could not create isolated test AVD' }
}
& (Join-Path $SdkRoot 'emulator\emulator.exe') -accel-check
if ($LASTEXITCODE -ne 0) { throw 'Windows emulator acceleration is unavailable; no system changes were attempted' }
Write-Host "AVD ready: $name. Run scripts/issue-162-armv7-smoke.ps1 -Build"
