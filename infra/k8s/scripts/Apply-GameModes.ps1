#Requires -Version 5.1
Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'

. (Join-Path $PSScriptRoot '_game-mode-helpers.ps1')

Test-Kubectl
$config = Get-GameModesConfig
$infraRoot = Split-Path $PSScriptRoot -Parent

Write-Step 'Syncing credentials from skypvp-web'
& (Join-Path $PSScriptRoot 'Sync-NetworkCredentials.ps1')

Write-Step 'Applying cross-namespace Redis/Postgres aliases'
kubectl apply -f (Join-Path $infraRoot 'cross-namespace-services.yaml') | Out-Host

Write-Step 'Applying Paper entrypoint ConfigMap (deprecated stub)'
kubectl apply -f (Join-Path $infraRoot 'skypvp-paper-entrypoint.yaml') | Out-Host

Write-Step 'Applying Velocity proxy manifest'
kubectl apply -f (Join-Path $infraRoot 'velocity-proxy.yaml') | Out-Host

Write-Step 'Applying active game server manifests'
kubectl apply -f (Join-Path $infraRoot 'game-servers.yaml') | Out-Host

Write-Step 'Configured game modes'
foreach ($mode in $config.modes) {
    Write-Host ("  - {0}: {1} on {2} (default replicas {3}, max {4})" -f `
        $mode.id, $mode.statefulSet, $mode.gameNode, $mode.defaultReplicas, $mode.maxReplicas)
}

Write-Host "`nDone. Scale with: .\Scale-GameMode.ps1 -ModeId extraction -Replicas 3" -ForegroundColor Green
