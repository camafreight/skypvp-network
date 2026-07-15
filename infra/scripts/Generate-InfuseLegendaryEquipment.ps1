# Generates the "Aetherforged" legendary Infuse armor equipment model:
#   - textures/entity/equipment/humanoid/infuse_legendary.png          (helmet/chest/arms/boots)
#   - textures/entity/equipment/humanoid_leggings/infuse_legendary.png (waist tassets + legs)
#   - textures/entity/equipment/wings/infuse_legendary.png             (3D cape via the elytra wings layer)
#   - assets/skypvp/equipment/infuse_legendary.json                    (layer wiring)
#
# Layers use the vanilla 64x32 armor UV layout, rendered at 2x (128x64) for detail.
# Theme: near-black alloy plate, molten gold trim, glowing aether core lines, and a
# runed cape with a ragged hem. Legendary pieces opt in server-side via the
# EQUIPPABLE component (assetId skypvp:infuse_legendary) — see ExtractionCustomItemProvider.
Add-Type -AssemblyName System.Drawing

$packRoot = Join-Path $PSScriptRoot "..\..\resource-packs\skypvp-core"
$eqTexRoot = Join-Path $packRoot "assets\skypvp\textures\entity\equipment"
$eqDefRoot = Join-Path $packRoot "assets\skypvp\equipment"
foreach ($d in @((Join-Path $eqTexRoot "humanoid"), (Join-Path $eqTexRoot "humanoid_leggings"), (Join-Path $eqTexRoot "wings"), $eqDefRoot)) {
    New-Item -ItemType Directory -Force $d | Out-Null
}

$S = 2          # render scale: 1 native UV unit = 2 px
# NOTE: PowerShell variables are case-insensitive — these must NOT be named $W/$H or the
# $w/$h rect parameters of FillR/ClearR shadow them and the bounds checks break.
$CanvasW = 64 * $S
$CanvasH = 32 * $S

# Palette
$PLATE   = @(0x2A, 0x30, 0x38)   # dark alloy
$PLATE_D = @(0x1C, 0x21, 0x28)   # recessed plate
$TRIM    = @(0xE8, 0xB5, 0x4E)   # molten gold trim
$GLOW    = @(0xFF, 0xE9, 0xA6)   # emissive gold
$CYAN    = @(0x35, 0xE0, 0xF0)   # aether accent
$CLOTH   = @(0x20, 0x25, 0x2E)   # cape cloth

function Get-Noise([int]$x, [int]$y, [int]$seed) {
    $h = ($x * 374761393 + $y * 668265263 + $seed * 987643211) -band 0x7FFFFFFF
    $h = (($h -bxor ($h -shr 13)) * 1274126177) -band 0x7FFFFFFF
    return ((($h -band 0xFFFF) / 65535.0) * 2.0) - 1.0
}

# Fills a native-UV rect [$u,$v,$w,$h] (fractions allowed) with a styled surface.
# Styles: plate, plated (recessed), trim (gold bevel), glow, glowc (cyan), cloth.
function FillR($bmp, [double]$u, [double]$v, [double]$w, [double]$h, [string]$style, [int]$seed) {
    $x0 = [int][Math]::Round($u * $S); $y0 = [int][Math]::Round($v * $S)
    $x1 = [int][Math]::Round(($u + $w) * $S) - 1; $y1 = [int][Math]::Round(($v + $h) * $S) - 1
    for ($y = $y0; $y -le $y1; $y++) {
        for ($x = $x0; $x -le $x1; $x++) {
            if ($x -lt 0 -or $y -lt 0 -or $x -ge $CanvasW -or $y -ge $CanvasH) { continue }
            switch ($style) {
                'plate'  { $c = $PLATE }
                'plated' { $c = $PLATE_D }
                'trim'   { $c = $TRIM }
                'glow'   { $c = $GLOW }
                'glowc'  { $c = $CYAN }
                'cloth'  { $c = $CLOTH }
                default  { $c = $PLATE }
            }
            $r = [double]$c[0]; $g = [double]$c[1]; $b = [double]$c[2]
            $span = [Math]::Max(1, $y1 - $y0)
            $f = 1.08 - 0.16 * (($y - $y0) / $span)
            # Bevel: light top/left rim, dark bottom/right rim of the rect.
            if ($y -eq $y0 -or $x -eq $x0) { $f *= 1.22 }
            elseif ($y -eq $y1 -or $x -eq $x1) { $f *= 0.72 }
            switch ($style) {
                'plate'  { $f *= 1.0 + 0.05 * [Math]::Sin($x * 0.6 + (Get-Noise 0 $y $seed) * 6.28) }
                'plated' { $f *= 1.0 + 0.04 * [Math]::Sin($x * 0.6 + (Get-Noise 0 $y $seed) * 6.28) }
                'cloth'  { $f *= 1.0 + 0.06 * (((([int]($x / 1) + [int]($y / 2)) % 2) * 2) - 1) }
                'glow'   { $f *= 1.05 }
                'glowc'  { $f *= 1.05 }
            }
            $f *= 1.0 + 0.04 * (Get-Noise $x $y $seed)
            $ri = [int][Math]::Min(255, [Math]::Max(0, $r * $f))
            $gi = [int][Math]::Min(255, [Math]::Max(0, $g * $f))
            $bi = [int][Math]::Min(255, [Math]::Max(0, $b * $f))
            $bmp.SetPixel($x, $y, [System.Drawing.Color]::FromArgb(255, $ri, $gi, $bi))
        }
    }
}

function ClearR($bmp, [double]$u, [double]$v, [double]$w, [double]$h) {
    $x0 = [int][Math]::Round($u * $S); $y0 = [int][Math]::Round($v * $S)
    $x1 = [int][Math]::Round(($u + $w) * $S) - 1; $y1 = [int][Math]::Round(($v + $h) * $S) - 1
    for ($y = $y0; $y -le $y1; $y++) {
        for ($x = $x0; $x -le $x1; $x++) {
            if ($x -ge 0 -and $y -ge 0 -and $x -lt $CanvasW -and $y -lt $CanvasH) {
                $bmp.SetPixel($x, $y, [System.Drawing.Color]::FromArgb(0, 0, 0, 0))
            }
        }
    }
}

function New-Canvas { New-Object System.Drawing.Bitmap($CanvasW, $CanvasH, [System.Drawing.Imaging.PixelFormat]::Format32bppArgb) }
function Save-Canvas($bmp, [string]$path) {
    $bmp.Save($path, [System.Drawing.Imaging.ImageFormat]::Png)
    $bmp.Dispose()
    Write-Host "wrote $path"
}

# ============================ HUMANOID (helmet / chest / arms / boots) =====================
$hum = New-Canvas
$seed = 11

# --- Helmet: head box (top 8,0; bottom 16,0; right 0,8; front 8,8; left 16,8; back 24,8) ---
FillR $hum 8 0 8 8 'plate' $seed          # top
FillR $hum 11 0 2 8 'trim' $seed          # crest rail
FillR $hum 11.5 0.5 1 7 'glow' $seed      # crest glow core
FillR $hum 16 0 8 8 'plated' $seed        # bottom (underside)
foreach ($u0 in @(0, 16)) {               # right/left sides
    FillR $hum $u0 8 8 8 'plate' $seed
    FillR $hum $u0 8 8 1 'trim' $seed     # brow trim wraps around
    FillR $hum ($u0 + 3) 11 2 2 'plated' $seed   # vent
    FillR $hum ($u0 + 3.5) 11.5 1 1 'glowc' $seed # aether vent core
}
FillR $hum 8 8 8 8 'plate' $seed          # front
FillR $hum 8 8 8 1 'trim' $seed           # brow trim
FillR $hum 9 11 6 1.5 'plated' $seed      # visor recess
FillR $hum 9.5 11.25 5 1 'glow' $seed     # glowing visor slit
FillR $hum 10 14 1 1 'plated' $seed       # chin vents
FillR $hum 13 14 1 1 'plated' $seed
FillR $hum 24 8 8 8 'plate' $seed         # back
FillR $hum 24 8 8 1 'trim' $seed
FillR $hum 27.5 9.5 1 5 'glow' $seed      # spine conduit

# --- Chestplate: body (top 20,16; front 20,20; back 32,20; right 16,20; left 28,20) --------
FillR $hum 20 16 8 4 'plate' $seed        # body top (shoulder deck)
FillR $hum 20 16 8 0.5 'trim' $seed
FillR $hum 28 16 8 4 'plated' $seed       # body bottom
FillR $hum 20 20 8 12 'plate' $seed       # front
FillR $hum 20 20 8 1 'trim' $seed         # collar
FillR $hum 20 26 8 1 'trim' $seed         # belt
FillR $hum 21 21.5 2 0.75 'trim' $seed    # chevrons toward the core
FillR $hum 25 21.5 2 0.75 'trim' $seed
FillR $hum 23 22.75 2 2.5 'plated' $seed  # core housing
FillR $hum 23.25 23 1.5 2 'glow' $seed    # aether core (gold bloom)
FillR $hum 23.6 23.5 0.8 1 'glowc' $seed  # cyan heart
FillR $hum 20 27.5 8 0.75 'plated' $seed  # abdomen plate seams
FillR $hum 20 29.5 8 0.75 'plated' $seed
FillR $hum 32 20 8 12 'plate' $seed       # back
FillR $hum 32 20 8 1 'trim' $seed
FillR $hum 34 21 4 5.5 'plated' $seed     # power pack
FillR $hum 35.5 21.5 1 4.5 'glow' $seed   # glowing spine
FillR $hum 32 26 8 1 'trim' $seed         # belt (back)
FillR $hum 16 20 4 12 'plate' $seed       # right side
FillR $hum 16 26 4 1 'trim' $seed
FillR $hum 28 20 4 12 'plate' $seed       # left side
FillR $hum 28 26 4 1 'trim' $seed

# --- Arms: right arm (top 44,16; bottom 48,16; right 40,20; front 44,20; left 48,20; back 52,20)
FillR $hum 44 16 4 4 'trim' $seed         # pauldron top: gold cap
FillR $hum 44.5 16.5 3 3 'plate' $seed    # with alloy inset
FillR $hum 48 16 4 4 'plated' $seed       # arm bottom (fist underside)
foreach ($u0 in @(40, 44, 48, 52)) {      # right/front/left/back faces share the pattern
    FillR $hum $u0 20 4 12 'plate' $seed
    FillR $hum $u0 20 4 3.5 'plated' $seed    # pauldron drop
    FillR $hum $u0 23.5 4 0.75 'trim' $seed   # pauldron rim
    FillR $hum $u0 29 4 0.75 'glow' $seed     # glowing wrist band
    FillR $hum $u0 30 4 2 'plated' $seed      # gauntlet
}

# --- Boots: legs, lower part only (right leg right 0,20; front 4,20; left 8,20; back 12,20; top 4,16; bottom 8,16)
FillR $hum 8 16 4 4 'plated' $seed        # sole (leg bottom face)
foreach ($u0 in @(0, 4, 8, 12)) {
    FillR $hum $u0 27 4 5 'plate' $seed       # greave
    FillR $hum $u0 27 4 0.75 'trim' $seed     # greave rim
    FillR $hum $u0 29.25 4 0.5 'glow' $seed   # ankle conduit
    FillR $hum $u0 30 4 2 'trim' $seed        # gold toe cap
}
Save-Canvas $hum (Join-Path $eqTexRoot "humanoid\infuse_legendary.png")

# ============================ LEGGINGS (waist tassets + thighs) ============================
$leg = New-Canvas
$seed = 23

# Body box on layer 2 = waist band + tassets (front/back/sides, lower rows only).
foreach ($u0 in @(20, 32)) {              # front / back (8 wide)
    FillR $leg $u0 26 8 1 'trim' $seed        # waist belt
    FillR $leg $u0 27 8 4 'plate' $seed       # tasset skirt
    FillR $leg ($u0 + 3.5) 27 1 3.5 'plated' $seed  # split seam
    FillR $leg $u0 30.5 8 0.5 'trim' $seed    # gold fringe
}
foreach ($u0 in @(16, 28)) {              # sides (4 wide)
    FillR $leg $u0 26 4 1 'trim' $seed
    FillR $leg $u0 27 4 3 'plate' $seed
    FillR $leg $u0 29.5 4 0.5 'trim' $seed
}

# Legs (full boxes; boots overlay the bottom): right leg faces right 0,20 / front 4,20 / left 8,20 / back 12,20.
FillR $leg 4 16 4 4 'plate' $seed         # leg top
FillR $leg 8 16 4 4 'plated' $seed        # leg bottom (under boots anyway)
foreach ($u0 in @(0, 4, 8, 12)) {
    FillR $leg $u0 20 4 10 'plate' $seed      # thigh + shin plating
    FillR $leg $u0 24.5 4 1.25 'trim' $seed   # gold knee guard
    FillR $leg $u0 22 4 0.5 'plated' $seed    # thigh seam
    FillR $leg $u0 27.5 4 0.5 'plated' $seed  # shin seam
}
FillR $leg 0 21 0.5 8 'glowc' $seed       # aether side stripe (outer right)
FillR $leg 11.5 21 0.5 8 'glowc' $seed    # aether side stripe (outer left)
Save-Canvas $leg (Join-Path $eqTexRoot "humanoid_leggings\infuse_legendary.png")

# ============================ WINGS (cape) =================================================
# Elytra UV: box 10x20x2 at texOffs(22,0): top(24,0) bottom(34,0) right(22,2) front(24,2) left(34,2) back(36,2).
$cape = New-Canvas
$seed = 37

FillR $cape 24 0 10 2 'trim' $seed        # shoulder mount (top)
FillR $cape 34 0 10 2 'plated' $seed      # underside
FillR $cape 22 2 2 20 'cloth' $seed       # thickness strips
FillR $cape 34 2 2 20 'cloth' $seed
foreach ($u0 in @(24, 36)) {              # front / back faces (10 wide, 20 tall)
    FillR $cape $u0 2 10 20 'cloth' $seed
    FillR $cape $u0 2 0.75 20 'trim' $seed        # gold hem left
    FillR $cape ($u0 + 9.25) 2 0.75 20 'trim' $seed # gold hem right
    FillR $cape $u0 2 10 0.75 'trim' $seed        # gold yoke
    # Central rune: glowing diamond + glyph dots.
    FillR $cape ($u0 + 4) 8.5 2 0.6 'glow' $seed
    FillR $cape ($u0 + 3.4) 9.1 3.2 1.8 'glow' $seed
    FillR $cape ($u0 + 4) 10.9 2 0.6 'glow' $seed
    FillR $cape ($u0 + 4.6) 9.6 0.8 0.8 'glowc' $seed
    FillR $cape ($u0 + 4.6) 6.5 0.8 0.8 'glow' $seed   # glyph above
    FillR $cape ($u0 + 2.4) 13 0.7 0.7 'glow' $seed    # scattered glyphs below
    FillR $cape ($u0 + 6.9) 14.2 0.7 0.7 'glow' $seed
    FillR $cape ($u0 + 4.6) 16 0.8 0.8 'glowc' $seed
    # Ragged hem: alternating notches cut from the bottom edge.
    for ($n = 0; $n -lt 10; $n += 2) {
        ClearR $cape ($u0 + $n) 21 1 1
        ClearR $cape ($u0 + $n + 0.5) 20 0.5 1
    }
}
Save-Canvas $cape (Join-Path $eqTexRoot "wings\infuse_legendary.png")

# ============================ EQUIPMENT ASSET ==============================================
$asset = @'
{
  "layers": {
    "humanoid": [
      { "texture": "skypvp:infuse_legendary" }
    ],
    "humanoid_leggings": [
      { "texture": "skypvp:infuse_legendary" }
    ],
    "wings": [
      { "texture": "skypvp:infuse_legendary" }
    ]
  }
}
'@
Set-Content -Path (Join-Path $eqDefRoot "infuse_legendary.json") -Value $asset -Encoding ascii
Write-Host "wrote equipment asset infuse_legendary.json"
Write-Host "Aetherforged legendary equipment generated."
