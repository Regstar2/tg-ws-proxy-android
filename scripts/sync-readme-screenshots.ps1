# Copies images from readme_images/ to docs/assets/screenshots/
$ErrorActionPreference = "Stop"
$repoRoot = Split-Path -Parent $PSScriptRoot
$src = Join-Path $repoRoot "readme_images"
$dest = Join-Path $repoRoot "docs\assets\screenshots"

$map = @{
    "main_window.jpg"     = "screenshot-main.jpg"
    "settings_mobile.jpg" = "screenshot-settings-mobile.jpg"
    "settings_wifi.jpg"   = "screenshot-settings-wifi.jpg"
}

if (-not (Test-Path $src)) {
    Write-Host "No readme_images folder at $src"
    exit 0
}

New-Item -ItemType Directory -Force -Path $dest | Out-Null
$copied = $false
foreach ($entry in $map.GetEnumerator()) {
    $from = Join-Path $src $entry.Key
    if (Test-Path $from) {
        Copy-Item $from (Join-Path $dest $entry.Value) -Force
        Write-Host "Copied $($entry.Key) -> docs\assets\screenshots\$($entry.Value)"
        $copied = $true
    }
}

if (-not $copied) {
    Write-Host "readme_images is empty — add screenshots first."
}
