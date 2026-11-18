<#
.SYNOPSIS
  Adds PAYASSIST_DB_APP_PASSWORD (the restricted runtime role's password, ADR 0013) to an existing .env.
.DESCRIPTION
  For environments created before milestone 6.3. Generates a random 32-byte (256-bit) base64url secret and appends it
  to the git-ignored .env. Does nothing if the variable already exists. The value is never printed.
  The password is applied to the database by the "migrate" job (docker compose up), which runs as the schema owner.
#>
$ErrorActionPreference = 'Stop'
$root = Split-Path -Parent $PSScriptRoot
$envFile = Join-Path $root '.env'
if (-not (Test-Path $envFile)) { throw ".env not found. Run .\scripts\new-dev-env.ps1 first." }

if (Select-String -Path $envFile -Pattern '^PAYASSIST_DB_APP_PASSWORD=.+' -Quiet) {
    Write-Host 'PAYASSIST_DB_APP_PASSWORD already present in .env; nothing changed.' -ForegroundColor Yellow
    exit 0
}

$buffer = New-Object byte[] 32
[System.Security.Cryptography.RandomNumberGenerator]::Create().GetBytes($buffer)
$secret = [Convert]::ToBase64String($buffer).TrimEnd('=').Replace('+', '-').Replace('/', '_')

$content = [System.IO.File]::ReadAllText($envFile)
$prefix = if ($content.Length -gt 0 -and -not $content.EndsWith("`n")) { "`n" } else { '' }
$block = $prefix + "# Restricted runtime role payassist_app (ADR 0013); added by scripts\add-app-db-password.ps1`n" +
        "PAYASSIST_DB_APP_PASSWORD=$secret`n"
# UTF-8 without BOM, like new-dev-env.ps1.
[System.IO.File]::AppendAllText($envFile, $block, (New-Object System.Text.UTF8Encoding $false))
Remove-Variable secret, buffer, block
Write-Host 'Added PAYASSIST_DB_APP_PASSWORD (256-bit random) to .env. The value was not displayed.' -ForegroundColor Green
