<#
.SYNOPSIS
    Verifies the Azure CLI is attached to the expected AAD tenant and prints
    the current principal, tenant, and subscription context used by the Phase 7
    deployment scripts.
#>
[CmdletBinding()]
param(
    [ValidatePattern('^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$')]
    [string]$ExpectedSubscriptionId = ""
)

$ErrorActionPreference = "Stop"

$account = & az account show -o json 2>$null
if (-not $account) {
    Write-Host "No active Azure session. Run 'az login'." -ForegroundColor Yellow
    & az login
    $account = & az account show -o json
}
$ctx = $account | ConvertFrom-Json

$info = @(
    "Tenant        : $($ctx.tenantId)",
    "Subscription  : $($ctx.id)  ($($ctx.name))",
    "Principal     : $($ctx.user.name)  [type $($ctx.user.type)]",
    "Environment   : $($ctx.environmentName)"
)
$info | ForEach-Object { Write-Host $_ -ForegroundColor Cyan }

$principal = & az ad signed-in-user show -o json 2>$null | ConvertFrom-Json
if ($principal) {
    Write-Host "Object id     : $($principal.id)  ($($principal.userPrincipalName))" -ForegroundColor Cyan
}

if ($ExpectedSubscriptionId -and $ctx.id -ne $ExpectedSubscriptionId) {
    Write-Host "Subscription mismatch: expected $ExpectedSubscriptionId" -ForegroundColor Red
    exit 1
}