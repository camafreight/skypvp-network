# One-command full network deploy:
#   1. Game servers + proxy images (gradle deployJars -> docker -> nodes) via deploy-unique-run.ps1
#   2. skypvp-web image (resource pack hosting) -> master node + rollout
#   3. Proxy manifest apply (resource-pack SHA env) AFTER the web serves the new pack
# Prereq: web-image.tar exists (docker save of skypvp-web:latest) or skypvp-web:latest is built.
#Requires -Version 5.1
Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'

$root = $PSScriptRoot

# --- credentials (same source as deploy-unique-run.ps1) ---------------------------------
$vars = @{}
Get-Content (Join-Path $root 'infra/deploy.local.env') | ForEach-Object {
    $line = $_.Trim()
    if ($line -and -not $line.StartsWith('#') -and $line.Contains('=')) {
        $k, $v = $line.Split('=', 2); $vars[$k.Trim()] = $v.Trim().Trim('"').Trim("'")
    }
}
$sshUser = $vars['SKYPVP_NODE1_SSH_TARGET'].Split('@')[0]
# Master node has its own password; target key accepts the SSG typo variant.
$password = if ($vars.ContainsKey('SKYPVP_MASTER_SSH_PASSWORD')) { $vars['SKYPVP_MASTER_SSH_PASSWORD'] } else { $vars['SKYPVP_NODE_SSH_PASSWORD'] }
$masterTarget = if ($vars.ContainsKey('SKYPVP_MASTER_SSH_TARGET')) { $vars['SKYPVP_MASTER_SSH_TARGET'] }
    elseif ($vars.ContainsKey('SKYPVP_MASTER_SSG_TARGET')) { $vars['SKYPVP_MASTER_SSG_TARGET'] }
    else { "$sshUser@192.168.0.25" }

# --- 1. game images + backend rollouts ----------------------------------------------------
& (Join-Path $root 'deploy-unique-run.ps1')

# --- 2. skypvp-web image (pack hosting) via image-loader on master -----------------------
# Always rebuild: the image bakes public/pack, and a stale tar would silently ship an old pack.
docker build -t skypvp-web:latest (Join-Path $root '..\skypvp-web')
$webTar = Join-Path $root 'web-image.tar'
docker save -o $webTar skypvp-web:latest

kubectl delete pod image-loader-master -n default --grace-period=0 --ignore-not-found | Out-Null
kubectl apply -f (Join-Path $root '..\skypvp-web\k8s\cluster\image-loader-master.yaml') | Out-Null
kubectl wait pod/image-loader-master -n default --for=condition=Ready --timeout=120s
Push-Location $root
try {
    kubectl cp .\web-image.tar default/image-loader-master:/tmp/web-image.tar
} finally {
    Pop-Location
}
kubectl exec -n default image-loader-master -- /host/usr/local/bin/k3s ctr --address /host/run/k3s/containerd/containerd.sock --namespace k8s.io images import /tmp/web-image.tar
kubectl -n skypvp-web rollout restart deployment/skypvp-web
kubectl -n skypvp-web rollout status deployment/skypvp-web --timeout=180s

# --- 3. verify pack (+ meta) is served, then apply proxy (META_URL polling; SHA1 env optional) ---
function Read-RemoteText([string]$Url) {
    $response = Invoke-WebRequest -UseBasicParsing $Url
    $raw = $response.Content
    if ($raw -is [byte[]]) {
        return [System.Text.Encoding]::UTF8.GetString($raw).Trim()
    }
    return ([string]$raw).Trim()
}

$expected = (Get-Content (Join-Path $root '..\skypvp-web\public\pack\skypvp-core.sha1') -Raw).Trim()
$served = Read-RemoteText "https://skypvp.gg/pack/skypvp-core.sha1?h=$expected"
if ($served -ne $expected) {
    throw "Pack SHA mismatch: served=$served expected=$expected — NOT applying proxy manifest."
}
$metaUrl = "https://skypvp.gg/pack/skypvp-core.meta.json?h=$expected"
try {
    $meta = (Read-RemoteText $metaUrl) | ConvertFrom-Json
    if ($meta.sha1 -ne $expected) {
        throw "Pack meta SHA mismatch: meta=$($meta.sha1) expected=$expected"
    }
    Write-Host "Pack + meta verified: $($meta.sha1)"
} catch {
    throw "Pack meta unavailable at $metaUrl — redeploy web first. $_"
}
kubectl apply -f (Join-Path $root 'infra/k8s/velocity-proxy.yaml')
kubectl -n skypvp-network rollout status deployment/skypvp-proxy --timeout=300s
Write-Host 'Full deploy completed. Proxy will refresh SHA1 from meta without further restarts.'
