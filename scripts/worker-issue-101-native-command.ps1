# Shared native-command wrapper for the issue #101 ADB smoke.
# Windows PowerShell 5.1 turns native stderr into an ErrorRecord when stderr is
# merged into the pipeline. Nonzero process exit codes, not stderr text, are
# the source of truth for native command success.
function Invoke-WorkerSmokeNativeCommand {
    [CmdletBinding()]
    param(
        [Parameter(Mandatory = $true)][string]$FilePath,
        [string[]]$Arguments = @()
    )
    $savedPreference = $ErrorActionPreference
    try {
        $ErrorActionPreference = 'Continue'
        $output = @(& $FilePath @Arguments 2>&1 | ForEach-Object { $_.ToString() })
        $exitCode = $LASTEXITCODE
    }
    finally {
        $ErrorActionPreference = $savedPreference
    }
    if ($exitCode -ne 0) {
        throw "Native command failed (exit $exitCode): $FilePath $($Arguments -join ' ') | $($output -join ' ')"
    }
    return $output
}
