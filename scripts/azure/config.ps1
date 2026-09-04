# Shared configuration for the Azure deployment scripts (dot-sourced).
# Idempotent: a random 6-hex suffix is generated once and persisted under
# scripts/azure/.state/ so reruns keep the same resource names.

$ErrorActionPreference = "Stop"

$stateDir = Join-Path $PSScriptRoot ".state"
if (-not (Test-Path $stateDir)) { New-Item -ItemType Directory -Path $stateDir | Out-Null }

$stateFile = Join-Path $stateDir "state.json"

function Get-OrCreate-Suffix {
    if (Test-Path $stateFile) {
        $state = Get-Content $stateFile -Raw | ConvertFrom-Json
        return $state.suffix
    }
    $suffix = -join ((1..6) | ForEach-Object { "{0:x}" -f (Get-Random -Minimum 0 -Maximum 16) })
    $state = [ordered]@{ suffix = $suffix }
    $state | ConvertTo-Json | Set-Content -Path $stateFile -Encoding utf8
    return $suffix
}

function Get-State-Value {
    param([string]$Key, [object]$Default)
    if (Test-Path $stateFile) {
        $state = Get-Content $stateFile -Raw | ConvertFrom-Json
        if ($state.PSObject.Properties.Name -contains $Key -and $state.$Key) { return $state.$Key }
    }
    return $Default
}

function Set-State-Value {
    param([string]$Key, [object]$Value)
    $existing = [ordered]@{}
    if (Test-Path $stateFile) {
        $parsed = Get-Content $stateFile -Raw | ConvertFrom-Json
        foreach ($p in $parsed.PSObject.Properties) { $existing[$p.Name] = $p.Value }
    }
    $existing[$Key] = $Value
    [pscustomobject]$existing | ConvertTo-Json | Set-Content -Path $stateFile -Encoding utf8
}

$Suffix      = Get-OrCreate-Suffix
$Location    = Get-State-Value "location" "eastus2"
$ResourceGroup = Get-State-Value "resourceGroup" "rg-cicd-platform"

$AcrName      = "cicdacr$Suffix"
$CaeName      = "cae-cicd"
$VnetName     = "cicd-vnet"
$AppSubnet    = "app-subnet"
$PgSubnet     = "pg-subnet"
$LogAnalytics = "la-cicd$Suffix"
$StorageAccount  = "cicdstore$Suffix"
$KeyVaultName = "kv-cicd$Suffix"
$PgServerName = "pg-cicd-$Suffix"
$UaiName      = "id-cicd"
$ImageTag     = "latest"

function Az-Checked {
    param([string]$Desc, [string[]]$Args)
    Write-Host "== $Desc" -ForegroundColor Cyan
    $output = & az @Args 2>&1
    if ($LASTEXITCODE -ne 0) {
        Write-Host ($output -join "`n") -ForegroundColor Red
        throw "Command failed: az $($Args -join ' ')"
    }
    return $output
}