# Imports the Eclipse-Studios/backpacks (Apache-2.0) tier 1-4 backpack models, textures,
# and skin variants into the skypvp-core resource pack.
#
# Source layout (github.com/Eclipse-Studios/backpacks):
#   resourcepack/assets/backpacks/models/item/backpacks/normal/<tier>/<skin>.json
#   resourcepack/assets/backpacks/textures/item/backpacks/normal/<tier>/<skin>.png
#
# Local layout produced:
#   assets/skypvp/models/item/backpack/t<tier>/<skin>.json   (namespaces + display rewritten)
#   assets/skypvp/textures/item/backpack/t<tier>/<skin>.png  (christmas keeps its .mcmeta animation)
#   assets/skypvp/items/backpack_t<tier>_<skin>.json         (item asset -> model)
#
# Base item material (server): PAPER — not BUNDLE. Bundle is a usable offhand item and steals
# right-click from WeaponMechanics guns. Appearance comes from the item_model component
# pointing at skypvp:backpack_t<tier>_<skin> (these item assets), so the pack does NOT
# override minecraft:items/bundle.json.
#
# Models are imported VERBATIM — geometry AND display transforms. Their datapack holds the
# backpack in the OFFHAND exactly like our reserved-offhand system does, and their
# thirdperson transforms (rot [69,0,0], tr [-5.5,-10,3.25], sc 0.71) are precisely what
# renders the pack on the wearer's back, front side out (see their Modrinth screenshots).
# Earlier import attempts replaced those transforms with computed ones and the pack never
# appeared — do not "fix" their display block. The ONLY changes: texture references are
# re-namespaced, and first-person is zeroed (our offhand permanently holds the pack, so it
# must never block the first-person view-model).

$ErrorActionPreference = "Stop"

$rawBase = "https://raw.githubusercontent.com/Eclipse-Studios/backpacks/main/resourcepack/assets/backpacks"
$packRoot = Join-Path $PSScriptRoot "..\..\resource-packs\skypvp-core\assets\skypvp"
$skins = @(
    "brown", "black", "blue", "cyan", "gray", "green", "light_blue", "light_gray",
    "lime", "magenta", "orange", "pink", "purple", "red", "white", "yellow",
    "autumnal", "christmas", "nether"
)

function Get-Remote([string]$relative, [string]$destination) {
    New-Item -ItemType Directory -Force (Split-Path $destination) | Out-Null
    Invoke-WebRequest -Uri "$rawBase/$relative" -OutFile $destination -UseBasicParsing
}

foreach ($tier in 1..4) {
    foreach ($skin in $skins) {
        $modelDir = Join-Path $packRoot "models\item\backpack\t$tier"
        $textureDir = Join-Path $packRoot "textures\item\backpack\t$tier"
        $itemsDir = Join-Path $packRoot "items"
        New-Item -ItemType Directory -Force $modelDir, $textureDir, $itemsDir | Out-Null

        $modelPath = Join-Path $modelDir "$skin.json"
        Get-Remote "models/item/backpacks/normal/$tier/$skin.json" $modelPath
        Get-Remote "textures/item/backpacks/normal/$tier/$skin.png" (Join-Path $textureDir "$skin.png")
        if ($skin -eq "christmas") {
            Get-Remote "textures/item/backpacks/normal/$tier/christmas.png.mcmeta" (Join-Path $textureDir "christmas.png.mcmeta")
        }

        $model = Get-Content $modelPath -Raw | ConvertFrom-Json

        # Retarget every texture reference into our namespace.
        foreach ($property in $model.textures.PSObject.Properties) {
            $property.Value = $property.Value -replace "^backpacks:item/backpacks/normal/$tier/", "skypvp:item/backpack/t$tier/"
        }

        # Keep their display transforms verbatim; only hide the pack in first person.
        $hidden = [pscustomobject]@{ scale = @(0, 0, 0) }
        $model.display.firstperson_righthand = $hidden
        $model.display.firstperson_lefthand = $hidden

        $model | ConvertTo-Json -Depth 100 | Set-Content $modelPath -Encoding UTF8

        $itemAsset = [ordered]@{
            model = [ordered]@{
                type  = "minecraft:model"
                model = "skypvp:item/backpack/t$tier/$skin"
            }
        }
        $itemAsset | ConvertTo-Json -Depth 10 | Set-Content (Join-Path $itemsDir "backpack_t${tier}_$skin.json") -Encoding UTF8

        Write-Host "tier $tier skin $skin -> imported verbatim (firstperson hidden)"
    }
}

Write-Host "Imported $($skins.Count * 4) backpack skin models."
