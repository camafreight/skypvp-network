# Removes stale/duplicate resource-pack assets. Prefers newest skypvp namespace over
# legacy minecraft copies, and drops superseded placeholder item ids.
#
# Run before Sync-SkyPvPResourcePack.ps1 when art looks wrong on live clients.

$ErrorActionPreference = "Stop"

$NetworkRoot = (Resolve-Path (Join-Path $PSScriptRoot "..\..")).Path
$PackRoot = Join-Path $NetworkRoot "resource-packs\skypvp-core"

if (-not (Test-Path $PackRoot)) {
    throw "Missing pack root: $PackRoot"
}

# Early placeholder ids superseded by crafting/material naming (materials.json + Assign-UnassignedItemArt).
$obsoleteItemBases = @(
    "mat_alloy",
    "mat_chem",
    "mat_circuit",
    "mat_cloth",
    "mat_crystal",
    "mat_scrap_metal",
    "medic_bandage",
    "medic_syringe"
)

# Explicit stale minecraft assets; skypvp:laser_beam is canonical.
$staleMinecraftRelPaths = @(
    "assets\minecraft\textures\item\laser_beam.png",
    "assets\minecraft\models\item\laser_beam.json"
)

# WM merge must never clobber custom SkyPvP weapon models.
$protectedMinecraftRelPrefixes = @(
    "assets\minecraft\models\item\weapons\laser_carbine",
    "assets\minecraft\items\feather.json",
    "assets\minecraft\items\crossbow.json"
)

function Test-ProtectedMinecraftPath([string]$RelPath) {
    $normalized = $RelPath.Replace('/', '\')
    foreach ($prefix in $protectedMinecraftRelPrefixes) {
        if ($normalized.StartsWith($prefix, [System.StringComparison]::OrdinalIgnoreCase)) {
            return $true
        }
    }
    return $false
}

function Remove-PackFile([string]$RelPath, [string]$Reason) {
    $full = Join-Path $PackRoot $RelPath
    if (-not (Test-Path $full)) {
        return
    }
    Remove-Item $full -Force
    Write-Host "removed  $RelPath  ($Reason)"
}

$removed = 0

Write-Host "Pack root: $PackRoot"
Write-Host ""

Write-Host "== Obsolete skypvp placeholder items =="
foreach ($base in $obsoleteItemBases) {
    foreach ($suffix in @(
        "assets\skypvp\textures\item\$base.png",
        "assets\skypvp\models\item\$base.json",
        "assets\skypvp\items\$base.json"
    )) {
        $full = Join-Path $PackRoot $suffix
        if (Test-Path $full) {
            Remove-PackFile $suffix "superseded id"
            $removed++
        }
    }
}

Write-Host ""
Write-Host "== Stale minecraft laser_beam copies =="
foreach ($rel in $staleMinecraftRelPaths) {
    $full = Join-Path $PackRoot $rel
    if (Test-Path $full) {
        Remove-PackFile $rel "skypvp:laser_beam is canonical"
        $removed++
    }
}

Write-Host ""
Write-Host "== Cross-namespace basename conflicts (keep newest; prefer skypvp) =="
$mcRoot = Join-Path $PackRoot "assets\minecraft"
$spRoot = Join-Path $PackRoot "assets\skypvp"
$mcFiles = @{}
if (Test-Path $mcRoot) {
    Get-ChildItem $mcRoot -Recurse -File | ForEach-Object {
        $mcFiles[$_.Name] = $_
    }
}
if (Test-Path $spRoot) {
    Get-ChildItem $spRoot -Recurse -File | ForEach-Object {
        $spFile = $_
        if (-not $mcFiles.ContainsKey($spFile.Name)) {
            return
        }
        $mcFile = $mcFiles[$spFile.Name]
        $mcRel = $mcFile.FullName.Substring($PackRoot.Length + 1)
        if (Test-ProtectedMinecraftPath $mcRel) {
            return
        }
        # items/*.json + models/item/*.json in both namespaces are normal wiring, not conflicts.
        if ($spFile.Name.EndsWith(".json") -and $mcRel -match '\\items\\' -and $spFile.FullName -match '\\items\\') {
            return
        }
        if ($spFile.Name.EndsWith(".json") -and $mcRel -match '\\models\\item\\' -and $spFile.FullName -match '\\models\\item\\') {
            return
        }

        $keepSp = $false
        if ($spFile.LastWriteTime -gt $mcFile.LastWriteTime) {
            $keepSp = $true
        } elseif ($spFile.LastWriteTime -eq $mcFile.LastWriteTime -and $spFile.Length -ge $mcFile.Length) {
            $keepSp = $true
        }

        if ($keepSp) {
            Remove-PackFile $mcRel "older minecraft duplicate of $($spFile.FullName.Substring($PackRoot.Length + 1))"
            $removed++
        } else {
            $spRel = $spFile.FullName.Substring($PackRoot.Length + 1)
            Remove-PackFile $spRel "older skypvp duplicate of $mcRel"
            $removed++
        }
    }
}

Write-Host ""
Write-Host "Removed $removed stale/duplicate file(s)."
Write-Host "Next: ./infra/scripts/Sync-SkyPvPResourcePack.ps1"
