# Generates per-item art for crafting materials (mat_<id>), medic consumables
# (medic_<id>), and shield rechargers (shield_recharger_<tier>), plus the
# items/*.json and models/item/*.json wiring for each.
#
# 64x64 detailed sprites rendered from the original 16x16 region maps, so every
# item keeps its silhouette and palette (the "theme") while gaining real detail:
#   1. the 16x16 char map is smoothed 16->32->64 with Scale2x (rounds staircase
#      diagonals without inventing colors),
#   2. each pixel is shaded: top-down directional light, rim highlight on
#      top/left silhouette edges, drop shadow on bottom/right edges, soft seams
#      between regions,
#   3. per-region material finishes add surface character: brushed metal,
#      fabric weave, glass specular streak, liquid gradient + bubbles, emissive
#      glow (radial bloom around the region), or plain matte noise.
# Models use minecraft:item/generated, which accepts any square texture size.
Add-Type -AssemblyName System.Drawing

$texRoot = Join-Path $PSScriptRoot "..\..\resource-packs\skypvp-core\assets\skypvp\textures\item"
$modelRoot = Join-Path $PSScriptRoot "..\..\resource-packs\skypvp-core\assets\skypvp\models\item"
$itemsRoot = Join-Path $PSScriptRoot "..\..\resource-packs\skypvp-core\assets\skypvp\items"
foreach ($d in @($texRoot, $modelRoot, $itemsRoot)) { New-Item -ItemType Directory -Force $d | Out-Null }

function C([string]$hex) {
    [System.Drawing.Color]::FromArgb(255,
        [Convert]::ToInt32($hex.Substring(0,2),16),
        [Convert]::ToInt32($hex.Substring(2,2),16),
        [Convert]::ToInt32($hex.Substring(4,2),16))
}

# Deterministic per-pixel hash noise in [-1, 1].
function Get-Noise([int]$x, [int]$y, [int]$seed) {
    $h = ($x * 374761393 + $y * 668265263 + $seed * 987643211) -band 0x7FFFFFFF
    $h = (($h -bxor ($h -shr 13)) * 1274126177) -band 0x7FFFFFFF
    return ((($h -band 0xFFFF) / 65535.0) * 2.0) - 1.0
}

# Scale2x: doubles a jagged char grid, rounding diagonal steps.
function Get-Scale2x([object[]]$grid) {
    $h = $grid.Count
    $w = $grid[0].Count
    $out = New-Object 'object[]' ($h * 2)
    for ($y = 0; $y -lt $h; $y++) {
        $row0 = New-Object 'char[]' ($w * 2)
        $row1 = New-Object 'char[]' ($w * 2)
        for ($x = 0; $x -lt $w; $x++) {
            $e = $grid[$y][$x]
            $b = if ($y -gt 0) { $grid[$y - 1][$x] } else { $e }
            $h2 = if ($y -lt $h - 1) { $grid[$y + 1][$x] } else { $e }
            $d = if ($x -gt 0) { $grid[$y][$x - 1] } else { $e }
            $f = if ($x -lt $w - 1) { $grid[$y][$x + 1] } else { $e }
            $e0 = $e; $e1 = $e; $e2 = $e; $e3 = $e
            if ($b -ne $h2 -and $d -ne $f) {
                if ($d -eq $b) { $e0 = $d }
                if ($b -eq $f) { $e1 = $f }
                if ($d -eq $h2) { $e2 = $d }
                if ($h2 -eq $f) { $e3 = $f }
            }
            $row0[$x * 2] = $e0; $row0[$x * 2 + 1] = $e1
            $row1[$x * 2] = $e2; $row1[$x * 2 + 1] = $e3
        }
        $out[$y * 2] = $row0
        $out[$y * 2 + 1] = $row1
    }
    return ,$out
}

function New-Sprite {
    param(
        [string]$name,
        [string[]]$rows,
        [hashtable]$palette,
        # char -> finish: metal | fabric | glass | liquid | glow | matte (default matte)
        [hashtable]$finish = @{}
    )
    # 16x16 char map -> 64x64 via two Scale2x passes.
    $grid = New-Object 'object[]' 16
    for ($y = 0; $y -lt 16; $y++) { $grid[$y] = $rows[$y].ToCharArray() }
    $grid = Get-Scale2x $grid
    $grid = Get-Scale2x $grid
    $size = 64
    $seed = 0
    foreach ($chr in $name.ToCharArray()) { $seed = ($seed * 31 + [int]$chr) -band 0xFFFFF }

    # Region centroids + extents for glow bloom.
    $centroids = @{}
    foreach ($key in $palette.Keys) {
        if (($finish[$key]) -ne 'glow') { continue }
        $sx = 0.0; $sy = 0.0; $n = 0
        for ($y = 0; $y -lt $size; $y++) {
            for ($x = 0; $x -lt $size; $x++) {
                if ($grid[$y][$x] -eq [char]$key) { $sx += $x; $sy += $y; $n++ }
            }
        }
        if ($n -gt 0) {
            $centroids[$key] = @(($sx / $n), ($sy / $n), [Math]::Sqrt($n / [Math]::PI))
        }
    }

    $bmp = New-Object System.Drawing.Bitmap($size, $size, [System.Drawing.Imaging.PixelFormat]::Format32bppArgb)
    for ($y = 0; $y -lt $size; $y++) {
        for ($x = 0; $x -lt $size; $x++) {
            $ch = $grid[$y][$x]
            $key = [string]$ch
            if ($ch -eq '.' -or -not $palette.ContainsKey($key)) { continue }
            $col = $palette[$key]
            $r = [double]$col.R; $g = [double]$col.G; $b = [double]$col.B

            # Directional light: brighter top, darker bottom; whisper of left bias.
            $f = 1.10 - 0.18 * ($y / 63.0) + 0.03 * (1.0 - $x / 63.0)

            # Silhouette + seam shading.
            $up    = if ($y -gt 0)         { $grid[$y - 1][$x] } else { [char]'.' }
            $down  = if ($y -lt $size - 1) { $grid[$y + 1][$x] } else { [char]'.' }
            $left  = if ($x -gt 0)         { $grid[$y][$x - 1] } else { [char]'.' }
            $right = if ($x -lt $size - 1) { $grid[$y][$x + 1] } else { [char]'.' }
            if ($down -eq [char]'.' -or $right -eq [char]'.') { $f *= 0.62 }
            if ($up -eq [char]'.' -or $left -eq [char]'.') { $f *= 1.24 }
            if ($down -ne [char]'.' -and $right -ne [char]'.' -and $up -ne [char]'.' -and $left -ne [char]'.') {
                if ($down -ne $ch -or $right -ne $ch) { $f *= 0.90 }
                if ($up -ne $ch -or $left -ne $ch) { $f *= 1.08 }
            }

            $add = 0.0
            switch ([string]$finish[$key]) {
                'metal' {
                    # Brushed horizontal grain + faint sparkle.
                    $f *= 1.0 + 0.05 * [Math]::Sin($x * 0.55 + (Get-Noise 0 $y $seed) * 6.28)
                    if ((Get-Noise $x $y ($seed + 7)) -gt 0.93) { $add += 0.10 }
                }
                'fabric' {
                    # Two-pixel weave checker + thread noise.
                    $weave = ((([int]($x / 2) + [int]($y / 2)) % 2) * 2 - 1)
                    $f *= 1.0 + 0.05 * $weave + 0.03 * (Get-Noise $x $y $seed)
                }
                'glass' {
                    # Diagonal specular streak.
                    $band = ($x + (63 - $y)) % 30
                    if ($band -lt 4) { $add += 0.26 * (1.0 - $band / 4.0) }
                    $f *= 1.02
                }
                'liquid' {
                    # Stronger vertical gradient + sparse bubbles.
                    $f *= 1.0 + 0.12 * (1.0 - $y / 63.0)
                    if ((Get-Noise $x $y ($seed + 3)) -gt 0.96) { $add += 0.22 }
                }
                'glow' {
                    $f *= 1.12
                    if ($centroids.ContainsKey($key)) {
                        $cx = $centroids[$key][0]; $cy = $centroids[$key][1]
                        $sigma = [Math]::Max(2.0, $centroids[$key][2])
                        $d2 = ($x - $cx) * ($x - $cx) + ($y - $cy) * ($y - $cy)
                        $f *= 1.0 + 0.22 * [Math]::Exp(-$d2 / (2.0 * $sigma * $sigma))
                        $add += 0.30 * [Math]::Exp(-$d2 / (0.6 * $sigma * $sigma))
                    }
                }
            }
            # Universal fine grain keeps large flats from looking sterile.
            $f *= 1.0 + 0.035 * (Get-Noise $x $y ($seed + 1))

            $r = $r * $f + 255.0 * $add
            $g = $g * $f + 255.0 * $add
            $b = $b * $f + 255.0 * $add
            $ri = [int][Math]::Min(255, [Math]::Max(0, $r))
            $gi = [int][Math]::Min(255, [Math]::Max(0, $g))
            $bi = [int][Math]::Min(255, [Math]::Max(0, $b))
            $bmp.SetPixel($x, $y, [System.Drawing.Color]::FromArgb(255, $ri, $gi, $bi))
        }
    }
    $bmp.Save((Join-Path $texRoot "$name.png"), [System.Drawing.Imaging.ImageFormat]::Png)
    $bmp.Dispose()
    # model + item wiring
    Set-Content -Path (Join-Path $modelRoot "$name.json") -Encoding ascii -Value (
        '{ "parent": "minecraft:item/generated", "textures": { "layer0": "skypvp:item/' + $name + '" } }')
    Set-Content -Path (Join-Path $itemsRoot "$name.json") -Encoding ascii -Value (
        '{ "model": { "type": "minecraft:model", "model": "skypvp:item/' + $name + '" } }')
    Write-Host "wrote $name (texture + model + item)"
}

# ---- shared syringe template (diagonal, needle lower-left, plunger upper-right) ----------
$syringeRows = @(
    "................",
    "............pp..",
    "...........ppp..",
    "..........opp...",
    ".........oFFo...",
    "........oFFo....",
    ".......oFFo.....",
    "......oFFo......",
    ".....oFFo.......",
    "....oFFo........",
    "....oFo.........",
    "...ooo..........",
    "..nn............",
    ".nn.............",
    "................",
    "................"
)
function New-Syringe([string]$name, [string]$fluidHex) {
    New-Sprite $name $syringeRows @{
        'o' = C 'C8D8DE'; 'p' = C '8FA0A8'; 'n' = C 'D7DEE2'; 'F' = C $fluidHex
    } @{ 'o' = 'glass'; 'p' = 'metal'; 'n' = 'metal'; 'F' = 'glow' }
}

# ============================ MEDIC (healing) ==============================================
New-Sprite "medic_bandage_rag" @(
    "................",
    "....oooooo......",
    "..oobbbbbboo....",
    ".obblllllbbbo...",
    ".oblldddlllbo...",
    "obldbbbbbdlbo...",
    "obldbsssbdlbo...",
    "obldbsbsbdlbo...",
    "obldbsssbdlbo...",
    "obldbbbbbdlbo...",
    ".oblldddlllbo...",
    ".obbllllllbo....",
    "..oobbbbboo.tt..",
    "....oooootttt...",
    ".........tt.....",
    "................"
) @{ 'b' = C 'C4B99E'; 'l' = C 'DED4BA'; 'd' = C '9E9278'; 's' = C '857A62'; 'o' = C '6E6552'; 't' = C 'B0A488' } `
  @{ 'b' = 'fabric'; 'l' = 'fabric'; 'd' = 'fabric'; 's' = 'fabric'; 't' = 'fabric' }

New-Sprite "medic_sterile_bandage" @(
    "................",
    "....oooooo......",
    "..oowwwwwwoo....",
    ".owwwwwwwwwwo...",
    ".owwggggggwwo...",
    "owgwwwwwwwgwo...",
    "owgwwrrwwwgwo...",
    "owgwrrrrwwgwo...",
    "owgwwrrwwwgwo...",
    "owgwwwwwwwgwo...",
    ".owwggggggwwo...",
    ".owwwwwwwwwo....",
    "..oowwwwwoo.ww..",
    "....oooowwww....",
    ".........ww.....",
    "................"
) @{ 'w' = C 'F4F6F6'; 'g' = C 'C9D2D4'; 'r' = C 'E03A3A'; 'o' = C '97A4A6' } `
  @{ 'w' = 'fabric'; 'g' = 'fabric'; 'r' = 'matte' }

New-Sprite "medic_medkit" @(
    "................",
    ".....kkkkkk.....",
    "....kk....kk....",
    ".oooooooooooooo.",
    ".olllllllllllro.",
    ".orrrrrwwrrrrro.",
    ".orrrrrwwrrrrro.",
    ".orrrwwwwwwrrro.",
    ".orrrwwwwwwrrro.",
    ".orrrrrwwrrrrro.",
    ".orrrrrwwrrrrro.",
    ".odddddddddddro.",
    ".oooooooooooooo.",
    "................",
    "................",
    "................"
) @{ 'r' = C 'D8383E'; 'l' = C 'F06A6A'; 'd' = C 'A8262B'; 'w' = C 'F6F6F4'; 'k' = C '3A3F44'; 'o' = C '701418' } `
  @{ 'r' = 'matte'; 'l' = 'matte'; 'd' = 'matte'; 'k' = 'metal'; 'w' = 'matte' }

New-Sprite "medic_surgical_kit" @(
    "................",
    "....gggggggg....",
    ".ggggbbbbbbgggg.",
    ".gbbbbbbbbbbbbg.",
    ".gbbbbbggbbbbbg.",
    ".gggggggggggggg.",
    ".gwwtwwwtwwwtwg.",
    ".gggggggggggggg.",
    ".gbbbbbbbbbbbbg.",
    ".gbbbbbbbbbbbbg.",
    ".gbbbbbggbbbbbg.",
    ".gbbbbbbbbbbbbg.",
    ".ggggbbbbbbgggg.",
    "....gggggggg....",
    "................",
    "................"
) @{ 'b' = C '3E4650'; 'g' = C 'E3B54E'; 'w' = C 'EDEFF1'; 't' = C '9FB2BD' } `
  @{ 'b' = 'fabric'; 'g' = 'metal'; 'w' = 'metal'; 't' = 'metal' }

New-Syringe "medic_adrenaline_shot" "35E0F0"
New-Syringe "medic_stamina_stabilizer" "F2C740"
New-Syringe "medic_overdrive_serum" "D950E8"

# ============================ MATERIALS ====================================================
New-Sprite "mat_cloth_scrap" @(
    "................",
    "..o..oooo.......",
    ".obooblllbo.o...",
    ".oblbbllbbbob...",
    "..obllldlllbbo..",
    ".obllddldllbo...",
    "..oblldllllbbo..",
    ".oobllllldllbo..",
    ".obdlllddlllbo..",
    "..obbdllldbbo...",
    "...obblllbbo.o..",
    "..o..obbbo..b...",
    "......o.oo......",
    "................",
    "................",
    "................"
) @{ 'b' = C 'C9B892'; 'l' = C 'E3D6B4'; 'd' = C 'A08B66'; 'o' = C '6E5C3F' } `
  @{ 'b' = 'fabric'; 'l' = 'fabric'; 'd' = 'fabric' }

New-Sprite "mat_fiber_bundle" @(
    "................",
    "..s.w.g.w.g.s...",
    "..swgwgwgwgws...",
    "..swgwgwgwgws...",
    "...swgwgwgws....",
    "...swgwgwgws....",
    "....tttttt......",
    "....tttttt......",
    "...swgwgwgws....",
    "...swgwgwgws....",
    "..swgwgwgwgws...",
    "..swgwgwgwgws...",
    "..s.w.g.w.g.s...",
    "................",
    "................",
    "................"
) @{ 'w' = C 'E8E8E4'; 'g' = C 'B9BDBD'; 's' = C '8E9494'; 't' = C '7C6A4E' } `
  @{ 'w' = 'fabric'; 'g' = 'fabric'; 's' = 'fabric'; 't' = 'fabric' }

New-Sprite "mat_metal_shards" @(
    "................",
    "..o.............",
    ".oml......o.....",
    ".omml....omo....",
    ".ommml..ommlo...",
    "..ommml.ommmo...",
    "..odmmloommlo...",
    "...odmoommdo....",
    "...oodoommo.....",
    "..o..o.odo......",
    ".oml....o.......",
    ".ommlo..........",
    ".odmmo..........",
    "..oddo..........",
    "...oo...........",
    "................"
) @{ 'm' = C '9AA3AC'; 'l' = C 'C7CFD6'; 'd' = C '5F6A73'; 'o' = C '3A434B' } `
  @{ 'm' = 'metal'; 'l' = 'metal'; 'd' = 'metal'; 'o' = 'metal' }

New-Sprite "mat_polymer_sheet" @(
    "................",
    "..oooooooooo....",
    ".oplllllllppo...",
    ".opppppppppppo..",
    "..odddddddpppo..",
    "...oppppppppdo..",
    "...opllllppdo...",
    "..opppppppdo....",
    "..oppppppdo.....",
    ".opppppppo......",
    ".oppppppo.......",
    ".oddddddo.......",
    "..oooooo........",
    "................",
    "................",
    "................"
) @{ 'p' = C 'A9D6E8'; 'l' = C 'D6EFF9'; 'd' = C '7FB2C9'; 'o' = C '5B8BA1' } `
  @{ 'p' = 'glass'; 'l' = 'glass'; 'd' = 'glass' }

New-Sprite "mat_alloy_plate" @(
    "................",
    "..oooooooooooo..",
    ".ollllllllllllo.",
    ".olrggggggggrlo.",
    ".olggggggggggdo.",
    ".olggllllllggdo.",
    ".olggggggggggdo.",
    ".olggggggggggdo.",
    ".olggllllllggdo.",
    ".olggggggggggdo.",
    ".olrggggggggrdo.",
    ".oddddddddddddo.",
    "..oooooooooooo..",
    "................",
    "................",
    "................"
) @{ 'g' = C '4E5A66'; 'l' = C '6E7E8C'; 'd' = C '333C46'; 'r' = C '8C9AA6'; 'o' = C '232A31' } `
  @{ 'g' = 'metal'; 'l' = 'metal'; 'd' = 'metal'; 'r' = 'metal'; 'o' = 'metal' }

New-Sprite "mat_capacitor_cell" @(
    "................",
    ".....ooooo......",
    "....oeeeeeo.....",
    "....occcccco....",
    "....obbbbbbo....",
    "....oblbbbdo....",
    "....obbbbbdo....",
    "....ogggggdo....",
    "....ogghggdo....",
    "....ogggggdo....",
    "....obbbbbdo....",
    "....oblbbbdo....",
    "....occcccco....",
    "....oeeeeeo.....",
    ".....ooooo......",
    "................"
) @{ 'c' = C 'C77F3F'; 'e' = C 'E39A55'; 'b' = C '3D4854'; 'l' = C '5A6875'; 'd' = C '2C3540'; 'g' = C '2ABBD0'; 'h' = C '9FF6FF'; 'o' = C '26303A' } `
  @{ 'c' = 'metal'; 'e' = 'metal'; 'b' = 'metal'; 'l' = 'metal'; 'd' = 'metal'; 'g' = 'glow'; 'h' = 'glow' }

New-Sprite "mat_aether_resin" @(
    "................",
    "......ooo.......",
    "....ooaaaoo.....",
    "...oaalsaaao....",
    "..oaalssaaaao...",
    "..oalsslaaaao...",
    ".oaassaaaaaado..",
    ".oaaaaaaaaddo...",
    ".oaaaaaaadddo...",
    "..oaaaaaaddo....",
    "..odaaaadddo....",
    "...oddadddo.....",
    "....oo.odo......",
    "........o.......",
    "................",
    "................"
) @{ 'a' = C 'E8A93B'; 'l' = C 'FFD87A'; 's' = C 'FFF0BE'; 'd' = C 'B77E22'; 'o' = C '8A5D14' } `
  @{ 'a' = 'liquid'; 'l' = 'glow'; 's' = 'glow'; 'd' = 'liquid' }

New-Sprite "mat_quantum_gel" @(
    "................",
    "......oooo......",
    "....ootttoo.....",
    "...otttmtttto...",
    "..otttppttttdo..",
    "..ottpppptttdo..",
    ".otttppppttmtdo.",
    ".ottttppptttddo.",
    ".otmtttttttdddo.",
    ".otttttttdddo...",
    "..ottttdddddo...",
    "..odddddddoo....",
    "...ooooooo......",
    "................",
    "................",
    "................"
) @{ 't' = C '3FD9C8'; 'p' = C '8E5BD9'; 'm' = C 'EDF9FF'; 'd' = C '2AA394'; 'o' = C '1F7A6F' } `
  @{ 't' = 'liquid'; 'p' = 'glow'; 'm' = 'glow'; 'd' = 'liquid' }

New-Sprite "mat_stim_compound" @(
    "................",
    "......kkkk......",
    "......kkkk......",
    ".......gg.......",
    ".......gg.......",
    "......oggo......",
    ".....ogccgo.....",
    "....ogccccgo....",
    "...ogccccccgo...",
    "...ogcclcccgo...",
    "...ogccccccgo...",
    "....ogccccgo....",
    ".....oggggo.....",
    "......oooo......",
    "................",
    "................"
) @{ 'g' = C 'CDE9F2'; 'c' = C '35E0F0'; 'l' = C 'AFF5FC'; 'k' = C '5E6E76'; 'o' = C '3C7C8A' } `
  @{ 'g' = 'glass'; 'c' = 'liquid'; 'l' = 'glow'; 'k' = 'metal' }

New-Sprite "mat_field_suture" @(
    "................",
    "..........nn....",
    ".........n..n...",
    "........n....n..",
    "...ooooon.....n.",
    "..otttttto....n.",
    ".ottdddttto..n..",
    ".otd...dtto.....",
    ".otd...dtto.....",
    ".ottdddttto.....",
    "..otttttto......",
    "...oooooo.......",
    "................",
    "................",
    "................",
    "................"
) @{ 't' = C '8A8F5B'; 'd' = C '63683F'; 'n' = C 'C9CFD4'; 'o' = C '4A4E2E' } `
  @{ 't' = 'fabric'; 'd' = 'fabric'; 'n' = 'metal' }

# ============================ SHIELD RECHARGERS ============================================
# Handheld energy canister: prong contacts up top, tier-colored charge window,
# pip row = tier rank (1-4). Tier color carries through window + accent bands.
function New-Recharger([string]$tierId, [int]$pips, [string]$fluidHex, [string]$accentHex) {
    $pipRow = "..ommmmmmmmo....".ToCharArray()
    $positions = @(4, 6, 8, 10)
    for ($i = 0; $i -lt $pips; $i++) { $pipRow[$positions[$i]] = 'p' }
    $rows = @(
        "................",
        "....kk..kk......",
        "....kk..kk......",
        "...occccccco....",
        "..ommmmmmmmo....",
        "..omaaaaaamo....",
        "..omgwwwwgmo....",
        "..omgwwwwgmo....",
        "..omgwwwwgmo....",
        "..omgwwwwgmo....",
        "..omaaaaaamo....",
        "..ommmmmmmmo....",
        (-join $pipRow),
        "...occcccco.....",
        "................",
        "................"
    )
    New-Sprite "shield_recharger_$tierId" $rows @{
        'o' = C '232A31'; 'c' = C '6E7E8C'; 'm' = C '4E5A66'; 'g' = C '2C3540'
        'k' = C '8C9AA6'; 'w' = C $fluidHex; 'a' = C $accentHex; 'p' = C $fluidHex
    } @{
        'o' = 'metal'; 'c' = 'metal'; 'm' = 'metal'; 'k' = 'metal'
        'g' = 'matte'; 'w' = 'glow'; 'a' = 'matte'; 'p' = 'glow'
    }
}

New-Recharger "field"    1 "35E0F0" "1E8C9C"
New-Recharger "tactical" 2 "4F9BFF" "2A5CA8"
New-Recharger "military" 3 "B049E8" "6E2E93"
New-Recharger "quantum"  4 "FFD84D" "B08A1E"

Write-Host "Crafting/medic/recharger item art generated (64x64)."
