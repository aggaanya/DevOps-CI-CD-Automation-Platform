<#
.SYNOPSIS
    Triggers a remote pipeline execution on the Azure deployment and polls the
    control plane until the standalone worker emits a terminal result.

    Requires: an active Azure deployment (.state has frontendUrl) and the
    target commit (containing the fixture) pushed to origin/main.
#>
[CmdletBinding()]
param(
    [string]$FrontendUrl = "",
    [string]$RepositoryUrl = "https://github.com/aggaanya/DevOps-CI-CD-Automation-Platform",
    [string]$CommitSha = "",
    [string]$Branch = "main",
    [string]$PipelineFile = "infrastructure/e2e-fixture/pipeline-remote.yml",
    [int]$TimeoutSeconds = 600
)

$ErrorActionPreference = "Stop"
. (Join-Path $PSScriptRoot "config.ps1")

if (-not $FrontendUrl) { $FrontendUrl = Get-State-Value "frontendUrl" "" }
if (-not $FrontendUrl) { throw "Frontend URL unknown - deploy first or pass -FrontendUrl." }
if (-not $CommitSha)   { $CommitSha = Get-State-Value "e2eCommitSha" "" }
if (-not $CommitSha)   { throw "CommitSha is required (must be pushed to origin/main)." }

Write-Host "== Triggering remote build of $CommitSha via $FrontendUrl" -ForegroundColor Cyan
$body = [ordered]@{
    repositoryUrl = $RepositoryUrl
    commitSha     = $CommitSha
    branch        = $Branch
    pipelineFile  = $PipelineFile
} | ConvertTo-Json

$queued = Invoke-RestMethod -Method Post `
    -Uri "$FrontendUrl/api/v1/executions/trigger" `
    -ContentType "application/json" -Body $body

Write-Host "   jobId: $($queued.jobId)  pipeline: $($queued.pipelineId)  status: $($queued.status)" -ForegroundColor Cyan

$result = $null
$sw = [System.Diagnostics.Stopwatch]::StartNew()
$terminal = @("SUCCEEDED", "FAILED", "TIMED_OUT", "CANCELLED")
while ($sw.Elapsed.TotalSeconds -lt $TimeoutSeconds) {
    Start-Sleep -Seconds 5
    try {
        $result = Invoke-RestMethod -Uri "$FrontendUrl/api/v1/executions/$($queued.jobId)"
    } catch {
        continue
    }
    if ($terminal -contains $result.status) { break }
}

if (-not $result) {
    Write-Host "[FAIL] No result within $TimeoutSeconds s for $($queued.jobId)" -ForegroundColor Red
    exit 1
}

Write-Host "== Result for $($queued.jobId)" -ForegroundColor Cyan
Write-Host "   status      : $($result.status)"
Write-Host "   workerId    : $($result.workerId)"
Write-Host "   commitSha   : $($result.commitSha)"
Write-Host "   durationMs  : $($result.durationMs)"
Write-Host "   message     : $($result.message)"

if ($result.status -ne "SUCCEEDED") {
    Write-Host "[FAIL] Job did not succeed." -ForegroundColor Red
    exit 1
}
Set-State-Value "e2eCommitSha" $CommitSha
Write-Host "[OK] Remote execution succeeded." -ForegroundColor Green