# Syncs resource-packs/skypvp-core → skypvp-web/public/pack for Cloudflare HTTPS hosting.
# Run from skypvp-network after pack asset changes, then redeploy skypvp-web + update proxy SHA1 env.

$ErrorActionPreference = "Stop"

$NetworkRoot = (Resolve-Path (Join-Path $PSScriptRoot "..\..")).Path

function Resolve-SkyPvPWebRoot([string]$NetworkRootPath) {
    $candidates = @(
        (Join-Path $NetworkRootPath "..\skypvp-web"),
        "E:\Minecraft\skypvp-web",
        (Join-Path $env:USERPROFILE "Documents\skypvp-web")
    )
    if ($env:SKYPVP_WEB_ROOT) {
        $candidates = @($env:SKYPVP_WEB_ROOT) + $candidates
    }
    foreach ($candidate in $candidates) {
        if (-not $candidate) { continue }
        if (Test-Path -LiteralPath $candidate) {
            return (Resolve-Path -LiteralPath $candidate).Path
        }
    }
    throw "skypvp-web not found. Set SKYPVP_WEB_ROOT or place it next to skypvp-network. Tried: $($candidates -join ', ')"
}

$WebRoot = Resolve-SkyPvPWebRoot $NetworkRoot

Write-Host "Network root: $NetworkRoot"
Write-Host "Web root:     $WebRoot"

# Keep ESC menu glyph overrides on every client locale (not just en_us).
& (Join-Path $PSScriptRoot "Sync-EscapeMenuLangs.ps1")

Push-Location $NetworkRoot
try {
    ./gradlew :proxy:velocity-core:packageSkyPvPResourcePack --quiet
} finally {
    Pop-Location
}

$zip = Join-Path $NetworkRoot "proxy\velocity-core\build\resource-pack\skypvp-core.zip"
if (-not (Test-Path $zip)) {
    throw "Pack zip missing: $zip"
}

function Sync-PackArtifacts([string]$TargetDir) {
    New-Item -ItemType Directory -Force -Path $TargetDir | Out-Null
    $targetZip = Join-Path $TargetDir "skypvp-core.zip"
    Copy-Item $zip $targetZip -Force

    $sha1 = (Get-FileHash $targetZip -Algorithm SHA1).Hash.ToLower()
    # UTF-8 without BOM — PowerShell Set-Content -Encoding utf8 writes a BOM that breaks
    # Velocity's meta JSON parser (body.startsWith("{") fails) and Node JSON.parse.
    $utf8NoBom = New-Object System.Text.UTF8Encoding $false
    [System.IO.File]::WriteAllText((Join-Path $TargetDir "skypvp-core.sha1"), $sha1, $utf8NoBom)

    $publicUrl = "https://skypvp.gg/pack/skypvp-core.zip"
    $meta = [ordered]@{
        file        = "skypvp-core.zip"
        sha1        = $sha1
        url         = $publicUrl
        downloadUrl = "$publicUrl`?h=$sha1"
        updatedAt   = (Get-Date).ToUniversalTime().ToString("o")
    }
    $metaJson = $meta | ConvertTo-Json -Compress
    [System.IO.File]::WriteAllText((Join-Path $TargetDir "skypvp-core.meta.json"), $metaJson, $utf8NoBom)
    return $sha1
}

$publicDir = Join-Path $WebRoot "public\pack"
$sha1 = Sync-PackArtifacts $publicDir

# nginx Docker image serves /usr/share/nginx/html from vite dist — keep dist/pack in sync
# so a plain `npm run build` + image build does not resurrect an old zip.
$distDir = Join-Path $WebRoot "dist\pack"
if (Test-Path (Join-Path $WebRoot "dist")) {
    Sync-PackArtifacts $distDir | Out-Null
    Write-Host "Mirrored pack into $distDir"
}

Write-Host "Synced $(Join-Path $publicDir 'skypvp-core.zip')"
Write-Host "SHA1    $sha1"
Write-Host "Meta    $publicDir\skypvp-core.meta.json"
Write-Host ""
Write-Host "Proxy can poll without restart:"
Write-Host "  SPVP_RESOURCE_PACK_META_URL=https://skypvp.gg/api/pack"
Write-Host "  (or https://skypvp.gg/pack/skypvp-core.meta.json)"
Write-Host "  SPVP_RESOURCE_PACK_URL=https://skypvp.gg/pack/skypvp-core.zip"
Write-Host "  SPVP_RESOURCE_PACK_SHA1=   # optional bootstrap only"
Write-Host ""
Write-Host "Next:"
Write-Host "  1. Redeploy skypvp-web so clients/proxy can fetch the new zip + meta"
Write-Host "  2. No proxy restart needed when META_URL is configured"
