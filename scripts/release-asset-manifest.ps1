# Returns the APK variants published by each historical release format.
# v1.11.9 and earlier: ARM64 only (APK + SHA-256).
# v1.11.10: ARMv7, ARM64 and Universal (three APK/checksum pairs).
# Versions after v1.11.10: four ABIs plus Universal (five APK/checksum pairs).
function Get-ExpectedReleaseAbis {
    [CmdletBinding()]
    param(
        [Parameter(Mandatory = $true)]
        [string]$Tag
    )

    $tagMatch = [regex]::Match(
        $Tag,
        '^v(?<major>\d+)\.(?<minor>\d+)\.(?<patch>\d+)(?:[-.][0-9A-Za-z.-]+)?$'
    )
    if (-not $tagMatch.Success) {
        throw "Invalid release tag for asset manifest: $Tag"
    }

    $versionText = '{0}.{1}.{2}' -f
        $tagMatch.Groups['major'].Value,
        $tagMatch.Groups['minor'].Value,
        $tagMatch.Groups['patch'].Value
    $version = [version]::Parse($versionText)
    $armv7Release = [version]'1.11.10'

    if ($version -lt $armv7Release) {
        return @('arm64-v8a')
    }

    if ($version -eq $armv7Release) {
        return @('armeabi-v7a', 'arm64-v8a', 'universal')
    }

    return @('armeabi-v7a', 'arm64-v8a', 'x86', 'x86_64', 'universal')
}

function Get-ExpectedReleaseAssetNames {
    [CmdletBinding()]
    param(
        [Parameter(Mandatory = $true)]
        [string]$Tag
    )

    $abis = @(Get-ExpectedReleaseAbis -Tag $Tag)
    $names = @(
        foreach ($abi in $abis) {
            "TgWsProxy-Android-$Tag-$abi.apk"
            "TgWsProxy-Android-$Tag-$abi.apk.sha256"
        }
    )

    return @($names | Sort-Object)
}
