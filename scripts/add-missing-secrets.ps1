<#
.SYNOPSIS
  Adds secrets introduced after an environment was created to the existing, git-ignored .env.
.DESCRIPTION
  For each secret below that is missing, generates a random 32-byte (256-bit) base64url value and appends it.
  Existing values are never changed. No value is ever printed.
    PAYASSIST_DB_APP_PASSWORD       runtime role payassist_app (M6.3)
    PAYASSIST_DB_AUDITOR_PASSWORD   read-only role payassist_auditor for audit checkpoints (M6.4)
    PAYASSIST_AUDIT_CHECKPOINT_KEY  HMAC key of the external checkpoint ledger (M6.4)
  Database passwords are applied by the "migrate" job (docker compose up), which runs as the schema owner.
#>
$ErrorActionPreference = 'Stop'
$root = Split-Path -Parent $PSScriptRoot
$envFile = Join-Path $root '.env'
if (-not (Test-Path $envFile)) { throw ".env not found. Run .\scripts\new-dev-env.ps1 first." }

$names = @('PAYASSIST_DB_APP_PASSWORD', 'PAYASSIST_DB_AUDITOR_PASSWORD', 'PAYASSIST_AUDIT_CHECKPOINT_KEY')
$utf8 = New-Object System.Text.UTF8Encoding $false
$added = @()
foreach ($name in $names) {
    if (Select-String -Path $envFile -Pattern "^$name=.+" -Quiet) { continue }
    $buffer = New-Object byte[] 32
    [System.Security.Cryptography.RandomNumberGenerator]::Create().GetBytes($buffer)
    $secret = [Convert]::ToBase64String($buffer).TrimEnd('=').Replace('+', '-').Replace('/', '_')
    $content = [System.IO.File]::ReadAllText($envFile)
    $prefix = if ($content.Length -gt 0 -and -not $content.EndsWith("`n")) { "`n" } else { '' }
    [System.IO.File]::AppendAllText($envFile, "$prefix# added by scripts\add-missing-secrets.ps1`n$name=$secret`n", $utf8)
    Remove-Variable secret, buffer
    $added += $name
}
if ($added.Count -eq 0) {
    Write-Host 'All secrets already present in .env; nothing changed.' -ForegroundColor Yellow
} else {
    Write-Host "Added to .env (256-bit random, values not displayed): $($added -join ', ')" -ForegroundColor Green
}
