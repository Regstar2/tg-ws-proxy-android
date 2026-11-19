[CmdletBinding()]
param(
    [Parameter(Mandatory=$true)][string]$Serial,
    [ValidateSet('armeabi-v7a','arm64-v8a')][string]$Abi = 'armeabi-v7a'
)
$ErrorActionPreference = 'Stop'
$root = Split-Path -Parent $PSScriptRoot
$sdk = $env:ANDROID_SDK_ROOT
if (-not $sdk) { $sdk = 'C:\Android\Sdk' }
$ndk = $env:ANDROID_NDK_ROOT
if (-not $ndk) { $ndk = (Get-ChildItem (Join-Path $sdk 'ndk') -Directory | Sort-Object { [version]$_.Name } -Descending | Select-Object -First 1).FullName }
$adb = Join-Path $sdk 'platform-tools\adb.exe'
$deviceAbis = (& $adb -s $Serial shell getprop ro.product.cpu.abilist) -join ''
if ($LASTEXITCODE -ne 0 -or $Abi -notin ($deviceAbis.Trim() -split ',')) { throw 'Device does not support requested ABI' }
$directory = Join-Path $root "artifacts\issue-162\go-$Abi"
New-Item -ItemType Directory -Path $directory -Force | Out-Null
$saved = @{}
foreach ($name in @('GOOS','GOARCH','GOARM','CGO_ENABLED','CC')) { $saved[$name] = [Environment]::GetEnvironmentVariable($name) }
try {
    $env:GOOS = 'android'; $env:CGO_ENABLED = '1'
    $arm32 = $Abi -eq 'armeabi-v7a'
    $env:GOARCH = if ($arm32) { 'arm' } else { 'arm64' }
    $env:GOARM = if ($arm32) { '7' } else { $null }
    $triple = if ($arm32) { 'armv7a-linux-androideabi' } else { 'aarch64-linux-android' }
    $env:CC = Join-Path $ndk "toolchains\llvm\prebuilt\windows-x86_64\bin\${triple}26-clang.cmd"
    Push-Location (Join-Path $root 'native\tgwsproxy')
    try {
        foreach ($package in @('.', './mtproxyfrontend', './tgwsroute')) {
            $name = if ($package -eq '.') { 'runtime' } else { $package.Substring(2) }
            $binary = Join-Path $directory "$name.test"
            & go test -c -o $binary $package
            if ($LASTEXITCODE -ne 0) { throw "Go test compilation failed: $package" }
            $remote = "/data/local/tmp/issue162-$name-$Abi.test"
            & $adb -s $Serial push $binary $remote
            if ($LASTEXITCODE -ne 0) { throw 'ADB push failed' }
            & $adb -s $Serial shell chmod 700 $remote
            if ($LASTEXITCODE -ne 0) { throw 'ADB chmod failed' }
            $output = Join-Path $directory "$name.txt"
            $stderr = Join-Path $directory "$name-stderr.txt"
            $process = Start-Process -FilePath $adb -ArgumentList @('-s',$Serial,'shell',"TMPDIR=/data/local/tmp $remote -test.v -test.timeout=120s") -PassThru -WindowStyle Hidden -RedirectStandardOutput $output -RedirectStandardError $stderr
            if (-not $process.WaitForExit(150000)) { $process.Kill(); throw "Native tests timed out: $package" }
            if ($process.ExitCode -ne 0 -or -not (Select-String -LiteralPath $output -Pattern '^PASS$' -Quiet)) { throw "Native tests failed: $output" }
            Write-Host "PASS $Abi $package on $Serial; $output"
        }
    } finally { Pop-Location }
} finally {
    foreach ($name in $saved.Keys) { [Environment]::SetEnvironmentVariable($name, $saved[$name], 'Process') }
}
