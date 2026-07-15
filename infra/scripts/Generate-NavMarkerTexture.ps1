# Generates the navigator marker plate texture: a flat regular octagon, 32x32.
# Grayscale so the leather dye tint (set by the Waypoint/WaypointMarker API) colors it:
# near-white rim -> bright tint, darker center -> deep tint behind the icon glyph.
# Subtle hash noise gives the "slightly textured" surface. Alpha is binary (255 inside,
# 0 outside) — deliberately NOT 253, which is the pack shaders' emissive-plasma marker.
Add-Type -AssemblyName System.Drawing

$texDir = Join-Path $PSScriptRoot "..\..\resource-packs\skypvp-core\assets\skypvp\textures\item"
$size = 32
$half = ($size - 1) / 2.0
$radius = $half - 0.5          # octagon apothem in pixels
$rimWidth = 2.6

# Deterministic per-pixel hash noise in [-1, 1].
function Get-Noise([int]$x, [int]$y) {
    $h = ($x * 374761393 + $y * 668265263) -band 0x7FFFFFFF
    $h = (($h -bxor ($h -shr 13)) * 1274126177) -band 0x7FFFFFFF
    return ((($h -band 0xFFFF) / 65535.0) * 2.0) - 1.0
}

$bmp = New-Object System.Drawing.Bitmap $size, $size
for ($y = 0; $y -lt $size; $y++) {
    $dy = [Math]::Abs($y - $half)
    for ($x = 0; $x -lt $size; $x++) {
        $dx = [Math]::Abs($x - $half)
        # Regular octagon = square ∩ 45°-rotated square.
        $d = [Math]::Max([Math]::Max($dx, $dy), ($dx + $dy) / [Math]::Sqrt(2.0))
        if ($d -gt $radius) {
            $bmp.SetPixel($x, $y, [System.Drawing.Color]::FromArgb(0, 0, 0, 0))
            continue
        }
        $noise = Get-Noise $x $y
        if ($d -ge ($radius - $rimWidth)) {
            # Bright rim, faintly textured.
            $g = [int](233 + 12 * $noise)
        } else {
            # Fill: darker toward the center so the icon glyph reads on top.
            $t = $d / [Math]::Max(0.001, $radius - $rimWidth)   # 0 center -> 1 at rim
            $g = [int](128 + 42 * $t + 10 * $noise)
        }
        if ($g -lt 0) { $g = 0 } elseif ($g -gt 255) { $g = 255 }
        $bmp.SetPixel($x, $y, [System.Drawing.Color]::FromArgb(255, $g, $g, $g))
    }
}
$png = Join-Path $texDir "nav_marker_octagon.png"
$bmp.Save($png, [System.Drawing.Imaging.ImageFormat]::Png)
$bmp.Dispose()
Write-Output "Wrote $png"
