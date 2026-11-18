<#
.SYNOPSIS
  Kept for compatibility (M6.3). Adds every missing generated secret to .env; see add-missing-secrets.ps1.
#>
& (Join-Path $PSScriptRoot 'add-missing-secrets.ps1')
