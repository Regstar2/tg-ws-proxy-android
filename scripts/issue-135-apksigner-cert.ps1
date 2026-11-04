# Parse apksigner --print-certs output across Android Build Tools versions.
function Get-ApkSignerCertificateSha256 {
    param(
        [Parameter(Mandatory = $true)]
        [string[]]$ReportLines
    )

    $Digests = @(
        foreach ($Line in $ReportLines) {
            $Match = [regex]::Match(
                [string]$Line,
                'certificate SHA-256 digest:\s*([0-9A-Fa-f:]+)\s*$',
                [System.Text.RegularExpressions.RegexOptions]::IgnoreCase
            )
            if ($Match.Success) {
                $Normalized = $Match.Groups[1].Value.Replace(":", "").ToLowerInvariant()
                if ($Normalized -notmatch '^[0-9a-f]{64}$') {
                    throw "apksigner returned an invalid certificate SHA-256 digest"
                }
                $Normalized
            }
        }
    )

    $DistinctDigests = @($Digests | Sort-Object -Unique)
    if ($DistinctDigests.Count -ne 1) {
        throw "Expected one unique APK signing certificate SHA-256 digest; found $($DistinctDigests.Count). Check apksigner output format and signing configuration."
    }
    return $DistinctDigests[0]
}
