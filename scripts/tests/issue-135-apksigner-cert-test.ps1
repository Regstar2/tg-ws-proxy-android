Set-StrictMode -Version Latest
$ErrorActionPreference = "Stop"
. (Join-Path (Split-Path -Parent $PSScriptRoot) "issue-135-apksigner-cert.ps1")

$Digest = ("0123456789abcdef" * 4)
$Fixtures = @(
    "Signer #1 certificate SHA-256 digest: $Digest",
    "V2 Signer: certificate SHA-256 digest: $Digest",
    "V3.0 Signer: certificate SHA-256 digest: $Digest",
    "Signer (minSdkVersion=24, maxSdkVersion=32) certificate SHA-256 digest: $Digest",
    "  V3.1 Signer: certificate SHA-256 digest: $Digest  "
)
foreach ($Fixture in $Fixtures) {
    $Actual = Get-ApkSignerCertificateSha256 -ReportLines @($Fixture)
    if ($Actual -cne $Digest) {
        throw "Failed to parse signing certificate from: $Fixture"
    }
}
$TwoSchemes = @($Fixtures[1], $Fixtures[2])
if ((Get-ApkSignerCertificateSha256 -ReportLines $TwoSchemes) -cne $Digest) {
    throw "Duplicate certificate from different signature schemes must be accepted"
}

foreach ($Invalid in @(
    @("Signer #1 certificate SHA-256 digest: 1234"),
    @("No signing certificate was printed"),
    @("Signer #1 certificate SHA-256 digest: $Digest",
      "V3.0 Signer: certificate SHA-256 digest: $("abcdef0123456789" * 4)")
)) {
    $Rejected = $false
    try {
        Get-ApkSignerCertificateSha256 -ReportLines $Invalid | Out-Null
    } catch {
        $Rejected = $true
    }
    if (-not $Rejected) {
        throw "Unexpectedly accepted missing, invalid or conflicting certificate digests"
    }
}

Write-Host "Issue #135 apksigner certificate parser: PASS"
