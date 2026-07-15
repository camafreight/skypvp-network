# Generates the SkyPvP HUD font textures (bar frame, fill segment, icons).
# All art is WHITE on transparent so the server tints it per-state via component colors.
# Advance math depends on these exact dimensions: frame 106x10 (adv 107), fill 2x6 (adv 3),
# icons 10x10 (adv 11). If you resize anything, update BreachHudFont + hud.json to match.
Add-Type -AssemblyName System.Drawing

$root = Join-Path $PSScriptRoot "..\..\resource-packs\skypvp-core\assets\skypvp\textures\hud"
New-Item -ItemType Directory -Force $root | Out-Null

function New-Canvas([int]$w, [int]$h) {
    $bmp = New-Object System.Drawing.Bitmap($w, $h, [System.Drawing.Imaging.PixelFormat]::Format32bppArgb)
    return $bmp
}

function Save-Png($bmp, [string]$name) {
    $path = Join-Path $root $name
    $bmp.Save($path, [System.Drawing.Imaging.ImageFormat]::Png)
    $bmp.Dispose()
    Write-Host "wrote $path"
}

$white = [System.Drawing.Color]::FromArgb(255, 255, 255, 255)
$soft  = [System.Drawing.Color]::FromArgb(150, 255, 255, 255)

# --- bar_frame.png (106x10): 1px border with 2px sci-fi corner notches -----------------
$bmp = New-Canvas 106 10
for ($x = 2; $x -le 103; $x++) { $bmp.SetPixel($x, 0, $white); $bmp.SetPixel($x, 9, $white) }
for ($y = 2; $y -le 7; $y++) { $bmp.SetPixel(0, $y, $white); $bmp.SetPixel(105, $y, $white) }
# corner diagonals
$bmp.SetPixel(1, 1, $white); $bmp.SetPixel(104, 1, $white)
$bmp.SetPixel(1, 8, $white); $bmp.SetPixel(104, 8, $white)
Save-Png $bmp "bar_frame.png"

# --- bar_fill.png (2x6): solid segment ---------------------------------------------------
$bmp = New-Canvas 2 6
for ($x = 0; $x -lt 2; $x++) { for ($y = 0; $y -lt 6; $y++) { $bmp.SetPixel($x, $y, $white) } }
Save-Png $bmp "bar_fill.png"

# --- TOP-ROW variants: same art with transparent bottom padding --------------------------
# Vanilla rejects bitmap providers whose ascent exceeds height, so the stacked (shield)
# row cannot reuse the short textures with a big ascent. These taller canvases keep the
# art in the TOP rows; height==textureHeight keeps advance = width + 1 unchanged.
function New-PaddedCopy([string]$source, [string]$name, [int]$padTo) {
    $src = New-Object System.Drawing.Bitmap((Join-Path $root $source))
    $bmp = New-Canvas $src.Width $padTo
    for ($x = 0; $x -lt $src.Width; $x++) {
        for ($y = 0; $y -lt $src.Height; $y++) {
            $bmp.SetPixel($x, $y, $src.GetPixel($x, $y))
        }
    }
    $src.Dispose()
    Save-Png $bmp $name
}

# --- 10x10 icons from pixel maps ('#' = white, '+' = soft) -------------------------------
function New-Icon([string]$name, [string[]]$rows) {
    $bmp = New-Canvas 10 10
    for ($y = 0; $y -lt 10; $y++) {
        $row = $rows[$y]
        for ($x = 0; $x -lt 10; $x++) {
            switch ($row[$x]) {
                '#' { $bmp.SetPixel($x, $y, $white) }
                '+' { $bmp.SetPixel($x, $y, $soft) }
            }
        }
    }
    Save-Png $bmp $name
}

New-Icon "icon_health.png" @(
    "..........",
    "....##....",
    "....##....",
    ".########.",
    ".########.",
    "....##....",
    "....##....",
    "....##....",
    "..........",
    ".........."
)

New-Icon "icon_shield.png" @(
    ".########.",
    "#..####..#",
    "#..####..#",
    "#..####..#",
    "#........#",
    ".#......#.",
    ".#......#.",
    "..#....#..",
    "...#..#...",
    "....##...."
)

New-Icon "icon_ammo.png" @(
    "..........",
    "..........",
    "..........",
    ".######...",
    ".#######..",
    ".########.",
    ".#######..",
    ".######...",
    "..........",
    ".........."
)

New-Icon "icon_timer.png" @(
    "##########",
    ".#......#.",
    "..#....#..",
    "...#..#...",
    "....##....",
    "....##....",
    "...#..#...",
    "..#.##.#..",
    ".#.####.#.",
    "##########"
)

New-Icon "icon_extract.png" @(
    "....##....",
    "...####...",
    "..######..",
    ".###..###.",
    "....##....",
    "....##....",
    "....##....",
    "....##....",
    "#........#",
    "##########"
)

New-Icon "icon_stamina.png" @(
    ".....###..",
    "....###...",
    "...###....",
    "..#######.",
    ".....###..",
    "....###...",
    "...###....",
    "..###.....",
    "..##......",
    "..#......."
)

# --- Ammo digits (6x10) + slash (4x10): chunky HUD numerals -------------------------------
function New-PixelArt([string]$name, [string[]]$rows, [int]$w) {
    $bmp = New-Canvas $w 10
    for ($y = 0; $y -lt 10; $y++) {
        for ($x = 0; $x -lt $w; $x++) {
            if ($rows[$y][$x] -eq '#') { $bmp.SetPixel($x, $y, $white) }
        }
    }
    Save-Png $bmp $name
}

$digits = @(
  @(".####.","#....#","#...##","#..#.#","#.#..#","##...#","#....#","#....#","#....#",".####."),
  @("...#..","..##..",".###..","...#..","...#..","...#..","...#..","...#..","...#..",".#####"),
  @(".####.","#....#",".....#",".....#","....#.","...#..","..#...",".#....","#.....","######"),
  @(".####.","#....#",".....#",".....#","..###.",".....#",".....#",".....#","#....#",".####."),
  @("....#.","...##.","..#.#.",".#..#.","#...#.","######","....#.","....#.","....#.","....#."),
  @("######","#.....","#.....","#####.",".....#",".....#",".....#",".....#","#....#",".####."),
  @(".####.","#....#","#.....","#.....","#####.","#....#","#....#","#....#","#....#",".####."),
  @("######",".....#",".....#","....#.","....#.","...#..","...#..","..#...","..#...","..#..."),
  @(".####.","#....#","#....#","#....#",".####.","#....#","#....#","#....#","#....#",".####."),
  @(".####.","#....#","#....#","#....#",".#####",".....#",".....#",".....#","#....#",".####.")
)
for ($d = 0; $d -lt 10; $d++) { New-PixelArt "digit_$d.png" $digits[$d] 6 }
New-PixelArt "digit_slash.png" @("...#","...#","..#.","..#.","..#.",".#..",".#..",".#..","#...","#...") 4

# --- Reload spinner frames (10x10): rotating bar ------------------------------------------
New-PixelArt "reload_0.png" @("..........","..........","..........","..........",".########.",".########.","..........","..........","..........","..........") 10
New-PixelArt "reload_1.png" @("#.........",".##.......","..##......","...##.....","....##....",".....##...","......##..",".......##.","........##",".........#") 10
New-PixelArt "reload_2.png" @("....##....","....##....","....##....","....##....","....##....","....##....","....##....","....##....","....##....","....##....") 10
New-PixelArt "reload_3.png" @(".........#","........##",".......##.","......##..",".....##...","....##....","...##.....","..##......",".##.......","#.........") 10

# --- Solid fill strips (continuous bars, not ticks) ---------------------------------------
# Power-of-two widths compose any fill width; the composer appends a -1 space after each
# strip to cancel the +1 glyph advance so consecutive strips render seamlessly.
foreach ($w in @(1, 2, 4, 8, 16, 32, 64)) {
    $bmp = New-Canvas $w 6
    for ($x = 0; $x -lt $w; $x++) { for ($y = 0; $y -lt 6; $y++) { $bmp.SetPixel($x, $y, $white) } }
    Save-Png $bmp "fill_w$w.png"
}

# Top-row (shield) variants: art band 19..9 above baseline via ascent 19 with height 21
# (fill: ascent 17, height 17). Generated AFTER the base art exists.
New-PaddedCopy "bar_frame.png" "bar_frame_top.png" 21
New-PaddedCopy "bar_fill.png" "bar_fill_top.png" 17
New-PaddedCopy "icon_shield.png" "icon_shield_top.png" 21
foreach ($w in @(1, 2, 4, 8, 16, 32, 64)) {
    New-PaddedCopy "fill_w$w.png" "fill_w${w}_top.png" 17
}

# --- Blank out the vanilla hunger bar ------------------------------------------------------
# Stamina lives on the custom HUD bar and food is pinned full server-side; transparent
# sprites remove the drumstick row entirely.
$vanillaHud = Join-Path $PSScriptRoot "..\..\resource-packs\skypvp-core\assets\minecraft\textures\gui\sprites\hud"
New-Item -ItemType Directory -Force $vanillaHud | Out-Null
foreach ($sprite in @("food_empty", "food_full", "food_half", "food_empty_hunger", "food_full_hunger", "food_half_hunger")) {
    $bmp = New-Canvas 9 9
    $bmp.Save((Join-Path $vanillaHud "$sprite.png"), [System.Drawing.Imaging.ImageFormat]::Png)
    $bmp.Dispose()
    Write-Host "wrote $vanillaHud\$sprite.png"
}

# --- Balance icons (10x10, white, tinted server-side like the other icons) -----------------
New-Icon "icon_gold.png" @(
    "..........",
    "..........",
    "...####...",
    "..#....#..",
    ".########.",
    ".#......#.",
    ".#......#.",
    ".########.",
    "..........",
    ".........."
)

New-Icon "icon_coin.png" @(
    "..........",
    "...####...",
    "..#....#..",
    ".#..##..#.",
    ".#.#..#.#.",
    ".#.#..#.#.",
    ".#..##..#.",
    "..#....#..",
    "...####...",
    ".........."
)

# --- Blank the vanilla hearts + armor rows --------------------------------------------------
# Health/shield live on the custom HUD bars, so the vanilla survival rows are duplicates.
# Vehicle (mount) hearts and air bubbles are kept: they have no custom HUD equivalent.
$heartDir = Join-Path $PSScriptRoot "..\..\resource-packs\skypvp-core\assets\minecraft\textures\gui\sprites\hud\heart"
New-Item -ItemType Directory -Force $heartDir | Out-Null
$heartSprites = New-Object System.Collections.Generic.List[string]
foreach ($suffix in @("", "_blinking", "_hardcore", "_hardcore_blinking")) { $heartSprites.Add("container$suffix") }
foreach ($effect in @("", "absorbing_", "poisoned_", "withered_", "frozen_")) {
    foreach ($hardcore in @("", "hardcore_")) {
        foreach ($fill in @("full", "half")) {
            foreach ($blink in @("", "_blinking")) {
                $heartSprites.Add("$effect$hardcore$fill$blink")
            }
        }
    }
}
foreach ($sprite in $heartSprites) {
    $bmp = New-Canvas 9 9
    $bmp.Save((Join-Path $heartDir "$sprite.png"), [System.Drawing.Imaging.ImageFormat]::Png)
    $bmp.Dispose()
}
Write-Host "wrote $($heartSprites.Count) blank heart sprites"
foreach ($sprite in @("armor_full", "armor_half", "armor_empty")) {
    $bmp = New-Canvas 9 9
    $bmp.Save((Join-Path $vanillaHud "$sprite.png"), [System.Drawing.Imaging.ImageFormat]::Png)
    $bmp.Dispose()
    Write-Host "wrote $vanillaHud\$sprite.png"
}

# --- Blank the WHITE boss bar ---------------------------------------------------------------
# The balance readout rides a WHITE boss bar whose bar body is invisible; only the name text
# (hud-font glyphs) shows. Other boss bar colors keep their vanilla art (cycle/toxic timers).
$bossBar = Join-Path $PSScriptRoot "..\..\resource-packs\skypvp-core\assets\minecraft\textures\gui\sprites\boss_bar"
New-Item -ItemType Directory -Force $bossBar | Out-Null
foreach ($sprite in @("white_background", "white_progress")) {
    $bmp = New-Canvas 182 5
    $bmp.Save((Join-Path $bossBar "$sprite.png"), [System.Drawing.Imaging.ImageFormat]::Png)
    $bmp.Dispose()
    Write-Host "wrote $bossBar\$sprite.png"
}

Write-Host "HUD textures generated."
