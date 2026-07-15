# Generates a soft white 16x16 PNG for extract_beacon_beam.
# White RGB + alpha so leather dye (minecraft:dye tint) recolors the pillar in-game.
Add-Type -AssemblyName System.Drawing
$path = Join-Path $PSScriptRoot "..\..\resource-packs\skypvp-core\assets\skypvp\textures\item\extract_beacon_beam.png"
$dir = Split-Path $path -Parent
if (-not (Test-Path $dir)) { New-Item -ItemType Directory -Force -Path $dir | Out-Null }

$bmp = New-Object System.Drawing.Bitmap 16, 16
for ($y = 0; $y -lt 16; $y++) {
    for ($x = 0; $x -lt 16; $x++) {
        $dx = ($x - 7.5) / 7.5
        $radial = [Math]::Abs($dx)
        $core = [Math]::Exp(-$radial * $radial * 4.5)
        $edge = [Math]::Exp(-$radial * $radial * 1.2)
        $pulse = 0.85 + 0.15 * [Math]::Sin(($y / 16.0) * [Math]::PI * 2.0)
        $a = [int][Math]::Min(255, [Math]::Max(0, (40 + 215 * $edge * $pulse)))
        $v = [int][Math]::Min(255, [Math]::Max(180, (200 + 55 * $core)))
        $bmp.SetPixel($x, $y, [System.Drawing.Color]::FromArgb($a, $v, $v, $v))
    }
}
$bmp.Save($path, [System.Drawing.Imaging.ImageFormat]::Png)
$bmp.Dispose()
Write-Output "Wrote $path"
