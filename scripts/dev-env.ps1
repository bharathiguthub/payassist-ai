<#
.SYNOPSIS
  Loads .env into the current PowerShell session and sets the variables the backend needs when it
  runs on the host (outside Docker) against the Docker Compose Postgres and Keycloak.
.EXAMPLE
  . .\scripts\dev-env.ps1        # note the leading dot: dot-source so variables stay in your session
  cd backend; .\mvnw.cmd spring-boot:run
#>
$ErrorActionPreference = 'Stop'
$root = Split-Path -Parent $PSScriptRoot
$envFile = Join-Path $root '.env'
if (-not (Test-Path $envFile)) { throw ".env not found. Run .\scripts\new-dev-env.ps1 first." }

Get-Content $envFile | Where-Object { $_ -match '^\s*[A-Za-z_][A-Za-z0-9_]*=' } | ForEach-Object {
    $name, $value = $_ -split '=', 2
    Set-Item -Path "Env:$($name.Trim())" -Value $value.Trim()
}

$db = if ($env:POSTGRES_DB) { $env:POSTGRES_DB } else { 'payassist' }
$env:SPRING_PROFILES_ACTIVE    = 'dev'
$port = if ($env:POSTGRES_HOST_PORT) { $env:POSTGRES_HOST_PORT } else { '5433' }
$env:PAYASSIST_DB_URL          = "jdbc:postgresql://localhost:${port}/$db"
$env:PAYASSIST_DB_USERNAME     = $env:POSTGRES_USER
$env:PAYASSIST_DB_PASSWORD     = $env:POSTGRES_PASSWORD
$env:PAYASSIST_PUBLIC_BASE_URL = 'http://localhost:5173'   # Vite dev server is the public origin
$env:PAYASSIST_COOKIE_SECURE   = 'false'
$env:KEYCLOAK_ISSUER_URI       = 'http://localhost:8180/realms/payassist'
$env:KEYCLOAK_CLIENT_ID        = 'payassist-bff'
Remove-Item Env:KEYCLOAK_BACKCHANNEL_URI -ErrorAction SilentlyContinue

Write-Host "Backend environment loaded for host-based development (public URL http://localhost:5173)." -ForegroundColor Green
