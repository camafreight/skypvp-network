# Generates SkyPvP ESC / pause-menu placeholder bitmaps (font-in-label technique).
# Edit the PNGs under resource-packs/skypvp-core/assets/skypvp/textures/gui/escape_menu/
# then re-run this ONLY if you need to regenerate empty wireframes.
#
# Each art file is 255x150 and MUST keep the near-invisible corner markers (top-left +
# top-right) so Minecraft measures glyph width correctly when most of the image is empty.
#
# CRITICAL: button art must sit in the BOTTOM band (y ≈ 122..149). Font ascent maps the
# text baseline near the bottom of the glyph — drawing mid-image makes outlines float above
# the real pause buttons (see codingcat2468/custom-escape-menu reference assets).
Add-Type -AssemblyName System.Drawing

$guiRoot = Join-Path $PSScriptRoot "..\..\resource-packs\skypvp-core\assets\skypvp\textures\gui\escape_menu"
$internalRoot = Join-Path $PSScriptRoot "..\..\resource-packs\skypvp-core\assets\skypvp\textures\gui\escape_menu_internal"
New-Item -ItemType Directory -Force $guiRoot | Out-Null
New-Item -ItemType Directory -Force $internalRoot | Out-Null

function New-Canvas([int]$w, [int]$h) {
    New-Object System.Drawing.Bitmap($w, $h, [System.Drawing.Imaging.PixelFormat]::Format32bppArgb)
}
function Save-To([string]$root, $bmp, [string]$name) {
    $path = Join-Path $root $name
    $bmp.Save($path, [System.Drawing.Imaging.ImageFormat]::Png)
    $bmp.Dispose()
    Write-Host "wrote $path"
}

$edge    = [System.Drawing.Color]::FromArgb(255, 64, 240, 255)
$edgeDim = [System.Drawing.Color]::FromArgb(200, 26, 58, 78)
$labelC  = [System.Drawing.Color]::FromArgb(230, 200, 230, 240)
$hintC   = [System.Drawing.Color]::FromArgb(180, 120, 160, 180)
$corner  = [System.Drawing.Color]::FromArgb(1, 128, 128, 128)
$fill    = [System.Drawing.Color]::FromArgb(55, 11, 21, 34)

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
function DashFrame($bmp, [int]$x, [int]$y, [int]$w, [int]$h, $color) {
    for ($i = $x; $i -lt ($x + $w); $i += 2) {
        if ($i -ge 0 -and $i -lt $bmp.Width) {
            if ($y -ge 0 -and $y -lt $bmp.Height) { $bmp.SetPixel($i, $y, $color) }
            $yb = [Math]::Min($bmp.Height - 1, $y + $h - 1)
            $bmp.SetPixel($i, $yb, $color)
        }
    }
    for ($j = $y; $j -lt ($y + $h); $j += 2) {
        if ($j -ge 0 -and $j -lt $bmp.Height) {
            if ($x -ge 0 -and $x -lt $bmp.Width) { $bmp.SetPixel($x, $j, $color) }
            $xr = [Math]::Min($bmp.Width - 1, $x + $w - 1)
            $bmp.SetPixel($xr, $j, $color)
        }
    }
}
function Write-Label($bmp, [string]$text, [int]$x, [int]$y, $color, [float]$size = 9.0) {
    $g = [System.Drawing.Graphics]::FromImage($bmp)
    $g.TextRenderingHint = [System.Drawing.Text.TextRenderingHint]::SingleBitPerPixelGridFit
    $font = New-Object System.Drawing.Font("Consolas", $size, [System.Drawing.FontStyle]::Bold)
    $brush = New-Object System.Drawing.SolidBrush($color)
    $g.DrawString($text, $font, $brush, $x, $y)
    $brush.Dispose(); $font.Dispose(); $g.Dispose()
}

# Button art band matching proven custom-escape packs: content lives at y 122..149.
$BUTTON_Y = 122
$BUTTON_H = 27

function New-ButtonPlaceholder([string]$name, [string]$title, [string]$role, [bool]$halfWidth) {
    $bmp = New-Canvas 255 150
    $bmp.SetPixel(0, 0, $corner)
    $bmp.SetPixel(254, 0, $corner)

    if ($halfWidth) {
        # Half-row buttons (~98 GUI px): keep art centered like reference options.png (x≈65..188)
        $x = 65; $w = 124
    } else {
        # Full-width pause buttons
        $x = 0; $w = 255
    }

    Rect $bmp $x $BUTTON_Y $w $BUTTON_H $fill
    DashFrame $bmp $x $BUTTON_Y $w $BUTTON_H $edge
    Frame $bmp ($x + 2) ($BUTTON_Y + 2) ($w - 4) ($BUTTON_H - 4) $edgeDim
    Write-Label $bmp $title ($x + 6) ($BUTTON_Y + 4) $labelC 9.0
    Write-Label $bmp $role ($x + 6) ($BUTTON_Y + 14) $hintC 7.0
    Save-To $guiRoot $bmp "$name.png"
}

function New-LogoPlaceholder([string]$name) {
    $bmp = New-Canvas 255 150
    $bmp.SetPixel(0, 0, $corner)
    $bmp.SetPixel(254, 0, $corner)
    # Logo rides above the Back-to-Game baseline — keep art in the UPPER half
    DashFrame $bmp 20 10 215 100 $edge
    Write-Label $bmp "LOGO" 28 20 $labelC 12.0
    Write-Label $bmp "EDIT ME · header overlay" 28 48 $hintC 9.0
    Write-Label $bmp "255x150 · keep corner pixels" 28 90 $hintC 8.0
    Save-To $guiRoot $bmp "$name.png"
}

function New-BottomCardPlaceholder([string]$name) {
    $bmp = New-Canvas 255 150
    $bmp.SetPixel(0, 0, $corner)
    $bmp.SetPixel(254, 0, $corner)
    # bottom_card uses negative ascent — draw near TOP of the bitmap so it hangs under Disconnect
    DashFrame $bmp 8 4 239 60 $edge
    Rect $bmp 10 6 235 56 $fill
    Write-Label $bmp "BOTTOM CARD" 16 12 $labelC 10.0
    Write-Label $bmp "EDIT ME · footer under disconnect" 16 32 $hintC 8.0
    Write-Label $bmp "255x150 · keep corner pixels" 16 46 $hintC 7.0
    Save-To $guiRoot $bmp "$name.png"
}

# Spacer glyph source (negative-advance trick)
$square = New-Canvas 16 16
Rect $square 0 0 16 16 ([System.Drawing.Color]::FromArgb(1, 255, 255, 255))
Save-To $internalRoot $square "square.png"

# Preserve a hand-painted logo if present (larger / non-placeholder)
$logoPath = Join-Path $guiRoot "logo.png"
$skipLogo = $false
if (Test-Path $logoPath) {
    $len = (Get-Item $logoPath).Length
    if ($len -gt 8000) {
        Write-Host "keeping existing logo.png ($len bytes)"
        $skipLogo = $true
    }
}
if (-not $skipLogo) {
    New-LogoPlaceholder "logo"
}

New-BottomCardPlaceholder "bottom_card"

# Full-width
New-ButtonPlaceholder "back_to_game"     "BACK TO GAME"      "menu.returnToGame"        $false
New-ButtonPlaceholder "quit"             "DISCONNECT"        "menu.disconnect"          $false
New-ButtonPlaceholder "open_to_lan"      "OPEN TO LAN"       "menu.shareToLan"          $false
New-ButtonPlaceholder "feedback"         "FEEDBACK"          "menu.feedback"            $false
New-ButtonPlaceholder "server_links"     "SERVER LINKS"      "menu.server_links"        $false

# Half-width row buttons (left/right pairs)
New-ButtonPlaceholder "send_feedback"    "SEND FEEDBACK"     "menu.sendFeedback"        $true
New-ButtonPlaceholder "report_bugs"      "REPORT BUGS"       "menu.reportBugs"          $true
New-ButtonPlaceholder "options"          "OPTIONS"           "menu.options"             $true
New-ButtonPlaceholder "player_reporting" "PLAYER REPORTING"  "menu.playerReporting"     $true
New-ButtonPlaceholder "advancements"     "ADVANCEMENTS"      "gui.advancements"         $true
New-ButtonPlaceholder "statistics"       "STATISTICS"        "gui.stats"                $true

Write-Host "ESC menu placeholders realigned (button band y=$BUTTON_Y..$($BUTTON_Y+$BUTTON_H-1))."
Write-Host "Edit PNGs in:`n  $guiRoot"
