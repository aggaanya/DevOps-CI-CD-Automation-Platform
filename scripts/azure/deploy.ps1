<#
.SYNOPSIS
    Deploys the CI/CD platform to Azure Container Apps using only core az CLI
    commands plus ARM template deployments (no 'az containerapp' extension).

.DESCRIPTION
    Creates RG, Log Analytics, VNet + delegated subnets, Storage (Azure Files
    shares), Key Vault + secrets, User-Assigned Identity, ACR (with image
    builds/pushes), Azure PostgreSQL Flexible Server (private access), the
    Container Apps Environment (ARM), CAE storage mounts (ARM), and finally
    the 4 container apps (rabbitmq, backend, worker, frontend) via ARM.

    All names derive from config.ps1; the suffix is persisted in .state so
    reruns are idempotent.
#>

[CmdletBinding()]
param(
    [string]$ImageTag = "latest",
    [switch]$SkipBuilds
)

$ErrorActionPreference = "Stop"

. (Join-Path $PSScriptRoot "config.ps1")

$RepoRoot = (Resolve-Path (Join-Path $PSScriptRoot "..\..")).Path
$ArmDir   = Join-Path $RepoRoot "infrastructure\azure"

function Assert-LastExitCode {
    param([string]$What, $Output)
    if ($LASTEXITCODE -ne 0) {
        Write-Host "`n[FAIL] $What" -ForegroundColor Red
        if ($Output) { $Output | ForEach-Object { Write-Host $_ } }
        throw "Deployment failed at: $What"
    }
}

function Az-Step {
    param([string]$What, [string[]]$Args)
    Write-Host "`n== $What" -ForegroundColor Cyan
    $out = & az @Args 2>&1
    Assert-LastExitCode $What $out
    return $out
}

function Az-Json {
    param([string]$What, [string[]]$Args)
    $out = Az-Step -What $What -Args $Args
    return ($out -join "`n") | ConvertFrom-Json
}

function Wait-Provider {
    param([string]$Name)
    Write-Host "== Registering provider $Name ..." -ForegroundColor Cyan
    & az provider register -n $Name 2>&1 | Out-Null
    for ($i = 0; $i -lt 60; $i++) {
        $state = (& az provider show -n $Name --query registrationState -o tsv 2>&1)
        if ($state -eq "Registered") {
            Write-Host "   provider $Name registered." -ForegroundColor Green
            return
        }
        Start-Sleep -Seconds 10
    }
    throw "Provider $Name did not register within 10 minutes."
}

function New-RandomPassword {
    param([int]$Length = 24)
    $lower = "abcdefghjkmnpqrstuvwxyz"
    $upper = "ABCDEFGHJKMNPQRSTUVWXYZ"
    $digit = "23456789"
    $symbols = "!@#%^*-_=+"
    $chars = @(
        (($lower.ToCharArray() | Get-Random -Count 6) -join ""),
        (($upper.ToCharArray() | Get-Random -Count 6) -join ""),
        (($digit.ToCharArray() | Get-Random -Count 6) -join ""),
        (($symbols.ToCharArray() | Get-Random -Count 4) -join "")
    ) -join ""
    $charsArr = $chars.ToCharArray()
    return -join ($charsArr | Sort-Object { Get-Random })
}

function Build-Push-Image {
    param([string]$Name, [string]$Dockerfile, [string]$Context)
    $Image = "$AcrServer/$Name`:$ImageTag"
    Write-Host "`n== docker build $Name" -ForegroundColor Cyan
    docker build -t $Image -f $Dockerfile $Context 2>&1 | ForEach-Object { Write-Host $_ }
    Assert-LastExitCode "docker build $Name" @()
    Write-Host "== docker push $Name" -ForegroundColor Cyan
    docker push $Image 2>&1 | ForEach-Object { Write-Host $_ }
    Assert-LastExitCode "docker push $Name" @()
}

Write-Host "==============================================" -ForegroundColor Cyan
Write-Host "Phase 7 - Azure Deployment ($ResourceGroup / suffix $Suffix)" -ForegroundColor Cyan
Write-Host "==============================================" -ForegroundColor Cyan

# ----------------------------------------------------------------------------
# 0. Sign-in / subscription preconditions
# ----------------------------------------------------------------------------
$account = & az account show 2>$null
if ($LASTEXITCODE -ne 0) {
    throw "Not signed in. Run 'az login' first and select your subscription."
}

# ----------------------------------------------------------------------------
# 1. Provider registrations
# ----------------------------------------------------------------------------
foreach ($provider in @("Microsoft.App", "Microsoft.DBforPostgreSQL",
    "Microsoft.ContainerRegistry", "Microsoft.OperationalInsights",
    "Microsoft.Insights", "Microsoft.Storage", "Microsoft.KeyVault",
    "Microsoft.Network", "Microsoft.ManagedIdentity")) {
    & az provider register -n $provider 2>&1 | Out-Null
}
Wait-Provider "Microsoft.App"
Wait-Provider "Microsoft.DBforPostgreSQL"

# ----------------------------------------------------------------------------
# 2. Resource group
# ----------------------------------------------------------------------------
Az-Step "Create resource group $ResourceGroup" @("group", "create", "--name", $ResourceGroup, "--location", $Location)

# ----------------------------------------------------------------------------
# 3. Log Analytics workspace (CAE observability sink)
# ----------------------------------------------------------------------------
$la = Az-Json "Create Log Analytics workspace $LogAnalytics" @("monitor", "log-analytics", "workspace", "create",
    "--resource-group", $ResourceGroup, "--workspace-name", $LogAnalytics, "--location", $Location)
$laKeys = Az-Json "Get Log Analytics shared keys" @("monitor", "log-analytics", "workspace", "get-shared-keys",
    "--resource-group", $ResourceGroup, "--workspace-name", $LogAnalytics)
$LaCustomerId = $la.customerId
$LaSharedKey  = $laKeys.primarySharedKey

# ----------------------------------------------------------------------------
# 4. VNet + delegated subnets
# ----------------------------------------------------------------------------
Az-Step "Create VNet $VnetName" @("network", "vnet", "create",
    "--resource-group", $ResourceGroup, "--name", $VnetName, "--address-prefixes", "10.1.0.0/16", "--location", $Location)
Az-Step "Create app subnet (Microsoft.App delegation)" @("network", "vnet", "subnet", "create",
    "--resource-group", $ResourceGroup, "--vnet-name", $VnetName, "--name", $AppSubnet,
    "--address-prefixes", "10.1.0.0/22", "--delegations", "Microsoft.App/environments")
Az-Step "Create postgres subnet (flexibleServers delegation)" @("network", "vnet", "subnet", "create",
    "--resource-group", $ResourceGroup, "--vnet-name", $VnetName, "--name", $PgSubnet,
    "--address-prefixes", "10.1.4.0/24", "--delegations", "Microsoft.DBforPostgreSQL/flexibleServers")

$vnet = Az-Json "Get VNet id" @("network", "vnet", "show",
    "--resource-group", $ResourceGroup, "--name", $VnetName)
$AppSubnetId = ($vnet.subnets | Where-Object { $_.name -eq $AppSubnet }).id

# ----------------------------------------------------------------------------
# 5. Storage account + Azure Files shares
# ----------------------------------------------------------------------------
Az-Step "Create storage account $StorageAccount" @("storage", "account", "create",
    "--resource-group", $ResourceGroup, "--name", $StorageAccount, "--location", $Location,
    "--sku", "Standard_LRS", "--kind", "StorageV2", "--min-tls-version", "TLS1_2")
$keys = Az-Json "Get storage account keys" @("storage", "account", "keys", "list",
    "--account-name", $StorageAccount, "--resource-group", $ResourceGroup)
$StorageKey = $keys[0].value

foreach ($share in @("rabbitmq-data", "worker-workspaces")) {
    Az-Step "Create Azure Files share $share" @("storage", "share", "create",
        "--account-name", $StorageAccount, "--account-key", $StorageKey,
        "--name", $share, "--quota", "20")
}

# ----------------------------------------------------------------------------
# 6. User-assigned identity + Key Vault + secrets
# ----------------------------------------------------------------------------
$uai = Az-Json "Create user-assigned identity $UaiName" @("identity", "create",
    "--resource-group", $ResourceGroup, "--name", $UaiName)
$UaiId          = $uai.id
$UaiPrincipalId = $uai.principalId

Az-Step "Create Key Vault $KeyVaultName" @("keyvault", "create",
    "--resource-group", $ResourceGroup, "--name", $KeyVaultName, "--location", $Location)

$me = Az-Json "Get signed-in user" @("ad", "signed-in-user", "show")
az keyvault set-policy --name $KeyVaultName --object-id $me.id --secret-permissions get list set purge backup restore delete 2>&1 | Out-Null
Assert-LastExitCode "Grant caller key vault secret policy" @()

$PgPassword  = New-RandomPassword
$RabbitUser  = "cicd"
$RabbitPass  = New-RandomPassword

Az-Step "Persist cicd-pg-password secret" @("keyvault", "secret", "set",
    "--vault-name", $KeyVaultName, "--name", "cicd-pg-password", "--value", $PgPassword)
Az-Step "Persist cicd-rabbit-user secret" @("keyvault", "secret", "set",
    "--vault-name", $KeyVaultName, "--name", "cicd-rabbit-user", "--value", $RabbitUser)
Az-Step "Persist cicd-rabbit-pass secret" @("keyvault", "secret", "set",
    "--vault-name", $KeyVaultName, "--name", "cicd-rabbit-pass", "--value", $RabbitPass)

$kv = Az-Json "Get Key Vault properties" @("keyvault", "show",
    "--resource-group", $ResourceGroup, "--name", $KeyVaultName)
$RabbitUserSecretUrl = "$($kv.properties.vaultUri)secrets/cicd-rabbit-user"
$RabbitPassSecretUrl = "$($kv.properties.vaultUri)secrets/cicd-rabbit-pass"
$PgPasswordSecretUrl  = "$($kv.properties.vaultUri)secrets/cicd-pg-password"

az keyvault set-policy --name $KeyVaultName --object-id $UaiPrincipalId --secret-permissions get list 2>&1 | Out-Null
Assert-LastExitCode "Grant UAI key vault secret get/list policy" @()

# ----------------------------------------------------------------------------
# 7. Azure Container Registry
# ----------------------------------------------------------------------------
Az-Step "Create ACR $AcrName" @("acr", "create",
    "--resource-group", $ResourceGroup, "--name", $AcrName, "--sku", "Basic",
    "--location", $Location, "--admin-enabled", "false")
$AcrServer = "$AcrName.azurecr.io"
$acr = Az-Json "Get ACR resource id" @("acr", "show",
    "--resource-group", $ResourceGroup, "--name", $AcrName)

Az-Step "Grant UAI AcrPull on $AcrName" @("role", "assignment", "create",
    "--assignee-object-id", $UaiPrincipalId, "--assignee-principal-type", "ServicePrincipal",
    "--role", "AcrPull", "--scope", $acr.id)

# ----------------------------------------------------------------------------
# 8. Build + push images (skip with -SkipBuilds)
# ----------------------------------------------------------------------------
if (-not $SkipBuilds) {
    Az-Step "Login to ACR $AcrName" @("acr", "login", "--name", $AcrName)
    Build-Push-Image "cicd-backend"  (Join-Path $RepoRoot "backend\Dockerfile")  (Join-Path $RepoRoot "backend")
    Build-Push-Image "cicd-worker"   (Join-Path $RepoRoot "worker\Dockerfile")   (Join-Path $RepoRoot "worker")
    Build-Push-Image "cicd-frontend" (Join-Path $RepoRoot "frontend\Dockerfile") (Join-Path $RepoRoot "frontend")
}

# ----------------------------------------------------------------------------
# 9. Azure PostgreSQL Flexible Server (private access, delegated subnet)
# ----------------------------------------------------------------------------
$PgFqdn   = "$PgServerName.postgres.database.azure.com"
$PgDnsZone = $PgFqdn
Write-Host "`n== Creating PostgreSQL Flexible Server $PgServerName (this takes several minutes)..." -ForegroundColor Cyan
& az postgres flexible-server create `
    --resource-group $ResourceGroup `
    --name $PgServerName `
    --location $Location `
    --admin-user "cicd" `
    --admin-password $PgPassword `
    --database-name "cicd" `
    --vnet $VnetName `
    --subnet $PgSubnet `
    --private-dns-zone $PgDnsZone `
    --sku-name "Standard_B1ms" `
    --tier "Burstable" `
    --storage-size 32 `
    --version 16 `
    --yes 2>&1 | ForEach-Object { Write-Host $_ }
Assert-LastExitCode "Create PostgreSQL Flexible Server" @()

# ----------------------------------------------------------------------------
# 10. Container Apps Environment + storages + apps (ARM, no extension)
# ----------------------------------------------------------------------------
$deployment = Az-Step "Deploy Container Apps Environment (ARM)" @("deployment", "group", "create",
    "--resource-group", $ResourceGroup, "--name", "cae-$Suffix",
    "--template-file", (Join-Path $ArmDir "cae.json"),
    "--parameters", "environmentName=$CaeName", "appSubnetId=$AppSubnetId",
    "logAnalyticsCustomerId=$LaCustomerId", "logAnalyticsSharedKey=$LaSharedKey", "internalOnly=true")

$deployment = Az-Step "Deploy CAE storages (ARM)" @("deployment", "group", "create",
    "--resource-group", $ResourceGroup, "--name", "cae-storages-$Suffix",
    "--template-file", (Join-Path $ArmDir "storages.json"),
    "--parameters", "environmentName=$CaeName", "storageAccountName=$StorageAccount",
    "storageAccountKey=$StorageKey")

$deployment = Az-Step "Deploy container apps (ARM)" @("deployment", "group", "create",
    "--resource-group", $ResourceGroup, "--name", "apps-$Suffix",
    "--template-file", (Join-Path $ArmDir "apps.json"),
    "--parameters", "environmentName=$CaeName", "acrServer=$AcrServer", "imageTag=$ImageTag",
    "appIdentityId=$UaiId", "rabbitUserSecretUrl=$RabbitUserSecretUrl",
    "rabbitPassSecretUrl=$RabbitPassSecretUrl", "pgPasswordSecretUrl=$PgPasswordSecretUrl",
    "pgFqdn=$PgFqdn", "pgUser=cicd", "pgDatabase=cicd")

# ----------------------------------------------------------------------------
# 11. Capture endpoint FQDNs
# ----------------------------------------------------------------------------
$sub = & az account show --query id -o tsv
$rgId = "/subscriptions/$sub/resourceGroups/$ResourceGroup"
$apiVersion = "2024-02-02-preview"

$backendApp = (Az-Json "Fetch backend FQDN" @("rest", "--method", "GET",
    "--uri", "https://management.azure.com$rgId/providers/Microsoft.App/containerApps/backend?api-version=$apiVersion"))
$frontendApp = (Az-Json "Fetch frontend FQDN" @("rest", "--method", "GET",
    "--uri", "https://management.azure.com$rgId/providers/Microsoft.App/containerApps/frontend?api-version=$apiVersion"))
$rabbitApp = (Az-Json "Fetch rabbitmq FQDN" @("rest", "--method", "GET",
    "--uri", "https://management.azure.com$rgId/providers/Microsoft.App/containerApps/rabbitmq?api-version=$apiVersion"))

$BackendFqdn  = $backendApp.properties.configuration.ingress.fqdn
$FrontendFqdn = $frontendApp.properties.configuration.ingress.fqdn
$RabbitFqdn   = $rabbitApp.properties.configuration.ingress.fqdn

Set-State-Value "frontendUrl"  "https://$FrontendFqdn"
Set-State-Value "backendUrl"   "https://$BackendFqdn"
Set-State-Value "rabbitUrl"    "https://$($RabbitFqdn.Split(':')[0])"
Set-State-Value "pgFqdn"       $PgFqdn
Set-State-Value "registry"     $AcrServer
Set-State-Value "imageTag"     $ImageTag

Write-Host "`n==============================================" -ForegroundColor Green
Write-Host "Deployment complete." -ForegroundColor Green
Write-Host "  Frontend : https://$FrontendFqdn" -ForegroundColor Green
Write-Host "  Backend  : https://$BackendFqdn (internal only)" -ForegroundColor Green
Write-Host "  RabbitMQ : $RabbitFqdn (internal only)" -ForegroundColor Green
Write-Host "  Registry : $AcrServer" -ForegroundColor Green
Write-Host "Defaults  : image tag '$ImageTag', resource group '$ResourceGroup'." -ForegroundColor Green
Write-Host "==============================================" -ForegroundColor Green