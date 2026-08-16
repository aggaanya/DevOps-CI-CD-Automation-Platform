# Publishes a PipelineJob to the local RabbitMQ management API
# (requires docker-compose services to be running).
#
# Examples:
#   .\scripts\publish-job.ps1 -RepoUrl "file:///C:/tmp/repo" -CommitSha "abc123..."
#   .\scripts\publish-job.ps1 -RepoUrl "https://github.com/aggaanya/RealShield-...git" -CommitSha "3c547cb..." -PipelineFile "pipeline.yml"
#
# Output: prints the HTTP status of the publish call and the jobId.
param(
    [Parameter(Mandatory = $true)][string]$RepoUrl,
    [Parameter(Mandatory = $true)][string]$CommitSha,
    [string]$Branch = "main",
    [string]$PipelineFile = "pipeline.yml",
    [string]$JobId,
    [string]$PipelineId,
    [string]$MgmtBase = "http://localhost:15672",
    [string]$MgmtUser = "guest",
    [string]$MgmtPass = "guest"
)

$ErrorActionPreference = "Stop"

if (-not $JobId) { $JobId = "job-" + [guid]::NewGuid().ToString("N") }
if (-not $PipelineId) { $PipelineId = "pipeline-" + $JobId }

$job = [ordered]@{
    jobId          = $JobId
    pipelineId     = $PipelineId
    repositoryUrl  = $RepoUrl
    commitSha      = $CommitSha
    branch         = $Branch
    pipelineFile   = $PipelineFile
    environment    = @{}
    metadata       = @{}
    createdAt      = [DateTime]::UtcNow.ToString("yyyy-MM-ddTHH:mm:ss.fffZ")
}

$payload = $job | ConvertTo-Json -Compress -Depth 5

$body = @{
    properties       = @{ content_type = "application/json" }
    routing_key      = "cicd.job.submitted"
    payload          = $payload
    payload_encoding = "string"
} | ConvertTo-Json -Compress -Depth 5

$url = "$MgmtBase/api/exchanges/%2F/cicd.jobs.exchange/publish"
$encoded = [Convert]::ToBase64String([Text.Encoding]::ASCII.GetBytes("${MgmtUser}:${MgmtPass}"))

$response = Invoke-RestMethod -Uri $url -Method Post -Headers @{ Authorization = "Basic $encoded" } `
    -ContentType "application/json" -Body $body

$response | ConvertTo-Json -Compress
Write-Host "Published job $JobId -> $RepoUrl @ $CommitSha"
