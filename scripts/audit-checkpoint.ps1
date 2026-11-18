<#
.SYNOPSIS
  Verifies the audit hash chain against the external checkpoint ledger, and optionally appends a new checkpoint.
.DESCRIPTION
  Runs the on-demand Compose tool "audit-checkpoint" (ADR 0013 section 10) as the read-only payassist_auditor role.
  The ledger is .\audit-checkpoints\audit-checkpoints.jsonl (git-ignored, outside PostgreSQL); the HMAC key comes from
  PAYASSIST_AUDIT_CHECKPOINT_KEY in .env and is never printed. Exit code: 0 OK, 2 integrity failure, 1 configuration.
  Keep a copy of the ledger (or at least the latest "mac") somewhere else: see docs\runbooks\audit-checkpoints.md.
.EXAMPLE
  .\scripts\audit-checkpoint.ps1 -Create     # verify, then append a signed checkpoint
  .\scripts\audit-checkpoint.ps1             # verify only
#>
param([switch]$Create)
# Native stderr (docker progress) must not abort the script in Windows PowerShell 5.1; the exit code decides.
$ErrorActionPreference = 'Continue'
$root = Split-Path -Parent $PSScriptRoot
$mode = if ($Create) { 'create' } else { 'verify' }
Push-Location $root
try {
    docker compose run --rm --no-deps audit-checkpoint $mode
    $code = $LASTEXITCODE
} finally {
    Pop-Location
}
exit $code
