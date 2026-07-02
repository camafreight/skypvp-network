#Requires -Version 5.1
Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'

. (Join-Path $PSScriptRoot 'infra/k8s/scripts/_game-mode-helpers.ps1')

$config = Get-GameModesConfig
$buildId = Get-Date -Format 'yyyyMMddHHmmss'
$tags = @{}

& (Join-Path $PSScriptRoot 'infra/scripts/Generate-FloodgateKey.ps1')
Write-Host 'Staging Bedrock plugin jars...' -ForegroundColor Cyan
Push-Location $PSScriptRoot
try {
    .\gradlew.bat deployJars stageBedrockPlugins stageLuckPermsPlugins stageFoliaJar -q
}
finally {
    Pop-Location
}

Write-Host "Building Docker images with tag suffix: $buildId" -ForegroundColor Cyan

foreach ($mode in $config.modes) {
    $dockerfile = ".tmp-paper-$($mode.id)-1.Dockerfile"
    if (-not (Test-Path $dockerfile)) {
        throw "Dockerfile not found for mode '$($mode.id)': $dockerfile"
    }

    $tag = "$($mode.image)-$buildId"
    Write-Host "  building $tag"
    docker build -f $dockerfile -t $tag .
    docker tag $tag $mode.image
    $tags[$mode.id] = $tag
}

$proxyTag = "ghcr.io/skypvp/velocity-proxy:local-$buildId"
Write-Host "  building $proxyTag"
docker build -f .tmp-proxy-local.Dockerfile -t $proxyTag .
docker tag $proxyTag ghcr.io/skypvp/velocity-proxy:local
$tags['proxy'] = $proxyTag

Write-Host 'Exporting to tar...' -ForegroundColor Cyan
$tarPath = 'game-images-unique.tar'
if (Test-Path $tarPath) { Remove-Item $tarPath }
$allTags = @($tags.Values) + @($config.modes | ForEach-Object { $_.image }) + @('ghcr.io/skypvp/velocity-proxy:local')
docker save -o $tarPath @($allTags | Select-Object -Unique)

Write-Host 'Importing to node-1 (lobby + proxy)...' -ForegroundColor Cyan
$sshCmd = "apk add --no-cache openssh-client sshpass && sshpass -p 'SkyPvP2017@' scp -o StrictHostKeyChecking=no /workspace/game-images-unique.tar skypvp-node-1@192.168.0.3:/tmp/game-images-unique.tar && sshpass -p 'SkyPvP2017@' ssh -o StrictHostKeyChecking=no skypvp-node-1@192.168.0.3 ""echo 'SkyPvP2017@' | sudo -S k3s ctr -n k8s.io images import /tmp/game-images-unique.tar"""
[IO.File]::WriteAllText('import-images-node1.sh', $sshCmd)
docker run --rm -v "${PWD}:/workspace" -w /workspace alpine sh import-images-node1.sh

Write-Host 'Importing to node-2 (extraction)...' -ForegroundColor Cyan
$sshCmd2 = "apk add --no-cache openssh-client sshpass && sshpass -p 'SkyPvP2017@' scp -o StrictHostKeyChecking=no /workspace/game-images-unique.tar skypvp-node-2@192.168.0.4:/tmp/game-images-unique.tar && sshpass -p 'SkyPvP2017@' ssh -o StrictHostKeyChecking=no skypvp-node-2@192.168.0.4 ""echo 'SkyPvP2017@' | sudo -S k3s ctr -n k8s.io images import /tmp/game-images-unique.tar"""
[IO.File]::WriteAllText('import-images-node2.sh', $sshCmd2)
docker run --rm -v "${PWD}:/workspace" -w /workspace alpine sh import-images-node2.sh

Write-Host 'Updating active workloads from game-modes.json...' -ForegroundColor Cyan
foreach ($mode in $config.modes) {
    kubectl -n $config.namespace set image statefulset/$($mode.statefulSet) paper=$($tags[$mode.id])
    kubectl -n $config.namespace patch statefulset/$($mode.statefulSet) --type=json `
        -p='[{"op":"replace","path":"/spec/template/spec/containers/0/imagePullPolicy","value":"IfNotPresent"}]'
    kubectl -n $config.namespace rollout restart statefulset/$($mode.statefulSet)
}

kubectl -n $config.namespace set image deployment/skypvp-proxy velocity=$($tags['proxy'])
kubectl -n $config.namespace patch deployment/skypvp-proxy --type=json `
    -p='[{"op":"replace","path":"/spec/template/spec/containers/0/imagePullPolicy","value":"IfNotPresent"}]'
kubectl -n $config.namespace rollout restart deployment/skypvp-proxy

Write-Host 'Waiting for rollouts...' -ForegroundColor Cyan
foreach ($mode in $config.modes) {
    kubectl -n $config.namespace rollout status statefulset/$($mode.statefulSet) --timeout=600s
    if ($LASTEXITCODE -ne 0) {
        Write-Warning "Rollout timed out for $($mode.statefulSet); deleting pod-0 to force image refresh..."
        kubectl -n $config.namespace delete pod "$($mode.statefulSet)-0" --wait=true
        kubectl -n $config.namespace rollout status statefulset/$($mode.statefulSet) --timeout=600s
    }
}
kubectl -n $config.namespace rollout status deployment/skypvp-proxy --timeout=300s

Write-Host 'Rollout completed successfully!' -ForegroundColor Green
