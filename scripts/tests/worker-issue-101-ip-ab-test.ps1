[CmdletBinding()]
param()

Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'

$runnerPath = Join-Path (Split-Path -Parent $PSScriptRoot) 'worker-issue-101-ip-ab.ps1'
$tokens = $null
$parseErrors = $null
$ast = [System.Management.Automation.Language.Parser]::ParseFile(
    $runnerPath, [ref]$tokens, [ref]$parseErrors
)
if ($parseErrors) { throw "Invalid runner PowerShell syntax: $($parseErrors -join '; ')" }
$source = Get-Content -LiteralPath $runnerPath -Raw
if ([regex]::Matches($source, [regex]::Escape('[CmdletBinding()]')).Count -ne 1) {
    throw 'Worker IP A/B script has duplicate entrypoint'
}
if ($source -notmatch '\[string\]\$IPv6Address') {
    throw 'IPv6 address override missing'
}
if (-not $source.Contains('[string[]]$HelloProfiles') -or
    -not $source.Contains('foreach ($helloMode in $HelloProfiles)') -or
    -not $source.Contains('''-hello'', $helloMode')) {
    throw 'Compact TLS Hello profile matrix is not passed to Android probe'
}

$functionAst = $ast.Find({
    param($node)
    $node -is [System.Management.Automation.Language.FunctionDefinitionAst] -and
    $node.Name -eq 'Get-WorkerProbeSummary'
}, $true)
if ($null -eq $functionAst) { throw 'Missing Get-WorkerProbeSummary' }
. ([scriptblock]::Create($functionAst.Extent.Text))

$failed = '{"network":"tcp4","phase":"wait_worker_upstream_reply","error":"i/o timeout"}' | ConvertFrom-Json
$f = Get-WorkerProbeSummary -Result $failed
if ($f.status -ne 'FAIL' -or $f.response_bytes -ne 0 -or $f.error -ne 'i/o timeout') {
    throw 'Sparse JSON failure was not handled safely'
}

$good = '{"network":"tcp6","phase":"upstream_reply_received","response_bytes":512,"pong_received":true}' | ConvertFrom-Json
$g = Get-WorkerProbeSummary -Result $good
if ($g.status -ne 'PASS' -or $g.response_bytes -ne 512 -or
    -not $g.pong_received -or $g.error -ne '') {
    throw 'Sparse JSON success was not handled safely'
}

$empty = '{}' | ConvertFrom-Json
$e = Get-WorkerProbeSummary -Result $empty
if ($e.status -ne 'FAIL' -or $e.response_bytes -ne 0 -or $e.phase -ne 'unknown') {
    throw 'Empty JSON must result in FAIL, not exception or PASS'
}

Write-Host 'Worker IPv4/IPv6 PowerShell sparse JSON regression: PASS'
