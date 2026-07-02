#Requires -Version 5.1
param(
    [Parameter(Mandatory = $true)]
    [string]$ModeId,

    [Parameter(Mandatory = $true)]
    [ValidateRange(1, 32)]
    [int]$Replicas
)

Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'

. (Join-Path $PSScriptRoot '_game-mode-helpers.ps1')

Test-Kubectl
$config = Get-GameModesConfig
$mode = Get-GameModeById -ModeId $ModeId

if ($Replicas -gt $mode.maxReplicas) {
    throw "Mode '$ModeId' max replicas is $($mode.maxReplicas)."
}

Write-Host "Scaling $($mode.statefulSet) to $Replicas on $($mode.gameNode)..."
kubectl scale statefulset/$($mode.statefulSet) -n $config.namespace --replicas=$Replicas
kubectl rollout status statefulset/$($mode.statefulSet) -n $config.namespace --timeout=900s
kubectl get pods -n $config.namespace -l "skypvp.io/mode=$ModeId" -o wide
