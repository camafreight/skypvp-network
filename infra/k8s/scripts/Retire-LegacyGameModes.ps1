#Requires -Version 5.1
Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'

. (Join-Path $PSScriptRoot '_game-mode-helpers.ps1')

Test-Kubectl
$config = Get-GameModesConfig
$namespace = $config.namespace

Write-Step "Retiring legacy game modes in $namespace"

foreach ($statefulSet in $config.retired.statefulSets) {
    $exists = kubectl get statefulset $statefulSet -n $namespace --ignore-not-found -o name 2>$null
    if ($exists) {
        Write-Host "  scaling down statefulset/$statefulSet"
        kubectl scale statefulset/$statefulSet -n $namespace --replicas=0 | Out-Host
        Write-Host "  deleting statefulset/$statefulSet"
        kubectl delete statefulset $statefulSet -n $namespace --wait=true | Out-Host
    }
}

foreach ($service in $config.retired.services) {
    $exists = kubectl get service $service -n $namespace --ignore-not-found -o name 2>$null
    if ($exists) {
        Write-Host "  deleting service/$service"
        kubectl delete service $service -n $namespace | Out-Host
    }
}

if ($config.retired.stagingStatefulSets) {
    foreach ($statefulSet in $config.retired.stagingStatefulSets) {
        $exists = kubectl get statefulset $statefulSet -n $namespace --ignore-not-found -o name 2>$null
        if ($exists) {
            Write-Host "  deleting staging statefulset/$statefulSet"
            kubectl delete statefulset $statefulSet -n $namespace --wait=true | Out-Host
        }
    }
}

Write-Host "`nLegacy modes removed. Active modes are defined in infra/k8s/game-modes.json" -ForegroundColor Green
