<#
.SYNOPSIS
    14-point end-to-end verification of the Phase 7 Azure deployment.
    Reads endpoint state from .state (written by deploy.ps1).

    Each check prints PASS/FAIL; the script exits non-zero on any failure.
#>
[CmdletBinding()]
param(
    [string]$FrontendUrl = ""
)

$ErrorActionPreference = "Stop"
. (Join-Path $PSScriptRoot "config.ps1")

if (-not $FrontendUrl) { $FrontendUrl = Get-State-Value "frontendUrl" "" }
if (-not $FrontendUrl) { throw "No deployment state found - run deploy.ps1 first." }

$sub = & az account show --query id -o tsv
if (-not $sub) { & az login; $sub = & az account show --query id -o tsv }
$rgId = "/subscriptions/$sub/resourceGroups/$ResourceGroup"
$apiVersion = "2024-02-02-preview"

$pass = 0; $fail = 0
function Report([string]$Check, [bool]$Ok, [string]$Detail = "") {
    if ($Ok) {
        $script:pass++
        Write-Host "  [PASS] $Check" -ForegroundColor Green
    } else {
        $script:fail++
        Write-Host "  [FAIL] $Check" -ForegroundColor Red
    }
    if ($Detail) { Write-Host "         $Detail" }
}

Write-Host "==============================================" -ForegroundColor Cyan
Write-Host "Phase 7 verification - $FrontendUrl" -ForegroundColor Cyan
Write-Host "==============================================" -ForegroundColor Cyan

# --- 1. Signed-in principal + subscription ---------------------------------
$account = & az account show -o json | ConvertFrom-Json
Report "1. Signed in as $($account.user.name) on subscription $($account.id)" `
    ($null -ne $account.id -and $account.state -eq "Enabled") `
    "tenant: $($account.tenantId) state: $($account.state)"

# --- 2. Resource group contains the expected resource types -----------------
$resources = & az resource list --resource-group $ResourceGroup -o json | ConvertFrom-Json
$types = $resources.type
$expectedTypes = @(
    "Microsoft.ContainerRegistry/registries",
    "Microsoft.App/managedEnvironments",
    "Microsoft.DBforPostgreSQL/flexibleServers",
    "Microsoft.Storage/storageAccounts",
    "Microsoft.KeyVault/vaults",
    "Microsoft.ManagedIdentity/userAssignedIdentities",
    "Microsoft.OperationalInsights/workspaces",
    "Microsoft.Network/virtualNetworks"
)
foreach ($t in $expectedTypes) {
    Report "2. Resource type present: $t" ($types -contains $t)
}

# --- 3. PostgreSQL Flexible Server Up ---------------------------------------
$pg = & az postgres flexible-server show -g $ResourceGroup -n $PgServerName -o json 2>$null | ConvertFrom-Json
$pgUp = ($null -ne $pg -and $pg.state -eq "Ready")
Report "3. PostgreSQL Flexible Server '$PgServerName' Ready" $pgUp `
    ($(if ($pg) { "state=$($pg.state) version=$($pg.version)" } else { "not found" }))

# --- 4. Storage account shares exist ----------------------------------------
$stores = & az rest -m GET -u "https://management.azure.com$rgId/providers/Microsoft.Storage/storageAccounts/$StorageAccount/fileServices/default/shares?api-version=2023-01-01" |
    ConvertFrom-Json
$shareNames = @($stores.value | ForEach-Object { $_.name })
foreach ($share in @("rabbitmq-data", "worker-workspaces")) {
    Report "4. Storage share '$share' exists" ($shareNames -contains $share)
}

# --- 5. Key Vault secrets ---------------------------------------------------
$secrets = & az keyvault secret list --vault-name $KeyVaultName -o json | ConvertFrom-Json
$secretNames = @($secrets | ForEach-Object { $_.name })
foreach ($s in @("cicd-pg-password", "cicd-rabbit-user", "cicd-rabbit-pass")) {
    Report "5. Key Vault secret '$s'" ($secretNames -contains $s)
}

# --- 6. ACR repositories + tag ----------------------------------------------
$registry = Get-State-Value "registry" ""
$tag = Get-State-Value "imageTag" "latest"
if ($registry) {
    foreach ($repo in @("cicd-backend", "cicd-worker", "cicd-frontend")) {
        $tags = & az acr repository show-tags --name $AcrName --repository $repo -o json 2>$null | ConvertFrom-Json
        Report "6. ACR image '$repo:$tag'" @($tags) -contains $tag
    }
} else {
    Write-Host "  [FAIL] Registry not in state - run deploy.ps1 first." -ForegroundColor Red
    $fail++
}

# --- 7. UAI AcrPull grant ----------------------------------------------------
$roleAssignments = & az role assignment list --scope "/subscriptions/$sub/resourceGroups/$ResourceGroup/providers/Microsoft.ContainerRegistry/registries/$AcrName" -o json |
    ConvertFrom-Json
$acrPullUai = @($roleAssignments | Where-Object { $_.roleDefinitionName -eq "AcrPull" -and $_.principalName -match "id-cicd" })
Report "7. UAI 'id-cicd' granted AcrPull on ACR" ($acrPullUai.Count -ge 1)

# --- 8. Frontend public HTTPS reachable -------------------------------------
$frontendHtml = ""
try { $frontendHtml = (Invoke-WebRequest -UseBasicParsing -Uri $FrontendUrl -TimeoutSec 30).Content } catch {}
Report "8. Frontend public HTTPS returns HTML" ($frontendHtml -match "<div id=`"root`"") `
    "url: $FrontendUrl"

# --- 9. Backend health through public frontend proxy -------------------------
$health = $null; $healthMsg = ""
try {
    $health = Invoke-RestMethod -Uri "$FrontendUrl/api/v1/health" -TimeoutSec 60
    $healthMsg = ($health.components | ConvertTo-Json -Compress)
} catch { $healthMsg = $_.Exception.Message }
$healthOk = ($null -ne $health -and $health.status -eq "UP")
Report "9. Backend health = UP (DB + RabbitMQ reachable)" $healthOk $healthMsg

# --- 10. Container apps present & running/serving ----------------------------
foreach ($app in @("rabbitmq", "backend", "worker", "frontend")) {
    $appObj = & az rest -m GET -u "https://management.azure.com$rgId/providers/Microsoft.App/containerApps/$app?api-version=$apiVersion" 2>$null | ConvertFrom-Json
    $running = ($null -ne $appObj -and
        $appObj.properties.provisioningState -eq "Succeeded" -and
        $appObj.properties.configuration.activeRevisionsMode -eq "Single" -and
        $appObj.properties.latestRevisionName)
    Report "10. Container app '$app' provisioned (Single revision)" $running
}

# --- 11. CAE Azure Files storages mounted ------------------------------------
$storages = & az rest -m GET -u "https://management.azure.com$rgId/providers/Microsoft.App/managedEnvironments/$CaeName/storages?api-version=$apiVersion" | ConvertFrom-Json
$storageNames = @($storages.value | ForEach-Object { $_.name })
foreach ($s in @("rabbitmq-data", "worker-workspaces")) {
    Report "11. CAE storage '$s' configured" ($storageNames -contains $s)
}

# --- 12. Remote E2E: trigger -> worker -> results loop -----------------------
Write-Host "  .. running remote E2E (trigger -> cicd topology -> worker -> results loop)" -ForegroundColor Cyan
& (Join-Path $PSScriptRoot "remote-job.ps1") -FrontendUrl $FrontendUrl -TimeoutSeconds 900
if ($LASTEXITCODE -eq 0) { Report "12. Remote E2E job SUCCEEDED end-to-end" $true } else { Report "12. Remote E2E job SUCCEEDED end-to-end" $false }

# --- 13. Restart / recovery proof -------------------------------------------
$backendObj = & az rest -m GET -u "https://management.azure.com$rgId/providers/Microsoft.App/containerApps/backend/revisions?api-version=$apiVersion" | ConvertFrom-Json
$activeRev = @($backendObj.value | Where-Object { $_.properties.active })[0]
if ($activeRev) {
    Write-Host "  .. restarting backend revision $($activeRev.name)" -ForegroundColor Cyan
    $null = & az rest -m POST -u "https://management.azure.com$rgId/providers/Microsoft.App/containerApps/backend/revisions/$($activeRev.name)/restart?api-version=$apiVersion"
    $healthyAgain = $false
    for ($i = 0; $i -lt 40; $i++) {
        Start-Sleep -Seconds 15
        try { $h = Invoke-RestMethod -Uri "$FrontendUrl/api/v1/health" -TimeoutSec 30; if ($h.status -eq "UP") { $healthyAgain = $true; break } } catch {}
    }
    Report "13. Backend recovered after revision restart (health UP)" $healthyAgain
    $recovered = $true
} else {
    Write-Host "  [FAIL] No active backend revision found" -ForegroundColor Red
    $fail++; $recovered = $false
}

# --- 14. Log Analytics observing container logs ------------------------------
$query = 'ContainerAppConsoleLogs_CL | where TimeGenerated > ago(15m) | count'
$logsOut = & az monitor log-analytics query -w $LogAnalytics --analytics-query $query -o json 2>$null
$logCount = 0
if ($logsOut) {
    try { $logCount = [int](($logsOut | ConvertFrom-Json).Tables[0].Rows[0][0]) } catch {}
}
Report "14. Log Analytics received container logs (>=1 row in 15m)" ($logCount -ge 1) "rows=$logCount"

Write-Host "==============================================" -ForegroundColor Cyan
Write-Host "Verification result: $pass passed, $fail failed" -ForegroundColor $(if ($fail -eq 0) { "Green" } else { "Red" })
Write-Host "==============================================" -ForegroundColor Cyan
exit $(if ($fail -eq 0) { 0 } else { 1 })