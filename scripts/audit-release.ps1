[CmdletBinding()]
param(
    [string]$ExpectedVersion = '1.10.13',
    [int]$ExpectedVersionCode = 51
)

Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'

$root = Split-Path -Parent $PSScriptRoot
Set-Location $root

$failures = New-Object System.Collections.Generic.List[string]
$warnings = New-Object System.Collections.Generic.List[string]

function Add-Failure([string]$Message) {
    $script:failures.Add($Message)
}

function Assert-Match {
    param(
        [Parameter(Mandatory = $true)][string]$Path,
        [Parameter(Mandatory = $true)][string]$Pattern,
        [Parameter(Mandatory = $true)][string]$Description
    )

    if (-not (Test-Path -LiteralPath $Path)) {
        Add-Failure "${Description}: missing file $Path"
        return
    }

    $text = Get-Content -LiteralPath $Path -Raw -Encoding UTF8
    if ($text -notmatch $Pattern) {
        Add-Failure "${Description}: expected pattern was not found in $Path"
    }
}

Write-Host '==> Release metadata consistency'
$gradlePath = Join-Path $root 'app\build.gradle.kts'
$gradleText = Get-Content -LiteralPath $gradlePath -Raw -Encoding UTF8
$versionNameMatch = [regex]::Match($gradleText, 'val\s+releaseVersionName\s*=\s*"([^"]+)"')
$versionCodeMatch = [regex]::Match($gradleText, 'val\s+releaseVersionCode\s*=\s*(\d+)')

if (-not $versionNameMatch.Success) {
    Add-Failure 'Could not read releaseVersionName from app/build.gradle.kts.'
} elseif ($versionNameMatch.Groups[1].Value -ne $ExpectedVersion) {
    Add-Failure "releaseVersionName is '$($versionNameMatch.Groups[1].Value)', expected '$ExpectedVersion'."
}

if (-not $versionCodeMatch.Success) {
    Add-Failure 'Could not read releaseVersionCode from app/build.gradle.kts.'
} elseif ([int]$versionCodeMatch.Groups[1].Value -ne $ExpectedVersionCode) {
    Add-Failure "releaseVersionCode is '$($versionCodeMatch.Groups[1].Value)', expected '$ExpectedVersionCode'."
}

$escapedVersion = [regex]::Escape($ExpectedVersion)
Assert-Match -Path (Join-Path $root 'README.md') -Pattern "`?$escapedVersion`?\s+\(`?versionCode\s+$ExpectedVersionCode`?\)" -Description 'Russian README source version'
Assert-Match -Path (Join-Path $root 'README_EN.md') -Pattern "`?$escapedVersion`?\s+\(`?versionCode\s+$ExpectedVersionCode`?\)" -Description 'English README source version'
Assert-Match -Path (Join-Path $root 'CHANGELOG.md') -Pattern "(?m)^##[^\r\n]*$escapedVersion\b" -Description 'CHANGELOG version section'
Assert-Match -Path (Join-Path $root 'docs\releases\RELEASE_NOTES_v1.10.13.md') -Pattern "versionName.*$escapedVersion" -Description 'Release notes versionName'

foreach ($readme in @('README.md', 'README_EN.md')) {
    $text = Get-Content -LiteralPath (Join-Path $root $readme) -Raw -Encoding UTF8
    if ($text -match 'Regstar2/TgWsProxy_Android') {
        Add-Failure "$readme still contains the obsolete Regstar2/TgWsProxy_Android repository URL."
    }
}

Write-Host '==> RU/EN Android resource parity'
function Get-ResourceNames([string]$Directory) {
    $set = New-Object 'System.Collections.Generic.HashSet[string]' ([System.StringComparer]::Ordinal)
    if (-not (Test-Path -LiteralPath $Directory)) {
        return $set
    }

    Get-ChildItem -LiteralPath $Directory -Filter '*.xml' -File | ForEach-Object {
        $text = Get-Content -LiteralPath $_.FullName -Raw -Encoding UTF8
        [regex]::Matches($text, '<(?:string|string-array|plurals)\b[^>]*\bname="([^"]+)"') | ForEach-Object {
            [void]$set.Add($_.Groups[1].Value)
        }
    }

    return $set
}

$defaultResources = Get-ResourceNames (Join-Path $root 'app\src\main\res\values')
$russianResources = Get-ResourceNames (Join-Path $root 'app\src\main\res\values-ru')

$missingRu = @($defaultResources | Where-Object { -not $russianResources.Contains($_) } | Sort-Object)
$extraRu = @($russianResources | Where-Object { -not $defaultResources.Contains($_) } | Sort-Object)

if ($missingRu.Count -gt 0) {
    Add-Failure ('Missing RU resources: ' + ($missingRu -join ', '))
}
if ($extraRu.Count -gt 0) {
    Add-Failure ('RU-only resources without default English fallback: ' + ($extraRu -join ', '))
}

Write-Host "Default resources: $($defaultResources.Count); RU resources: $($russianResources.Count)"

Write-Host '==> Tracked private/local/sensitive files'
$tracked = @(& git ls-files)
if ($LASTEXITCODE -ne 0) {
    throw 'git ls-files failed.'
}

$forbiddenExact = @(
    'AGENTS.md',
    'release-signing.env',
    'debug.keystore',
    'local.properties'
)
$forbiddenPrefixes = @(
    '.project-rules/',
    'docs/ai-prompts/',
    'docs/private/',
    '.cursor/',
    '.codex/',
    '.claude/',
    '.ai/',
    'secrets/',
    'runtime-logs/'
)
$sensitiveExtensions = @('.jks', '.keystore', '.p12', '.pfx', '.pem', '.key', '.log')

foreach ($path in $tracked) {
    $normalized = $path.Replace('\', '/')

    if ($forbiddenExact -contains $normalized) {
        Add-Failure "Forbidden tracked local/private file: $normalized"
    }

    foreach ($prefix in $forbiddenPrefixes) {
        if ($normalized.StartsWith($prefix, [System.StringComparison]::OrdinalIgnoreCase)) {
            Add-Failure "Forbidden tracked local/private path: $normalized"
        }
    }

    $extension = [System.IO.Path]::GetExtension($normalized).ToLowerInvariant()
    if ($sensitiveExtensions -contains $extension) {
        Add-Failure "Sensitive file extension is tracked: $normalized"
    }
    if ($normalized -match '(^|/)\.env($|\.)' -and $normalized -notmatch '\.env\.example$') {
        Add-Failure "Environment file is tracked: $normalized"
    }
    if ($normalized -match '(^|/)installed-v.*\.apk$') {
        Add-Failure "Locally installed APK copy is tracked: $normalized"
    }
}

Write-Host '==> Secret signature scan'
$tokenPatterns = @(
    ('g' + 'hp_[A-Za-z0-9]{20,}'),
    ('github_' + 'pat_[A-Za-z0-9_]{20,}'),
    ('-----BEGIN ' + '(RSA |EC |OPENSSH )?PRIVATE KEY-----')
)

foreach ($pattern in $tokenPatterns) {
    $matches = @(& git grep -I -n -E -- $pattern 2>$null)
    if ($LASTEXITCODE -eq 0 -and $matches.Count -gt 0) {
        Add-Failure ('Potential credential/private-key material found: ' + ($matches -join '; '))
    }
}

Write-Host '==> Hardcoded user-facing string heuristic'
$uiPatterns = @(
    '(^|[^[:alnum:]_])Text[[:space:]]*\([[:space:]]*"[^"\n]+"',
    'contentDescription[[:space:]]*=[[:space:]]*"[^"\n]+"',
    'Toast\.makeText\([^,]+,[[:space:]]*"[^"\n]+"'
)

foreach ($pattern in $uiPatterns) {
    $matches = @(& git grep -I -n -E -- $pattern -- ':(glob)app/src/main/java/**/*.kt' 2>$null)
    if ($LASTEXITCODE -eq 0 -and $matches.Count -gt 0) {
        foreach ($match in $matches) {
            $warnings.Add("Review possible hardcoded UI string: $match")
        }
    }
}

Write-Host '==> Required .gitignore protections'
$gitignore = Get-Content -LiteralPath (Join-Path $root '.gitignore') -Raw -Encoding UTF8
$requiredIgnoreEntries = @(
    '/AGENTS.md',
    '/.project-rules/',
    '/docs/ai-prompts/',
    '/docs/private/',
    '/.cursor/',
    '/.codex/',
    '/.claude/',
    '/.ai/',
    '*.jks',
    '*.keystore',
    'release-signing.env',
    '.env',
    '*.log',
    'dist/'
)

foreach ($entry in $requiredIgnoreEntries) {
    if (-not $gitignore.Contains($entry)) {
        Add-Failure ".gitignore is missing required protection: $entry"
    }
}

if ($warnings.Count -gt 0) {
    Write-Host "`nRelease audit warnings:"
    $warnings | Sort-Object -Unique | ForEach-Object { Write-Warning $_ }
}

if ($failures.Count -gt 0) {
    Write-Host "`nRelease audit FAILED:" -ForegroundColor Red
    $failures | Sort-Object -Unique | ForEach-Object {
        Write-Host " - $_" -ForegroundColor Red
    }
    exit 1
}

Write-Host "`nRelease audit passed for v$ExpectedVersion (versionCode $ExpectedVersionCode)." -ForegroundColor Green
exit 0
