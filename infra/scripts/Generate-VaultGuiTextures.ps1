# Generates the Vault GUI overlay (font-in-title technique), scrollbar thumb states,
# scroll button item textures, and item art for syringes/bandages/materials.
#
# Overlay geometry (6-row chest GUI, 176x222, drawn from GUI origin):
#   slot grid starts at (8,18), 18px cells; column 8 (x 152..168) hosts the scroll rail.
#   Buttons at slots 17 (row1) and 53 (row5 bottom) get connected plates; the groove spans
#   the track slots between them so rail + buttons read as one piece of UI.
Add-Type -AssemblyName System.Drawing

$guiRoot = Join-Path $PSScriptRoot "..\..\resource-packs\skypvp-core\assets\skypvp\textures\gui"
$itemRoot = Join-Path $PSScriptRoot "..\..\resource-packs\skypvp-core\assets\skypvp\textures\item"
New-Item -ItemType Directory -Force $guiRoot | Out-Null
New-Item -ItemType Directory -Force $itemRoot | Out-Null

function New-Canvas([int]$w, [int]$h) {
    New-Object System.Drawing.Bitmap($w, $h, [System.Drawing.Imaging.PixelFormat]::Format32bppArgb)
}
function Save-To([string]$root, $bmp, [string]$name) {
    $path = Join-Path $root $name
    $bmp.Save($path, [System.Drawing.Imaging.ImageFormat]::Png)
    $bmp.Dispose()
    Write-Host "wrote $path"
}

$edge    = [System.Drawing.Color]::FromArgb(255, 64, 240, 255)   # cyan accent
$edgeDim = [System.Drawing.Color]::FromArgb(255, 26, 58, 78)     # dim cyan inner border
$panel   = [System.Drawing.Color]::FromArgb(255, 11, 21, 34)     # OPAQUE base panel
$band    = [System.Drawing.Color]::FromArgb(255, 16, 28, 44)     # title/header band
$cellEdge = [System.Drawing.Color]::FromArgb(255, 38, 54, 74)
$cellFill = [System.Drawing.Color]::FromArgb(255, 19, 30, 43)
$cellDark = [System.Drawing.Color]::FromArgb(255, 10, 17, 26)
$groove  = [System.Drawing.Color]::FromArgb(255, 8, 14, 22)
$plate   = [System.Drawing.Color]::FromArgb(255, 27, 42, 61)
$thumbC  = [System.Drawing.Color]::FromArgb(255, 64, 240, 255)

function Rect($bmp, [int]$x, [int]$y, [int]$w, [int]$h, $color) {
    for ($i = $x; $i -lt ($x + $w); $i++) { for ($j = $y; $j -lt ($y + $h); $j++) {
        if ($i -ge 0 -and $j -ge 0 -and $i -lt $bmp.Width -and $j -lt $bmp.Height) { $bmp.SetPixel($i, $j, $color) }
    } }
}
function Frame($bmp, [int]$x, [int]$y, [int]$w, [int]$h, $color) {
    Rect $bmp $x $y $w 1 $color; Rect $bmp $x ($y + $h - 1) $w 1 $color
    Rect $bmp $x $y 1 $h $color; Rect $bmp ($x + $w - 1) $y 1 $h $color
}
function SlotCell($bmp, [int]$x, [int]$y) {
    # 18x18 slot cell at the vanilla slot frame position: crisp edge, recessed fill.
    Frame $bmp $x $y 18 18 $cellEdge
    Rect $bmp ($x + 1) ($y + 1) 16 16 $cellFill
    Rect $bmp ($x + 1) ($y + 1) 16 1 $cellDark        # top inner shadow (recessed look)
    Rect $bmp ($x + 1) ($y + 1) 1 16 $cellDark
}
function ButtonPlate($bmp, [int]$x, [int]$y) {
    Frame $bmp $x $y 18 18 $edge
    Rect $bmp ($x + 1) ($y + 1) 16 16 $plate
}

# --- vault_overlay.png (176x222): FULL opaque skin --------------------------------------
# Slot math (generic_54): slot frames at (7+18c, 17+18r); top rows r0..5, player inv rows
# at y 139/157/175, hotbar y 197. Buttons: 0=close(r0c0) 8=back(r0c8) 17=up(r1c8)
# 53=down(r5c8); track slots 26/35/44 sit under the groove.
$bmp = New-Canvas 176 222
Rect $bmp 0 0 176 222 $panel
Frame $bmp 0 0 176 222 $edge
Frame $bmp 1 1 174 220 $edgeDim
# title band + header row band
Rect $bmp 2 2 172 14 $band
Rect $bmp 2 16 172 20 $band
Rect $bmp 2 36 172 1 $edgeDim
# close (slot 0) + back (slot 8) button plates in the header row
ButtonPlate $bmp 7 17
ButtonPlate $bmp 151 17
# content grid: rows 1..5, cols 0..7
for ($r = 1; $r -le 5; $r++) {
    for ($c = 0; $c -le 7; $c++) {
        SlotCell $bmp (7 + 18 * $c) (17 + 18 * $r)
    }
}
# scroll rail column over col 8 rows 1..5 (x 150..173, y 35..125)
Rect $bmp 150 35 24 90 $plate
Frame $bmp 150 35 24 90 $edgeDim
ButtonPlate $bmp 151 35     # up (slot 17)
ButtonPlate $bmp 151 107    # down (slot 53)
# groove between the buttons; thumb (14 tall) travels y 57..91 -> 6 stops, 7px apart
Rect $bmp 156 55 12 50 $groove
Frame $bmp 156 55 12 50 $edgeDim
# divider band above the player inventory label
Rect $bmp 2 126 172 12 $band
Rect $bmp 2 126 172 1 $edgeDim
# player inventory grid (3 rows + hotbar)
for ($r = 0; $r -le 2; $r++) {
    for ($c = 0; $c -le 8; $c++) {
        SlotCell $bmp (7 + 18 * $c) (139 + 18 * $r)
    }
}
for ($c = 0; $c -le 8; $c++) { SlotCell $bmp (7 + 18 * $c) 197 }
Save-To $guiRoot $bmp "vault_overlay.png"

# --- vault_thumb.png (10x14; drawn at 6 vertical stops via font ascents) -------------------
$thumb = New-Canvas 10 14
Rect $thumb 0 0 10 14 $thumbC
Rect $thumb 1 1 8 12 $plate
Rect $thumb 2 4 6 1 $thumbC
Rect $thumb 2 7 6 1 $thumbC
Rect $thumb 2 10 6 1 $thumbC
Save-To $guiRoot $thumb "vault_thumb.png"

# --- Scroll button item textures (16x16) ---------------------------------------------------
function New-Item16([string]$name, [string[]]$rows, $fg) {
    $bmp = New-Canvas 16 16
    for ($y = 0; $y -lt 16; $y++) {
        for ($x = 0; $x -lt 16; $x++) {
            switch ($rows[$y][$x]) {
                '#' { $bmp.SetPixel($x, $y, $fg) }
                '+' { $bmp.SetPixel($x, $y, $plate) }
                '@' { $bmp.SetPixel($x, $y, $edge) }
                'r' { $bmp.SetPixel($x, $y, [System.Drawing.Color]::FromArgb(255, 255, 70, 85)) }
                'w' { $bmp.SetPixel($x, $y, [System.Drawing.Color]::FromArgb(255, 235, 240, 245)) }
                'g' { $bmp.SetPixel($x, $y, [System.Drawing.Color]::FromArgb(255, 120, 130, 145)) }
                'y' { $bmp.SetPixel($x, $y, [System.Drawing.Color]::FromArgb(255, 255, 215, 90)) }
                'c' { $bmp.SetPixel($x, $y, [System.Drawing.Color]::FromArgb(255, 90, 210, 130)) }
            }
        }
    }
    Save-To $itemRoot $bmp $name
}

New-Item16 "ui_scroll_up.png" @(
    "@@@@@@@@@@@@@@@@","@++++++++++++++@","@++++++#+++++++@","@+++++###++++++@",
    "@++++#####+++++@","@+++#######++++@","@++#########+++@","@++++#####+++++@",
    "@++++#####+++++@","@++++#####+++++@","@++++#####+++++@","@++++++++++++++@",
    "@++++++++++++++@","@++++++++++++++@","@++++++++++++++@","@@@@@@@@@@@@@@@@"
) $thumbC
New-Item16 "ui_scroll_down.png" @(
    "@@@@@@@@@@@@@@@@","@++++++++++++++@","@++++++++++++++@","@++++++++++++++@",
    "@++++#####+++++@","@++++#####+++++@","@++++#####+++++@","@++++#####+++++@",
    "@++#########+++@","@+++#######++++@","@++++#####+++++@","@+++++###++++++@",
    "@++++++#+++++++@","@++++++++++++++@","@++++++++++++++@","@@@@@@@@@@@@@@@@"
) $thumbC

# --- Medic + material item art (16x16) -----------------------------------------------------
New-Item16 "medic_syringe.png" @(
    "..............ww",".............ww.","............ww..","......rr...ww...",
    ".....rrrr.ww....","....rrrrrww.....","...rrrrrww......","..rrrrrr........",
    ".grrrrr.........","ggrrr...........","gg..............","gg..............",
    ".g..............","................","................","................"
) $thumbC
New-Item16 "medic_bandage.png" @(
    "................","....wwwwwwww....","...wwwwwwwwww...","..www.wwww.www..",
    "..ww.w.ww.w.ww..","..www.wwww.www..","..wwwwwwwwwwww..","..ww.w.ww.w.ww..",
    "..www.wwww.www..","..wwwwwwwwwwww..","..ww.w.ww.w.ww..","..www.wwww.www..",
    "...wwwwwwwwww...","....wwwwwwww....","................","................"
) $thumbC
New-Item16 "mat_scrap_metal.png" @(
    "................","..gg......gg....",".gggg....gggg...",".gggggggggggg...",
    "..gggwwgggggg...","..ggwwwwggg.....","..gggwwggggg....",".ggggggggggggg..",
    ".gg..gggg..ggg..","......gg........","....gggggg......","...gg....gg.....",
    "................","................","................","................"
) $thumbC
New-Item16 "mat_circuit.png" @(
    "................","..cccccccccccc..","..c..........c..","..c.yy..yy...c..",
    "..c.yy..yy...c..","..c..........c..","..c..yyyy....c..","..c..y..y....c..",
    "..c..yyyy....c..","..c..........c..","..c.yy..yy...c..","..c.yy..yy...c..",
    "..c..........c..","..cccccccccccc..","................","................"
) $thumbC
New-Item16 "mat_cloth.png" @(
    "................","...wwwwwwwww....","..wwwwwwwwwww...","..ww.ww.ww.ww...",
    "..wwwwwwwwwww...","..ww.ww.ww.ww...","..wwwwwwwwwww...","..ww.ww.ww.ww...",
    "..wwwwwwwwwww...","..ww.ww.ww.ww...","..wwwwwwwwwww...","...wwwwwwwww....",
    "................","................","................","................"
) $thumbC
New-Item16 "mat_chem.png" @(
    ".....wwww.......",".....w..w.......",".....w..w.......",".....w..w.......",
    "....cw..wc......","...cw....wc.....","..cw..cc..wc....","..w..cccc..w....",
    "..w.cccccc.w....","..w.cccccc.w....","..wc.cccc.cw....","...wc....cw.....",
    "....wwwwww......","................","................","................"
) $thumbC
New-Item16 "mat_alloy.png" @(
    "................","....gggggggg....","...ggwwwwwwgg...","..ggwggggggwgg..",
    "..gwggggggggwg..","..gwggggggggwg..","..gwggggggggwg..","..gwggggggggwg..",
    "..ggwggggggwgg..","...ggwwwwwwgg...","....gggggggg....","................",
    "................","................","................","................"
) $thumbC
New-Item16 "mat_crystal.png" @(
    ".......@........","......@@@.......",".....@@w@@......","....@@ww@@@.....",
    "...@@www@@@@....","...@wwww@@@@....","...@www@@@@@....","...@ww@@@@@@....",
    "....@w@@@@@.....",".....@@@@@......","......@@@.......",".......@........",
    "................","................","................","................"
) $thumbC

Write-Host "Vault GUI + item textures generated."
