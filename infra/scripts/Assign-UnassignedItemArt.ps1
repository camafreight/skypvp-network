# Assigns hand-made art dropped in resource-packs/_source_art/unassigned_textures (or the
# legacy assets/skypvp/unassigned_textures location) to the pack's item texture names:
#   <name>.png -> mat_<name> / medic_<name> / shield_module / blueprint (see $map below)
#
# Each source image (any size, transparent background) is:
#   1. converted to premultiplied alpha so bicubic downscaling can't ring dark halos,
#   2. cropped to its content bounding box (found on a 96px thumbnail) and re-framed as a
#      centered square so every item fills its slot consistently,
#   3. downscaled to 62x62 inside a 64x64 canvas (1px safety margin),
#   4. alpha-cleaned: A<48 -> 0 (kills fringe ghosts), A>200 -> 255 (solid body stays solid).
# models/item + items wiring is (re)written for every assigned name — idempotent for the
# mat_/medic_ names that already have wiring from Generate-CraftingItemArt.ps1.
Add-Type -AssemblyName System.Drawing

$packRoot = Join-Path $PSScriptRoot "..\..\resource-packs\skypvp-core"
$texRoot = Join-Path $packRoot "assets\skypvp\textures\item"
$modelRoot = Join-Path $packRoot "assets\skypvp\models\item"
$itemsRoot = Join-Path $packRoot "assets\skypvp\items"

$srcDir = Join-Path $PSScriptRoot "..\..\resource-packs\_source_art\unassigned_textures"
if (-not (Test-Path $srcDir)) {
    $srcDir = Join-Path $packRoot "assets\skypvp\unassigned_textures"
}
if (-not (Test-Path $srcDir)) {
    throw "No unassigned_textures folder found."
}

$map = @{
    'adrenaline_shot'    = 'medic_adrenaline_shot'
    'bandage_rag'        = 'medic_bandage_rag'
    'medkit'             = 'medic_medkit'
    'overdrive_serum'    = 'medic_overdrive_serum'
    'stamina_stabilizer' = 'medic_stamina_stabilizer'
    'sterile_bandage'    = 'medic_sterile_bandage'
    'surgical_kit'       = 'medic_surgical_kit'
    'aether_resin'       = 'mat_aether_resin'
    'alloy_plate'        = 'mat_alloy_plate'
    'capacitor_cell'     = 'mat_capacitor_cell'
    'cloth_scrap'        = 'mat_cloth_scrap'
    'fiber_bundle'       = 'mat_fiber_bundle'
    'field_suture'       = 'mat_field_suture'
    'metal_shards'       = 'mat_metal_shards'
    'polymer_sheet'      = 'mat_polymer_sheet'
    'quantum_gel'        = 'mat_quantum_gel'
    'stim_compound'      = 'mat_stim_compound'
    'shield_module'      = 'shield_module'
    'blueprint'          = 'blueprint'
}

function Convert-To64([string]$sourcePath, [string]$targetPath) {
    $src = New-Object System.Drawing.Bitmap $sourcePath
    try {
        # Premultiplied working copy: bicubic filtering in straight alpha bleeds the
        # (usually black) RGB of transparent pixels into edges as a dark halo.
        $pre = New-Object System.Drawing.Bitmap($src.Width, $src.Height, [System.Drawing.Imaging.PixelFormat]::Format32bppPArgb)
        $g = [System.Drawing.Graphics]::FromImage($pre)
        $g.DrawImage($src, 0, 0, $src.Width, $src.Height)
        $g.Dispose()

        # Content bounding box from a small thumbnail (fast, precise enough for framing).
        $thumbSize = 96
        $thumb = New-Object System.Drawing.Bitmap($thumbSize, $thumbSize, [System.Drawing.Imaging.PixelFormat]::Format32bppPArgb)
        $gt = [System.Drawing.Graphics]::FromImage($thumb)
        $gt.InterpolationMode = [System.Drawing.Drawing2D.InterpolationMode]::HighQualityBicubic
        $gt.DrawImage($pre, 0, 0, $thumbSize, $thumbSize)
        $gt.Dispose()
        $minX = $thumbSize; $minY = $thumbSize; $maxX = -1; $maxY = -1
        for ($y = 0; $y -lt $thumbSize; $y++) {
            for ($x = 0; $x -lt $thumbSize; $x++) {
                if ($thumb.GetPixel($x, $y).A -gt 8) {
                    if ($x -lt $minX) { $minX = $x }
                    if ($x -gt $maxX) { $maxX = $x }
                    if ($y -lt $minY) { $minY = $y }
                    if ($y -gt $maxY) { $maxY = $y }
                }
            }
        }
        $thumb.Dispose()
        if ($maxX -lt 0) { throw "$sourcePath is fully transparent" }

        # Thumb bbox -> source coords, padded, squared around the content center.
        $sx = $src.Width / [double]$thumbSize
        $sy = $src.Height / [double]$thumbSize
        $bx = [Math]::Max(0.0, ($minX - 1.5) * $sx)
        $by = [Math]::Max(0.0, ($minY - 1.5) * $sy)
        $bw = [Math]::Min($src.Width - $bx, ($maxX - $minX + 3.0) * $sx)
        $bh = [Math]::Min($src.Height - $by, ($maxY - $minY + 3.0) * $sy)
        $side = [Math]::Max($bw, $bh)
        $cx = $bx + $bw / 2.0
        $cy = $by + $bh / 2.0
        $rx = [single][Math]::Max(0.0, [Math]::Min($src.Width - $side, $cx - $side / 2.0))
        $ry = [single][Math]::Max(0.0, [Math]::Min($src.Height - $side, $cy - $side / 2.0))
        $srcRect = New-Object System.Drawing.RectangleF($rx, $ry, [single]$side, [single]$side)

        $dst = New-Object System.Drawing.Bitmap(64, 64, [System.Drawing.Imaging.PixelFormat]::Format32bppPArgb)
        $gd = [System.Drawing.Graphics]::FromImage($dst)
        $gd.InterpolationMode = [System.Drawing.Drawing2D.InterpolationMode]::HighQualityBicubic
        $gd.PixelOffsetMode = [System.Drawing.Drawing2D.PixelOffsetMode]::HighQuality
        $gd.CompositingQuality = [System.Drawing.Drawing2D.CompositingQuality]::HighQuality
        $destRect = New-Object System.Drawing.RectangleF(1.0, 1.0, 62.0, 62.0)
        $gd.DrawImage($pre, $destRect, $srcRect, [System.Drawing.GraphicsUnit]::Pixel)
        $gd.Dispose()
        $pre.Dispose()

        # Straight-alpha output + fringe cleanup.
        $out = New-Object System.Drawing.Bitmap(64, 64, [System.Drawing.Imaging.PixelFormat]::Format32bppArgb)
        for ($y = 0; $y -lt 64; $y++) {
            for ($x = 0; $x -lt 64; $x++) {
                $c = $dst.GetPixel($x, $y)
                $a = [int]$c.A
                if ($a -lt 48) { continue }
                if ($a -gt 200) { $a = 255 }
                $out.SetPixel($x, $y, [System.Drawing.Color]::FromArgb($a, $c.R, $c.G, $c.B))
            }
        }
        $dst.Dispose()
        $out.Save($targetPath, [System.Drawing.Imaging.ImageFormat]::Png)
        $out.Dispose()
    } finally {
        $src.Dispose()
    }
}

foreach ($entry in $map.GetEnumerator() | Sort-Object Key) {
    $sourcePath = Join-Path $srcDir ($entry.Key + ".png")
    if (-not (Test-Path $sourcePath)) {
        Write-Host "skip $($entry.Key) (no source image)"
        continue
    }
    $name = $entry.Value
    Convert-To64 $sourcePath (Join-Path $texRoot "$name.png")
    Set-Content -Path (Join-Path $modelRoot "$name.json") -Encoding ascii -Value (
        '{ "parent": "minecraft:item/generated", "textures": { "layer0": "skypvp:item/' + $name + '" } }')
    Set-Content -Path (Join-Path $itemsRoot "$name.json") -Encoding ascii -Value (
        '{ "model": { "type": "minecraft:model", "model": "skypvp:item/' + $name + '" } }')
    Write-Host "assigned $($entry.Key) -> $name"
}
Write-Host "Done."
