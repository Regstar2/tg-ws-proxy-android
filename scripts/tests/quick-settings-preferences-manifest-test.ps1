[CmdletBinding()]
param(
    [string]$IntermediatesPath = (Join-Path (Split-Path -Parent (Split-Path -Parent $PSScriptRoot)) 'app/build/intermediates')
)

Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'

if (-not (Test-Path -LiteralPath $IntermediatesPath -PathType Container)) {
    throw "Build intermediates directory not found: $IntermediatesPath"
}

$mergedManifests = @(Get-ChildItem -LiteralPath $IntermediatesPath -Recurse -File -Filter AndroidManifest.xml |
    Where-Object { $_.FullName -match '[\\/](merged_manifest|merged_manifests)[\\/]debug[\\/]' } |
    Sort-Object LastWriteTimeUtc -Descending)
if ($mergedManifests.Count -eq 0) {
    throw 'No merged debug AndroidManifest.xml found. Run .\gradlew.bat :app:processDebugMainManifest first.'
}

$mergedPath = $mergedManifests[0].FullName
[xml]$manifest = Get-Content -LiteralPath $mergedPath -Raw
$androidNamespace = 'http://schemas.android.com/apk/res/android'
$namespaces = New-Object System.Xml.XmlNamespaceManager($manifest.NameTable)
$namespaces.AddNamespace('android', $androidNamespace)

$mainActivity = $manifest.SelectSingleNode(
    "/manifest/application/activity[@android:name='com.amurcanov.tgwsproxy.MainActivity']", $namespaces
)
if ($null -eq $mainActivity) {
    throw 'MainActivity is absent from the merged manifest.'
}
if ($mainActivity.GetAttribute('exported', $androidNamespace) -ne 'true' -or
    $mainActivity.GetAttribute('launchMode', $androidNamespace) -ne 'singleTop') {
    throw 'MainActivity must remain exported=true and singleTop.'
}

$launcherFilters = @($mainActivity.SelectNodes(
    "./intent-filter[action[@android:name='android.intent.action.MAIN'] and category[@android:name='android.intent.category.LAUNCHER']]", $namespaces
))
if ($launcherFilters.Count -ne 1) {
    throw 'MainActivity must retain exactly one MAIN/LAUNCHER intent-filter.'
}

$preferenceFilters = @($mainActivity.SelectNodes(
    "./intent-filter[action[@android:name='android.service.quicksettings.action.QS_TILE_PREFERENCES']]", $namespaces
))
if ($preferenceFilters.Count -ne 1) {
    throw 'MainActivity must declare exactly one QS_TILE_PREFERENCES intent-filter.'
}
$preferenceActions = @($preferenceFilters[0].SelectNodes('./action', $namespaces))
$preferenceCategories = @($preferenceFilters[0].SelectNodes('./category', $namespaces))
if ($preferenceActions.Count -ne 1 -or $preferenceCategories.Count -ne 1 -or
    $preferenceCategories[0].GetAttribute('name', $androidNamespace) -ne 'android.intent.category.DEFAULT') {
    throw 'QS_TILE_PREFERENCES must use a separate filter with only the action and DEFAULT category.'
}

$tileService = $manifest.SelectSingleNode(
    "/manifest/application/service[@android:name='com.amurcanov.tgwsproxy.ProxyQuickSettingsTileService']", $namespaces
)
if ($null -eq $tileService -or @($tileService.SelectNodes(
    "./intent-filter/action[@android:name='android.service.quicksettings.action.QS_TILE']", $namespaces
)).Count -ne 1) {
    throw 'The existing Quick Settings tile service intent-filter must remain registered.'
}

Write-Host "PASS: merged debug manifest provides separate Quick Settings preferences and launcher filters."
Write-Host "Merged manifest: $mergedPath"
