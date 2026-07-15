# Generates SkyPvP quest-dialogue HUD panels (BetonQuest-style layout).
# Solid speech panel (left) + nameplate + portrait + choice panel (right).
# NOT stacked thin bars — one opaque panel per region.
#
# CRITICAL: Vanilla rejects bitmap providers when ascent > height.
# Art is drawn at the TOP of a taller canvas so height >= ascent.
#
# Pixel map (screen center = 0), mirrored by QuestDialogueFont / dialogue.json:
#   speech_panel   200x72 (art 200x56, ascent 48) @ X=-170 — main dialogue body
#   nameplate       96x64 (art  96x16, ascent 56)          — overlaps speech top-left
#   portrait        40x48 (art  40x40, ascent 34)          — aligns with the panel well
#   choice_panel   140x72 (art 140x56, ascent 48) @ X=+55  — options on the right
#   choice_cursor   10x48 (art  10x10, ascents 39/27/15/3)
#   icons           10x10 (ascent -9, controls row under the panel)
Add-Type -AssemblyName System.Drawing

$packRoot = Join-Path $PSScriptRoot "..\..\resource-packs\skypvp-core\assets\skypvp"
$root = Join-Path $packRoot "textures\dialogue"
$fontTex = Join-Path $packRoot "textures\font"
New-Item -ItemType Directory -Force $root | Out-Null
New-Item -ItemType Directory -Force $fontTex | Out-Null

function New-Canvas([int]$w, [int]$h) {
    New-Object System.Drawing.Bitmap($w, $h, [System.Drawing.Imaging.PixelFormat]::Format32bppArgb)
}
function Save-To($bmp, [string]$dir, [string]$name) {
    $path = Join-Path $dir $name
    $bmp.Save($path, [System.Drawing.Imaging.ImageFormat]::Png)
    $bmp.Dispose()
    Write-Host "wrote $path ($((Get-Item $path).Length) bytes)"
}

# Cyber / Aether palette (keep brand; layout matches RPG HUD reference)
$edge     = [System.Drawing.Color]::FromArgb(255, 64, 220, 255)
$edgeSoft = [System.Drawing.Color]::FromArgb(255, 40, 150, 190)
$edgeDim  = [System.Drawing.Color]::FromArgb(200, 30, 90, 120)
$fillTop  = [System.Drawing.Color]::FromArgb(242, 18, 30, 46)
$fillBot  = [System.Drawing.Color]::FromArgb(238, 8, 13, 22)
$plateTop = [System.Drawing.Color]::FromArgb(248, 24, 44, 62)
$plateBot = [System.Drawing.Color]::FromArgb(246, 12, 22, 34)
$silC     = [System.Drawing.Color]::FromArgb(230, 50, 70, 90)
$iconC    = [System.Drawing.Color]::FromArgb(255, 220, 235, 245)
$accent   = [System.Drawing.Color]::FromArgb(255, 255, 183, 77)
$accentHi = [System.Drawing.Color]::FromArgb(255, 255, 214, 140)
$corner   = [System.Drawing.Color]::FromArgb(1, 128, 128, 128)

function Rect($bmp, [int]$x, [int]$y, [int]$w, [int]$h, $color) {
    for ($i = $x; $i -lt ($x + $w); $i++) {
        for ($j = $y; $j -lt ($y + $h); $j++) {
            if ($i -ge 0 -and $j -ge 0 -and $i -lt $bmp.Width -and $j -lt $bmp.Height) {
                $bmp.SetPixel($i, $j, $color)
            }
        }
    }
}
function Frame($bmp, [int]$x, [int]$y, [int]$w, [int]$h, $color) {
    Rect $bmp $x $y $w 1 $color
    Rect $bmp $x ($y + $h - 1) $w 1 $color
    Rect $bmp $x $y 1 $h $color
    Rect $bmp ($x + $w - 1) $y 1 $h $color
}
# Vertical gradient fill between two colors.
function GradientRect($bmp, [int]$x, [int]$y, [int]$w, [int]$h, $top, $bot) {
    for ($j = 0; $j -lt $h; $j++) {
        $t = if ($h -le 1) { 0.0 } else { $j / ($h - 1.0) }
        $a = [int]($top.A + ($bot.A - $top.A) * $t)
        $r = [int]($top.R + ($bot.R - $top.R) * $t)
        $g = [int]($top.G + ($bot.G - $top.G) * $t)
        $b = [int]($top.B + ($bot.B - $top.B) * $t)
        Rect $bmp $x ($y + $j) $w 1 ([System.Drawing.Color]::FromArgb($a, $r, $g, $b))
    }
}
# Bright L-shaped ticks on all four corners of a frame.
function CornerTicks($bmp, [int]$x, [int]$y, [int]$w, [int]$h, [int]$len, $color) {
    Rect $bmp $x $y $len 1 $color;                       Rect $bmp $x $y 1 $len $color
    Rect $bmp ($x + $w - $len) $y $len 1 $color;         Rect $bmp ($x + $w - 1) $y 1 $len $color
    Rect $bmp $x ($y + $h - 1) $len 1 $color;            Rect $bmp $x ($y + $h - $len) 1 $len $color
    Rect $bmp ($x + $w - $len) ($y + $h - 1) $len 1 $color; Rect $bmp ($x + $w - 1) ($y + $h - $len) 1 $len $color
}
function Write-Label($bmp, [string]$text, [int]$x, [int]$y, $color, [float]$size = 7.0) {
    $g = [System.Drawing.Graphics]::FromImage($bmp)
    $g.TextRenderingHint = [System.Drawing.Text.TextRenderingHint]::SingleBitPerPixelGridFit
    $font = New-Object System.Drawing.Font("Consolas", $size, [System.Drawing.FontStyle]::Bold)
    $brush = New-Object System.Drawing.SolidBrush($color)
    $g.DrawString($text, $font, $brush, $x, $y)
    $brush.Dispose(); $font.Dispose(); $g.Dispose()
}

# --- Solid speech panel (pad height 72 so ascent 48 is legal; art fills top 56px) ---
$bmp = New-Canvas 200 72
$bmp.SetPixel(0, 0, $corner)
$bmp.SetPixel(199, 0, $corner)
GradientRect $bmp 0 0 200 56 $fillTop $fillBot
Frame $bmp 0 0 200 56 $edgeSoft
Frame $bmp 2 2 196 52 $edgeDim
CornerTicks $bmp 0 0 200 56 4 $edge
# Subtle top sheen under the border
Rect $bmp 3 3 194 1 ([System.Drawing.Color]::FromArgb(60, 120, 220, 255))
# Portrait cutout (darker well, bottom-left; QuestDialogueFont.PORTRAIT_X aligns here)
Rect $bmp 6 14 40 40 ([System.Drawing.Color]::FromArgb(250, 5, 9, 15))
Frame $bmp 6 14 40 40 $edgeDim
Save-To $bmp $root "speech_panel.png"

# --- Nameplate (overlaps speech top-left; art 96x16, ascent 56) ---
$bmp = New-Canvas 96 64
$bmp.SetPixel(0, 0, $corner)
$bmp.SetPixel(95, 0, $corner)
GradientRect $bmp 0 0 96 16 $plateTop $plateBot
Frame $bmp 0 0 96 16 $edge
# Accent underline so the plate visually "sits on" the panel edge
Rect $bmp 1 14 94 1 $edgeSoft
CornerTicks $bmp 0 0 96 16 3 $accentHi
Save-To $bmp $root "nameplate.png"

# --- Portrait frame (art 40x40, ascent 34 — matches the well in speech_panel) ---
$bmp = New-Canvas 40 48
$bmp.SetPixel(0, 0, $corner)
$bmp.SetPixel(39, 0, $corner)
GradientRect $bmp 0 0 40 40 $fillTop $fillBot
Frame $bmp 0 0 40 40 $edgeSoft
Frame $bmp 2 2 36 36 $edgeDim
CornerTicks $bmp 0 0 40 40 3 $edge
Rect $bmp 4 4 32 32 ([System.Drawing.Color]::FromArgb(248, 6, 11, 18))
Save-To $bmp $root "portrait_frame.png"

# --- Silhouette (fills the portrait interior; ascent 32) ---
$bmp = New-Canvas 32 48
$bmp.SetPixel(0, 0, $corner)
$bmp.SetPixel(31, 0, $corner)
Rect $bmp 10 4 12 12 $silC
Rect $bmp 13 16 6 2 $silC
Rect $bmp 6 18 20 12 $silC
Save-To $bmp $root "silhouette.png"

# --- Choice panel (right side; art 140x56, ascent 48) ---
$bmp = New-Canvas 140 72
$bmp.SetPixel(0, 0, $corner)
$bmp.SetPixel(139, 0, $corner)
GradientRect $bmp 0 0 140 56 $fillTop $fillBot
Frame $bmp 0 0 140 56 $edgeSoft
Frame $bmp 2 2 136 52 $edgeDim
CornerTicks $bmp 0 0 140 56 4 $edge
Rect $bmp 3 3 134 1 ([System.Drawing.Color]::FromArgb(60, 120, 220, 255))
Save-To $bmp $root "choice_panel.png"

# --- Choice cursor: amber arrow pointing at the selected row ---
$bmp = New-Canvas 10 48
$bmp.SetPixel(9, 0, $corner)
for ($i = 0; $i -lt 5; $i++) {
    Rect $bmp 1 (1 + $i) ($i + 1) 1 $accent
    Rect $bmp 1 (9 - $i) ($i + 1) 1 $accent
}
Rect $bmp 1 5 8 1 $accentHi
Save-To $bmp $root "choice_cursor.png"

function New-Icon([string]$name, [string]$glyph) {
    $b = New-Canvas 10 10
    $b.SetPixel(0, 0, $corner)
    $b.SetPixel(9, 0, $corner)
    GradientRect $b 0 0 10 10 $plateTop $plateBot
    Frame $b 0 0 10 10 $edgeSoft
    Write-Label $b $glyph 1 0 $iconC 7.0
    Save-To $b $root "$name.png"
}
New-Icon "icon_continue" "S"
New-Icon "icon_choose" "W"
New-Icon "icon_leave" "Q"

# Remove obsolete thin-bar textures if present
foreach ($stale in @("bubble_row.png", "bubble_header.png")) {
    $p = Join-Path $root $stale
    if (Test-Path $p) { Remove-Item $p -Force; Write-Host "removed stale $stale" }
}

# --- Padded ascii atlas for dialogue_text_* vertical shifts ---------------------------------
# Only rebuilt when missing: it needs the Minecraft client jar and never changes otherwise.
$asciiOut = Join-Path $fontTex "dialogue_ascii.png"
if (Test-Path $asciiOut) {
    Write-Host "dialogue_ascii.png present, skipping atlas rebuild"
} else {
    $clientJar = Join-Path $env:APPDATA ".minecraft\versions\1.21.11\1.21.11.jar"
    if (-not (Test-Path $clientJar)) {
        $clientJar = Join-Path $env:APPDATA ".minecraft\versions\1.21.10\1.21.10.jar"
    }
    if (-not (Test-Path $clientJar)) {
        throw "Minecraft client jar not found (need ascii.png). Checked 1.21.11 / 1.21.10 under %APPDATA%\.minecraft\versions"
    }

    $tmpDir = Join-Path $env:TEMP ("skypvp-dialogue-ascii-" + [guid]::NewGuid().ToString("N"))
    New-Item -ItemType Directory -Force $tmpDir | Out-Null
    try {
        Push-Location $tmpDir
        jar xf $clientJar assets/minecraft/textures/font/ascii.png
        Pop-Location
        $srcPath = Join-Path $tmpDir "assets\minecraft\textures\font\ascii.png"
        if (-not (Test-Path $srcPath)) {
            throw "ascii.png missing from client jar: $clientJar"
        }
        $src = New-Object System.Drawing.Bitmap($srcPath)
        $cols = 16
        $rows = 16
        $cellW = [int]($src.Width / $cols)
        $cellH = [int]($src.Height / $rows)
        $padH = 56
        $out = New-Canvas ($cols * $cellW) ($rows * $padH)
        for ($row = 0; $row -lt $rows; $row++) {
            for ($col = 0; $col -lt $cols; $col++) {
                for ($x = 0; $x -lt $cellW; $x++) {
                    for ($y = 0; $y -lt $cellH; $y++) {
                        $c = $src.GetPixel(($col * $cellW) + $x, ($row * $cellH) + $y)
                        $out.SetPixel(($col * $cellW) + $x, ($row * $padH) + $y, $c)
                    }
                }
            }
        }
        $src.Dispose()
        Save-To $out $fontTex "dialogue_ascii.png"
    } finally {
        Remove-Item -Recurse -Force $tmpDir -ErrorAction SilentlyContinue
    }
}

Write-Host "Dialogue RPG panels ready under $root"
