$ErrorActionPreference = 'Stop'

. (Join-Path $PSScriptRoot '..\release-asset-manifest.ps1')

$cases = @(
    [pscustomobject]@{
        Tag = 'v1.11.9'
        ExpectedAbis = @('arm64-v8a')
        ExpectedAssetCount = 2
    }
    [pscustomobject]@{
        Tag = 'v1.11.10'
        ExpectedAbis = @('armeabi-v7a', 'arm64-v8a', 'universal')
        ExpectedAssetCount = 6
    }
    [pscustomobject]@{
        Tag = 'v1.11.11'
        ExpectedAbis = @('armeabi-v7a', 'arm64-v8a', 'x86', 'x86_64', 'universal')
        ExpectedAssetCount = 10
    }
    [pscustomobject]@{
        Tag = 'v1.12.0'
        ExpectedAbis = @('armeabi-v7a', 'arm64-v8a', 'x86', 'x86_64', 'universal')
        ExpectedAssetCount = 10
    }
)

foreach ($case in $cases) {
    $actualAbis = @(Get-ExpectedReleaseAbis -Tag $case.Tag)
    $expectedAbis = @($case.ExpectedAbis)
    if (($actualAbis -join '|') -cne ($expectedAbis -join '|')) {
        throw "$($case.Tag): expected ABIs [$($expectedAbis -join ', ')], got [$($actualAbis -join ', ')]"
    }

    $actualNames = @(Get-ExpectedReleaseAssetNames -Tag $case.Tag)
    $expectedNames = @(
        foreach ($abi in $expectedAbis) {
            "TgWsProxy-Android-$($case.Tag)-$abi.apk"
            "TgWsProxy-Android-$($case.Tag)-$abi.apk.sha256"
        }
    ) | Sort-Object

    if ($actualNames.Count -ne $case.ExpectedAssetCount -or
        ($actualNames -join '|') -cne ($expectedNames -join '|')) {
        throw "$($case.Tag): expected $($case.ExpectedAssetCount) exact assets, got $($actualNames.Count)"
    }

    Write-Host "PASS $($case.Tag): $($actualNames.Count) assets"
}

$invalidTagRejected = $false
try {
    Get-ExpectedReleaseAssetNames -Tag '1.11.10' | Out-Null
}
catch {
    $invalidTagRejected = $true
}
if (-not $invalidTagRejected) {
    throw 'Invalid tag without the v-prefix was accepted.'
}

Write-Host 'Release asset manifest compatibility tests passed.'
