#Requires -Version 5.1
Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'

. (Join-Path $PSScriptRoot 'infra/k8s/scripts/_game-mode-helpers.ps1')

function Import-DeployLocalEnv {
    param([string]$Path)
    if (-not (Test-Path $Path)) {
        return
    }
    Get-Content $Path | ForEach-Object {
        $line = $_.Trim()
        if ($line -eq '' -or $line.StartsWith('#')) { return }
        $eq = $line.IndexOf('=')
        if ($eq -lt 1) { return }
        $name = $line.Substring(0, $eq).Trim()
        $value = $line.Substring($eq + 1).Trim().Trim('"').Trim("'")
        if ($name) { Set-Item -Path "Env:$name" -Value $value }
    }
}

Import-DeployLocalEnv (Join-Path $PSScriptRoot 'infra/deploy.local.env')

$nodePassword = $env:SKYPVP_NODE_SSH_PASSWORD
$node1Target = $env:SKYPVP_NODE1_SSH_TARGET
$node2Target = $env:SKYPVP_NODE2_SSH_TARGET
if ([string]::IsNullOrWhiteSpace($nodePassword)) {
    throw "SKYPVP_NODE_SSH_PASSWORD is not set. Copy infra/deploy.local.env.example to infra/deploy.local.env."
}
if ([string]::IsNullOrWhiteSpace($node1Target) -or [string]::IsNullOrWhiteSpace($node2Target)) {
    throw "SKYPVP_NODE1_SSH_TARGET and SKYPVP_NODE2_SSH_TARGET must be set in infra/deploy.local.env."
}

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
$escapedPassword = $nodePassword.Replace("'", "'\\''")
$escapedTarget1 = $node1Target.Replace("'", "'\\''")
$sshCmd = "apk add --no-cache openssh-client sshpass && sshpass -p '$escapedPassword' scp -o StrictHostKeyChecking=no /workspace/game-images-unique.tar ${escapedTarget1}:/tmp/game-images-unique.tar && sshpass -p '$escapedPassword' ssh -o StrictHostKeyChecking=no $escapedTarget1 ""echo '$escapedPassword' | sudo -S k3s ctr -n k8s.io images import /tmp/game-images-unique.tar"""
[IO.File]::WriteAllText('import-images-node1.sh', $sshCmd)
docker run --rm -v "${PWD}:/workspace" -w /workspace alpine sh import-images-node1.sh

Write-Host 'Importing to node-2 (extraction)...' -ForegroundColor Cyan
$escapedTarget2 = $node2Target.Replace("'", "'\\''")
$sshCmd2 = "apk add --no-cache openssh-client sshpass && sshpass -p '$escapedPassword' scp -o StrictHostKeyChecking=no /workspace/game-images-unique.tar ${escapedTarget2}:/tmp/game-images-unique.tar && sshpass -p '$escapedPassword' ssh -o StrictHostKeyChecking=no $escapedTarget2 ""echo '$escapedPassword' | sudo -S k3s ctr -n k8s.io images import /tmp/game-images-unique.tar"""
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
