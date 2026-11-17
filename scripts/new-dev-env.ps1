<#
.SYNOPSIS
  Creates .env from .env.example with freshly generated random local secrets.
.DESCRIPTION
  Refuses to overwrite an existing .env unless -Force is given (Keycloak's realm is imported only
  on first start, so changing secrets later also requires: docker compose down; docker compose up).
#>
param([switch]$Force)

$ErrorActionPreference = 'Stop'
$root = Split-Path -Parent $PSScriptRoot
$envFile = Join-Path $root '.env'
$example = Join-Path $root '.env.example'

if ((Test-Path $envFile) -and -not $Force) {
    Write-Host ".env already exists. Use -Force to regenerate it." -ForegroundColor Yellow
    exit 0
}

function New-Secret([int]$bytes = 24) {
    $buffer = New-Object byte[] $bytes
    [System.Security.Cryptography.RandomNumberGenerator]::Create().GetBytes($buffer)
    # URL/env-safe: base64url without padding
    return [Convert]::ToBase64String($buffer).TrimEnd('=').Replace('+', '-').Replace('/', '_')
}

$values = @{
    'POSTGRES_PASSWORD'       = New-Secret
    'KEYCLOAK_ADMIN_PASSWORD' = New-Secret
    'KEYCLOAK_CLIENT_SECRET'  = New-Secret 32
    'PAYASSIST_DEMO_PASSWORD' = 'Demo-' + (New-Secret 12)
}

$lines = Get-Content $example | ForEach-Object {
    $line = $_
    foreach ($key in $values.Keys) {
        if ($line -match "^$key=") { $line = "$key=$($values[$key])" }
    }
    $line
}
# UTF-8 without BOM so docker compose parses the first line correctly.
[System.IO.File]::WriteAllLines($envFile, $lines, (New-Object System.Text.UTF8Encoding $false))

Write-Host "Created $envFile with random local secrets." -ForegroundColor Green
Write-Host "Demo user password (all five synthetic users): $($values['PAYASSIST_DEMO_PASSWORD'])"
