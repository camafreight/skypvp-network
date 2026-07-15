# Merges the official WeaponMechanics ResourcePack into resource-packs/skypvp-core.
# Keeps SkyPvP laser assets and injects Laser Carbine CMD 18 / 1018 / 2018 into feather.json.
# (WM RelativeSkin Default must be 1..999 — high CMDs like 9100 serialize as vanilla feather.)
# IMPORTANT: keep WM's short-form item definition types (range_dispatch / model / custom_model_data).
# Forcing minecraft: prefixes or index broke every weapon into missing-texture cubes on 1.21.11.

$ErrorActionPreference = "Stop"

$NetworkRoot = (Resolve-Path (Join-Path $PSScriptRoot "..\..")).Path
$CoreRoot = Join-Path $NetworkRoot "resource-packs\skypvp-core"
$TmpRoot = Join-Path $NetworkRoot "resource-packs\_wm-tmp"
$WmRoot = Join-Path $TmpRoot "ResourcePack-3.0.0"
$SrcZip = Join-Path $TmpRoot "wm-src.zip"
$WmUrl = "https://github.com/WeaponMechanics/ResourcePack/archive/refs/tags/v3.0.0.zip"

if (-not (Test-Path $CoreRoot)) {
    throw "Missing skypvp-core pack at $CoreRoot"
}

New-Item -ItemType Directory -Force -Path $TmpRoot | Out-Null

if (-not (Test-Path (Join-Path $WmRoot "assets"))) {
    Write-Host "Downloading WeaponMechanics ResourcePack v3.0.0..."
    Invoke-WebRequest -Uri $WmUrl -OutFile $SrcZip -UseBasicParsing
    Expand-Archive -Path $SrcZip -DestinationPath $TmpRoot -Force
}

if (-not (Test-Path (Join-Path $WmRoot "assets"))) {
    throw "WM pack extract missing assets under $WmRoot"
}

Write-Host "Copying WM models/textures/sounds into skypvp-core..."
$mcAssets = Join-Path $CoreRoot "assets\minecraft"
New-Item -ItemType Directory -Force -Path $mcAssets | Out-Null

function Invoke-RobocopyOk([string]$Source, [string]$Dest) {
    & robocopy $Source $Dest /E /NFL /NDL /NJH /NJS /nc /ns /np | Out-Null
    if ($LASTEXITCODE -ge 8) {
        throw "robocopy failed ($LASTEXITCODE): $Source -> $Dest"
    }
}

Invoke-RobocopyOk (Join-Path $WmRoot "assets\minecraft\models") (Join-Path $mcAssets "models")
Invoke-RobocopyOk (Join-Path $WmRoot "assets\minecraft\textures") (Join-Path $mcAssets "textures")
Invoke-RobocopyOk (Join-Path $WmRoot "assets\minecraft\sounds") (Join-Path $mcAssets "sounds")
Copy-Item (Join-Path $WmRoot "assets\minecraft\sounds.json") (Join-Path $mcAssets "sounds.json") -Force

Write-Host "Building merged feather.json (WM format + SkyPvP laser carbine)..."
$wmFeatherPath = Join-Path $WmRoot "assets\minecraft\items\feather.json"
$outItems = Join-Path $mcAssets "items"
New-Item -ItemType Directory -Force -Path $outItems | Out-Null
$outFeather = Join-Path $outItems "feather.json"
$outCrossbow = Join-Path $outItems "crossbow.json"

$mergeJsPath = Join-Path $TmpRoot "merge-feather.js"
@'
const fs = require("fs");
const inputPath = process.argv[2];
const outputPath = process.argv[3];
const wm = JSON.parse(fs.readFileSync(inputPath, "utf8"));
const m = wm.model;
// 1.21.4+ reads CustomModelData floats at index 0. Keep WM model paths (item/weapons/*).
m.type = "minecraft:range_dispatch";
m.property = "minecraft:custom_model_data";
m.index = 0;
m.fallback.type = "minecraft:model";
for (const entry of m.entries) {
  entry.model.type = "minecraft:model";
}
const extras = [
  { threshold: 18, model: { type: "minecraft:model", model: "item/weapons/laser_carbine" } },
  { threshold: 1018, model: { type: "minecraft:model", model: "item/weapons/laser_carbineaiming" } },
  { threshold: 2018, model: { type: "minecraft:model", model: "item/weapons/laser_carbinesprinting" } },
];
const seen = new Set(m.entries.map((e) => e.threshold));
for (const entry of extras) {
  if (!seen.has(entry.threshold)) m.entries.push(entry);
}
m.entries.sort((a, b) => a.threshold - b.threshold);
fs.writeFileSync(outputPath, JSON.stringify({ hand_animation_on_swap: wm.hand_animation_on_swap, model: m }, null, 4) + "\n");
'@ | Set-Content -Path $mergeJsPath -Encoding utf8

& node $mergeJsPath $wmFeatherPath $outFeather
if ($LASTEXITCODE -ne 0) {
    throw "feather.json merge failed"
}
if (-not (Select-String -Path $outFeather -Pattern '"threshold": 18' -Quiet)) {
    throw "feather.json missing laser carbine threshold 18"
}

# Guns use CROSSBOW material for the loaded-hold arm pose; mirror the same CMD models.
Copy-Item $outFeather $outCrossbow -Force
Write-Host "Mirrored feather.json -> crossbow.json (shooting pose base item)"

Write-Host "Merged pack ready at $CoreRoot"
Write-Host "Next: ./infra/scripts/Sync-SkyPvPResourcePack.ps1"
